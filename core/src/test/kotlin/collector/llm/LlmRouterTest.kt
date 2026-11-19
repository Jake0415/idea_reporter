package collector.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*

class LlmRouterTest {
    private val request = LlmRequest("", "", "", buildJsonObject {}, "test", 16)
    private val guard = CostGuard(1.0)
    private val alternate = Fake(ProviderId.OPENAI) { result(ProviderId.OPENAI, it) }

    @Test fun fallsBackOnceOnFailureRefusalOrTimeout() = runTest {
        val failures: List<suspend (String) -> LlmResult> = listOf(
            { throw IllegalStateException("임의 오류") },
            { result(ProviderId.ANTHROPIC, it).copy(refused = true) },
            { delay(200); result(ProviderId.ANTHROPIC, it) }
        )
        for (failure in failures) {
            val router = LlmRouter(listOf(Fake(ProviderId.ANTHROPIC, failure), alternate), guard, timeoutMillis = 100)
            assertEquals(ProviderId.OPENAI, router.complete("classify", request, "classify", "default").provider)
        }
        assertEquals(3, alternate.requests)
        assertEquals(6, guard.calls.size)
        assertEquals("timeout", guard.calls[4].error)
        assertTrue(guard.calls.filter { it.fallbackUsed }.all { it.model == "gpt-5.4-mini" })
        assertTrue(guard.calls.all { it.promptVersion == "default" })
    }

    @Test fun propagatesAuthenticationFailureWithoutFallback() = runTest {
        val router = LlmRouter(listOf(Fake(ProviderId.ANTHROPIC) { throw LlmAuthException() }, alternate), guard)
        assertFailsWith<LlmAuthException> { router.complete("generate", request, "ideas", "4") }
        assertEquals(0, alternate.requests)
        assertEquals("4", guard.calls.single().promptVersion)
        assertEquals("auth", guard.calls.single().error)
    }

    @Test fun returnsRefusalWhenItsCostBlocksFallback() = runTest {
        val refusal = result(ProviderId.ANTHROPIC, "claude-haiku-5-5").copy(refused = true)
        val primary = Fake(ProviderId.ANTHROPIC) { refusal }
        val budget = CostGuard(0.01)
        val router = LlmRouter(listOf(primary, alternate), budget)
        assertSame(refusal, router.complete("classify", request, "classify", "default"))
        assertFailsWith<BudgetExceededException> { router.complete("classify", request, "classify", "default") }
        assertEquals(1, primary.requests)
        assertEquals(0, alternate.requests)
        assertEquals(refusal.costUsd, budget.calls.single().costUsd)
    }

    @Test fun budgetBlocksFurtherCallsAndResetsOnNextDay() = runTest {
        var now = Instant.parse("2026-11-19T00:00:00Z")
        val zone = ZoneId.of("UTC")
        val clock = object : Clock() {
            override fun getZone() = zone
            override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
            override fun instant() = now
        }
        val budget = CostGuard(0.01, clock)
        val router = LlmRouter(listOf(alternate), budget)
        assertEquals(0.02, router.complete("test", request, "classify", "default").costUsd)
        assertFailsWith<BudgetExceededException> { router.complete("test", request, "classify", "default") }
        assertEquals(1, alternate.requests)
        assertEquals(0.02, budget.calls.single().costUsd)
        now = now.plusSeconds(86_400)
        assertEquals(0.02, router.complete("test", request, "classify", "default").costUsd)
        assertEquals(2, alternate.requests)
    }

    @Test fun separatesRunRecordsAndAllowsConcurrentCalls() = runTest {
        val bothStarted = CompletableDeferred<Unit>()
        var started = 0
        val provider = Fake(ProviderId.ANTHROPIC) {
            if (++started == 2) bothStarted.complete(Unit)
            bothStarted.await()
            result(ProviderId.ANTHROPIC, it)
        }
        val router = LlmRouter(listOf(provider), guard)
        val first = async { router.complete("classify", request, "classify", "1") }
        val second = async { router.complete("track", request, "track", "1") }
        first.await()
        second.await()
        assertEquals(setOf("classify", "track"), guard.drainCalls().map { it.stage }.toSet())
        router.complete("generate", request, "ideas", "2")
        assertEquals("2", guard.drainCalls().single().promptVersion)
        assertTrue(guard.drainCalls().isEmpty())
    }

    @Test fun appliesFallbackSwitchesAndPropagatesLastFailure() = runTest {
        val first = IllegalStateException("첫 실패")
        val last = IllegalArgumentException("마지막 실패")
        val primary = Fake(ProviderId.ANTHROPIC) { throw first }
        val failingAlternate = Fake(ProviderId.OPENAI) { throw last }
        for (router in listOf(
            LlmRouter(listOf(primary, failingAlternate), guard, fallback = false, stageFallback = mapOf("classify" to true)),
            LlmRouter(listOf(primary, failingAlternate), guard, stageFallback = mapOf("classify" to false))
        )) {
            assertEquals(first.message,
                assertFailsWith<IllegalStateException> { router.complete("classify", request, "classify", "default") }.message)
        }
        assertEquals(0, failingAlternate.requests)
        val router = LlmRouter(listOf(primary, failingAlternate), guard)
        assertEquals(last.message,
            assertFailsWith<IllegalArgumentException> { router.complete("classify", request, "classify", "default") }.message)
        assertEquals(1, failingAlternate.requests)
        val refusal = result(ProviderId.ANTHROPIC, "claude-haiku-5-5").copy(refused = true)
        val solo = LlmRouter(listOf(Fake(ProviderId.ANTHROPIC) { refusal }), guard)
        assertSame(refusal, solo.complete("classify", request, "classify", "default"))
    }

    @Test fun distinguishesMissingKeysAndUsesStageTimeouts() = runTest {
        val primary = Fake(ProviderId.ANTHROPIC) {
            delay(60_001)
            result(ProviderId.ANTHROPIC, it)
        }
        val router = LlmRouter(listOf(primary, alternate), guard,
            stageModels = mapOf("track" to StageModel(ProviderId.OPENAI, "gpt-5.4-mini")))
        assertEquals(ProviderId.ANTHROPIC, router.complete("generate", request, "ideas", "default").provider)
        assertEquals(ProviderId.OPENAI, router.complete("classify", request, "classify", "default").provider)
        assertEquals(ProviderId.OPENAI, router.complete("test", request, "ideas", "default").provider)
        val missing = LlmRouter(listOf(primary), guard,
            stageModels = mapOf("generate" to StageModel(ProviderId.OPENAI, "gpt-5.5")))
        assertEquals("키 미등록: OPENAI_API_KEY",
            assertFailsWith<LlmKeyMissingException> { missing.complete("generate", request, "ideas", "default") }.message)
        assertEquals(LlmCall("generate", ProviderId.OPENAI, "gpt-5.5", "ideas", "default", error = "key_missing"),
            guard.calls.last())
        val configured = LlmRouter(listOf(primary, alternate), guard,
            stageTimeoutMillis = mapOf("generate" to 1))
        assertEquals(ProviderId.OPENAI, configured.complete("generate", request, "ideas", "default").provider)
    }

    private class Fake(override val id: ProviderId, val respond: suspend (String) -> LlmResult) : LlmProvider {
        var requests = 0
        override suspend fun listModels() = emptyList<ModelInfo>()
        override suspend fun complete(req: LlmRequest): LlmResult {
            requests++
            return respond(req.model)
        }
    }

    private fun result(id: ProviderId, model: String) = LlmResult("{}", id, model, 10, 2, 0, 0.02)
}

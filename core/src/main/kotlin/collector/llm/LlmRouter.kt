package collector.llm

import collector.config.PipelineConfig
import collector.model.SupplementRef
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.math.ceil

internal const val supplementCount = 3
internal const val supplementTitleChars = 120
internal const val supplementNoteChars = 300
internal const val supplementUrlChars = 2048
private val supplementInputHeadroom = estimateInputTokens(
    kotlinx.serialization.json.Json.encodeToString(List(supplementCount) {
        // Escaped control characters occupy six JSON characters per input character.
        SupplementRef("\u001f".repeat(supplementUrlChars), "\u001f".repeat(supplementTitleChars), "\u001f".repeat(supplementNoteChars))
    })
).toLong() + 256

internal fun estimateInputTokens(vararg texts: String): Int {
    val ascii = texts.sumOf { text -> text.count { it.code < 128 } }
    val nonAscii = texts.sumOf { it.codePointCount(0, it.length) } - ascii
    return ceil(ascii / 4.0 + nonAscii / 1.5).toInt()
}

internal fun analysisInputTokens(vararg texts: String): Int =
    ceil((texts.sumOf { it.length } / 2.0 + 1000) * 1.2).toInt()

internal fun ModelCatalog.analysisCostUsd(model: StageModel, input: Int, output: Int): Double =
    costUsd(model.provider, model.model, input, output, 0, useLongContextPrice = input >= 217_000)

@Serializable
data class StageModel(val provider: ProviderId, val model: String)

class LlmRouter(
    providers: Collection<LlmProvider>,
    private val costGuard: CostGuard,
    private val stageModels: Map<String, StageModel> = emptyMap(),
    private val fallback: Boolean = true,
    private val catalog: ModelCatalog = ModelCatalog.defaults,
    private val timeoutMillis: Long? = null,
    private val stageFallback: Map<String, Boolean> = emptyMap(),
    private val stageTimeoutMillis: Map<String, Long> = emptyMap(),
    private val preflightBudget: Boolean = false
) {
    private val providers = providers.associateBy { it.id }

    private fun primary(stage: String, promptId: String): StageModel {
        val tier = when (stage) {
            "classify", "track" -> ModelTier.FAST
            "generate", "analysis" -> ModelTier.STRONG
            "test" -> if (promptId == "ideas" || promptId == "criteria") ModelTier.STRONG else ModelTier.FAST
            else -> throw IllegalArgumentException("알 수 없는 LLM 단계입니다.")
        }
        val preferred = if (ProviderId.ANTHROPIC in providers) ProviderId.ANTHROPIC else ProviderId.OPENAI
        return stageModels[stage] ?: if (stage == "analysis") StageModel(ProviderId.OPENAI, "gpt-6.2-sol")
            else checkNotNull(catalog.defaultModel(preferred, tier))
    }

    internal fun startRun(startedAt: Instant, deadlineMinutes: Int, now: Instant = Instant.now()) {
        val left = Duration.between(now, startedAt.plusSeconds(deadlineMinutes * 60L)).toMillis()
        deadlineNanos = System.nanoTime() + left.coerceAtLeast(0) * 1_000_000
    }

    private var deadlineNanos: Long? = null
    private val recoveredBatches = mutableMapOf<File, BatchOutcome>()
    private val appliedBatches = mutableSetOf<File>()

    internal fun markAppliedBatchesConsumed() {
        for (path in appliedBatches) markBatchConsumed(path)
        appliedBatches.clear()
    }

    internal fun remainingMillis(): Long = deadlineNanos?.let {
        ((it - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
    } ?: Long.MAX_VALUE

    internal fun pollRemainingMillis(): Long = (remainingMillis() - 300_000L).coerceAtLeast(0)

    internal suspend fun recoverBatches(out: File, config: PipelineConfig.Batch, recentTopicDays: Int = 14,
                                       keep: (File) -> Boolean): List<String> {
        val issues = mutableListOf<String>()
        val directory = out.resolve("batch")
        val stopAt = System.nanoTime() + Duration.ofMinutes(config.recoveryMinutes.toLong()).toNanos()
        fun recoveryLeft() = ((stopAt - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
        recoverQuarantined(directory, costGuard, issues)
        val files = directory.listFiles().orEmpty().filter { it.isFile && it.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}-(classify|generate|backfill)\\.json")) }
            .sortedWith(compareBy<File> { !keep(it) }.thenBy { it.name })
        for (path in files) {
            val outcome = try {
                runMessageBatch(providers[ProviderId.ANTHROPIC] as? AnthropicProvider, costGuard, null,
                    path, config, emptyList(), emptyList(), null, emptyList(), ::remainingMillis,
                    pollRemainingMillis = ::pollRemainingMillis,
                    recoveryRemainingMillis = { minOf(remainingMillis(), recoveryLeft()) }, recovering = true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                BatchOutcome(issues = listOf("Batch recovery failed: " + path.name), pending = true)
            }
            recoveredBatches[path] = outcome
            issues += outcome.issues
        }
        if (recoveryLeft() == 0L) issues += "Batch recovery deadline"
        try {
            if (recoveryLeft() > 0) cleanBatchFiles(directory, recentTopicDays)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            issues += "Batch retention cleanup failed"
        }
        return issues
    }

    internal suspend fun batch(stage: String, runDate: String, out: File, config: PipelineConfig.Batch,
                               requests: List<LlmRequest>, promptVersions: Map<String, String>,
                               generationResume: GenerationBatchResume? = null,
                               itemKeys: List<List<String>> = emptyList()): BatchOutcome {
        val path = batchPath(out, runDate, stage)
        val recovered = recoveredBatches[path]
        if (recovered?.pending == true) return recovered
        if (hasSavedBatch(out, runDate, stage)) {
            val provider = providers[ProviderId.ANTHROPIC] as? AnthropicProvider
            val outcome = runMessageBatch(provider, costGuard, null, path, config, requests, emptyList(),
                generationResume, itemKeys, ::remainingMillis, retrySettlement = recovered == null)
            if (!outcome.pending && path.isFile && !isPendingBatch(path)) appliedBatches += path
            return if (recovered == null) outcome else outcome.copy(
                issues = (recovered.issues + outcome.issues).distinct(), waitMillis = recovered.waitMillis + outcome.waitMillis)
        }
        if (recovered != null) return recovered
        if (requests.isEmpty()) return BatchOutcome()
        val promptId = promptVersions.keys.first()
        val model = primary(if (stage == "backfill") "classify" else stage, promptId)
        if (model.provider == ProviderId.OPENAI) return BatchOutcome(issues = listOf("Batch 미지원 공급자 · 동기 실행"))
        val provider = providers[model.provider] as? AnthropicProvider ?: return BatchOutcome()
        val price = catalog.requirePrice(model.provider, model.model)
        val call = LlmCall(stage, model.provider, model.model, promptId, promptVersions.getValue(promptId),
            promptVersions = promptVersions, batch = true, priceUnverified = !price.priceVerified)
        val routed = requests.map { it.copy(model = model.model) }
        val outcome = runMessageBatch(provider, costGuard, call, path, config, routed,
            routed.map { projectedTokens(it, model) * price.batchMultiplier }, generationResume, itemKeys, ::remainingMillis)
        if (!outcome.pending && path.isFile && !isPendingBatch(path)) appliedBatches += path
        return outcome
    }

    internal suspend fun supplementSearch(
        user: String, config: PipelineConfig.Search, promptVersion: String = "default",
        generationRequest: LlmRequest? = null
    ): List<SupplementRef> {
        if (!config.enabled || config.maxUses <= 0) return emptyList()
        val model = primary("classify", "classify")
        val provider = providers[model.provider] as? AnthropicProvider ?: return emptyList()
        val request = LlmRequest(model.model,
            "주어진 주제와 관련된 기술, 사용자 요청, 제품을 웹에서 검색한다. 검색 결과의 URL과 제목, " +
                "관련성을 한 줄로 설명한 note를 {url, title, note} 객체 배열로 반환한다.",
            user, kotlinx.serialization.json.JsonObject(emptyMap()), "supplement-search", 1024, Effort.LOW)
        val price = catalog.requirePrice(model.provider, model.model)
        val call = LlmCall("supplement-search", model.provider, model.model, "ideas", promptVersion)
        val generationHeadroom = if (generationRequest != null)
            projectedTokens(generationRequest, primary("generate", "ideas"), extraInput = supplementInputHeadroom) else 0.0
        val timeout = minOf(stageTimeoutMillis["classify"] ?: timeoutMillis ?: 60_000, remainingMillis())
        if (timeout <= 0) throw LlmServiceException(reason = "timeout")
        val reservation = costGuard.reserve(projectedTokens(request, model, extraInput = config.maxUses * 8192L) +
            price.webSearchUsd * config.maxUses, headroomUsd = generationHeadroom)
        var settlement = call.copy(costUsd = reservation.amount, error = "provider_error",
            priceUnverified = !price.priceVerified || !price.webSearchPriceVerified)
        try {
            val result = withTimeoutOrNull(timeout) { provider.search(request, config) }
                ?: throw LlmServiceException(reason = "timeout")
            val responsePrice = catalog.find(model.provider, result.model) ?: price
            settlement = call.copy(model = result.model, inputTokens = result.usage.inputTokens,
                outputTokens = result.usage.outputTokens, cachedInputTokens = result.usage.cachedInputTokens,
                cacheWriteTokens = result.usage.cacheWriteTokens, webSearchRequests = result.requests,
                costUsd = catalog.costUsd(responsePrice, result.usage) + responsePrice.webSearchUsd * result.requests,
                priceFallback = catalog.find(model.provider, result.model) == null,
                priceUnverified = !responsePrice.priceVerified || result.requests > 0 && !responsePrice.webSearchPriceVerified,
                error = result.error)
            if (result.error != null) throw LlmServiceException(reason = result.error)
            return result.refs
        } catch (e: Exception) {
            settlement = settlement.copy(error = (e as? LlmServiceException)?.reason ?: when (e) {
                is LlmAuthException -> "auth"
                is CancellationException -> "cancelled"
                else -> "provider_error"
            })
            if (e is LlmAuthException) settlement = settlement.copy(costUsd = 0.0, priceUnverified = false)
            throw e
        } finally {
            costGuard.record(settlement, reservation)
        }
    }

    suspend fun complete(stage: String, request: LlmRequest, promptId: String, promptVersion: String,
                         promptVersions: Map<String, String> = emptyMap(), fallbackOnInvalidJson: Boolean = true,
                         allowFallback: Boolean = true, onAttempt: () -> Unit = {}): LlmResult {
        val primary = primary(stage, promptId)
        require(stage != "analysis" || primary.provider == ProviderId.OPENAI)
        val call = LlmCall(stage, primary.provider, primary.model, promptId, promptVersion, promptVersions = promptVersions)
        if (primary.provider !in providers) {
            costGuard.record(call.copy(error = "key_missing"))
            throw LlmKeyMissingException(primary.provider)
        }
        val tier = catalog.requirePrice(primary.provider, primary.model).tier
        val alternate = providers.keys.firstOrNull { it != primary.provider }
            ?.takeIf { stage != "analysis" && allowFallback && fallback && stageFallback[stage] != false }
            ?.let { catalog.defaultModel(it, tier) }

        val first = try {
            attempt(request, call, onAttempt)
        } catch (e: LlmAuthException) {
            throw e
        } catch (e: LlmKeyMissingException) {
            throw e
        } catch (e: LlmConfigException) {
            throw e
        } catch (e: BudgetExceededException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (alternate == null || !fallbackOnInvalidJson && e is LlmServiceException &&
                e.reason in setOf("invalid_json", "truncated")) throw e
            null
        }
        if (first != null && (!first.refused || alternate == null)) return first
        checkNotNull(alternate)
        return attempt(request, call.copy(provider = alternate.provider, model = alternate.model, fallbackUsed = true), onAttempt)
    }

    private fun projectedTokens(request: LlmRequest, model: StageModel, extraInput: Long = 0): Double {
        val price = catalog.requirePrice(model.provider, model.model)
        val input = estimateInputTokens(request.system, request.user, request.jsonSchema.toString()) + 1024L + extraInput
        val long = price.longContext?.takeIf { input > it.thresholdInputTokens }
        val inputRate = long?.input ?: price.inputUsd
        val maxInputRate = if (model.provider == ProviderId.ANTHROPIC)
            maxOf(inputRate, price.cacheWriteUsd ?: inputRate * 1.25) else inputRate
        val outputRate = long?.output ?: price.outputUsd
        return (input * maxInputRate + request.maxOutputTokens * outputRate) / 1_000_000
    }

    private suspend fun attempt(request: LlmRequest, call: LlmCall, onAttempt: () -> Unit): LlmResult {
        val timeout = minOf(remainingMillis(),
            stageTimeoutMillis[call.stage] ?: timeoutMillis ?: if (call.stage in setOf("generate", "analysis")) 600_000 else 60_000)
        if (timeout <= 0) throw LlmServiceException(reason = "timeout")
        val projected = if (call.stage == "analysis" && preflightBudget) {
            catalog.analysisCostUsd(StageModel(call.provider, call.model),
                analysisInputTokens(request.system, request.user, request.jsonSchema.toString()), request.maxOutputTokens)
        } else if (preflightBudget) projectedTokens(request, StageModel(call.provider, call.model)) else 0.0
        val reservation = costGuard.reserve(projected)
        var providerStarted = false
        var settlement = call.copy(costUsd = reservation.amount, error = "provider_error",
            priceUnverified = catalog.find(call.provider, call.model)?.priceVerified == false)
        try {
            onAttempt()
            val result = withTimeoutOrNull(timeout) {
                providerStarted = true
                providers.getValue(call.provider).complete(request.copy(model = call.model))
            } ?: throw LlmServiceException(reason = "timeout")
            settlement = call.copy(
                model = result.model,
                inputTokens = result.inputTokens,
                outputTokens = result.outputTokens,
                cachedInputTokens = result.cachedInputTokens,
                cacheWriteTokens = result.cacheWriteTokens,
                costUsd = result.costUsd,
                error = if (result.refused) "refused" else null,
                priceFallback = result.priceFallback,
                priceUnverified = result.priceUnverified
            )
            return result
        } catch (e: Exception) {
            val error = when (e) {
                is LlmAuthException -> "auth"
                is CancellationException -> "cancelled"
                is LlmServiceException -> e.reason
                else -> "provider_error"
            }
            val failure = e as? LlmServiceException
            val usage = failure?.usage
            val rejected = !providerStarted || e is LlmAuthException || e is LlmConfigException || failure?.reason == "http_4xx"
            settlement = settlement.copy(
                    model = failure?.model ?: call.model,
                    inputTokens = usage?.inputTokens ?: 0,
                    outputTokens = usage?.outputTokens ?: 0,
                    cachedInputTokens = usage?.cachedInputTokens ?: 0,
                    cacheWriteTokens = usage?.cacheWriteTokens ?: 0,
                    costUsd = if (usage != null) checkNotNull(failure).costUsd else if (rejected) 0.0 else reservation.amount,
                    error = error,
                    priceFallback = failure?.priceFallback ?: false,
                    priceUnverified = if (usage != null) checkNotNull(failure).priceUnverified else
                        !rejected && settlement.priceUnverified
                )
            throw e
        } finally {
            costGuard.record(settlement, reservation)
        }
    }
}


package collector.runtime

import collector.config.ConfigLoader
import collector.config.HfConfig
import collector.config.SourcesFile
import collector.dev.FileCostLedger
import collector.llm.AnthropicProvider
import collector.llm.BatchMockServer
import collector.llm.isPendingBatch
import collector.llm.LlmResult
import collector.llm.ProviderId
import collector.mail.Mailer
import collector.model.Item
import collector.port.SecretProvider
import collector.source.SourceAdapter
import collector.config.SourceConfig
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollectorRuntimeTest {
    @TempDir lateinit var root: File
    private val clock = Clock.fixed(Instant.parse("2026-11-22T07:00:00Z"), ZoneOffset.UTC)
    private val day = "2026-11-22"
    private val source = object : SourceAdapter {
        override val id = "hf"
        override suspend fun fetch(config: SourceConfig, now: Instant) =
            listOf(Item(id, "1", "Model for testing", "https://example.invalid/model", 10, publishedAt = now))
    }
    private val classified = """{"items":[{"itemId":"hf:1","profile":"none","score":null,"reason":"","summary":"","tags":[],"topicKey":"","topicLabel":""}]}"""

    private fun paths(): CollectorPaths {
        val paths = CollectorPaths(root.resolve("out"), root.resolve("config"))
        assertTrue(paths.configDir.mkdirs())
        paths.sourcesConfig.writeText(ConfigLoader.json.encodeToString(SourcesFile(listOf(HfConfig()))))
        paths.pipelineConfig.writeText("""{"batch":{"classify":true,"generate":true}}""")
        return paths
    }

    private fun snapshot(directory: File) = directory.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(directory).invariantSeparatorsPath to it.readBytes().contentHashCode() }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun scheduledUsesInjectedPathsAndChecksCancellationBeforeMail(cancelMail: Boolean) = runBlocking {
        val paths = paths()
        val working = CollectorPaths.workingDirectory()
        val marker = File.createTempFile("r1-cwd-", ".marker", File("."))
        try {
            marker.writeText("working directory marker")
            val before = listOf(snapshot(working.outDir), snapshot(working.configDir))
            val stages = mutableListOf<RunStage>()
            val mail = mutableListOf<String>()
            MockWebServer().use { server ->
                server.dispatcher = QueueDispatcher().apply { setFailFast(true) }
                server.enqueue(MockResponse().addHeader("Content-Type", "application/json").setBody(
                    message()
                ))
                val runtime = CollectorRuntime(paths, SecretProvider { null }, clock, listOf(source),
                    { listOf(AnthropicProvider("mock", server.url("/").toString())) },
                    { Mailer { mail += it.subject } })
                val job = launch {
                    val context = coroutineContext
                    val result = runtime.runScheduled(ScheduledOptions(batchOverride = false, onStage = {
                        stages += it
                        if (cancelMail && it == RunStage.MAIL) context.cancel()
                    }))
                    assertEquals(0, result.exitCode)
                }
                job.join()
                assertEquals(cancelMail, job.isCancelled)
                assertEquals(listOf(RunStage.COLLECT, RunStage.CLASSIFY, RunStage.GENERATE, RunStage.MAIL), stages)
                assertEquals(1, server.requestCount)
                assertEquals("/v1/messages", server.takeRequest().path)
            }
            assertTrue(paths.outDir.resolve("logs/$day.log").isFile)
            assertTrue(paths.outDir.resolve("trend/$day.json").isFile)
            assertEquals(if (cancelMail) 0 else 2, mail.size)
            assertEquals(!cancelMail, paths.outDir.resolve("mail/sent.json").exists())
            assertEquals("working directory marker", marker.readText())
            assertEquals(before, listOf(snapshot(working.outDir), snapshot(working.configDir)))
        } finally {
            marker.delete()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun cancellationPreservesBatchCheckpointAndSettlesReservationOnRetry(batchDisabled: Boolean) = runBlocking {
        val paths = paths()
        val checkpoint = paths.outDir.resolve("batch/$day-classify.json")
        val ledger = FileCostLedger(paths.outDir.resolve("cost-ledger"))
        fun batch(status: String) = MockResponse().addHeader("Content-Type", "application/json")
            .setBody("""{"id":"batch_mock","processing_status":"$status"}""")
        BatchMockServer().use { server ->
            server.enqueue(batch("in_progress"))
            val runtime = CollectorRuntime(paths, SecretProvider { null }, clock, listOf(source),
                { listOf(AnthropicProvider("mock", server.url("/").toString())) })
            val options = ScheduledOptions(mail = false)
            val first = launch { runtime.runScheduled(options) }
            withTimeout(10_000) {
                while (!checkpoint.isFile || "batch_mock" !in checkpoint.readText()) delay(10)
            }
            first.cancelAndJoin()
            assertTrue(isPendingBatch(checkpoint))
            val day = java.time.LocalDate.now(clock)
            val reserved = ledger.total(day)
            assertTrue(reserved > 0.0)
            assertTrue(!paths.outDir.resolve("mail/scheduled.json").exists())

            val submitted = server.takeRequest()
            val customId = Json.parseToJsonElement(submitted.body.readUtf8()).jsonObject
                .getValue("requests").jsonArray.single().jsonObject.getValue("custom_id")
            assertEquals("/v1/messages/batches", submitted.path)
            assertTrue(!paths.outDir.resolve("runs/failed").exists())
            if (batchDisabled) {
                val result = LlmResult(classified, ProviderId.ANTHROPIC, "claude-haiku-5-5", 10, 4, 0, 0.0000015)
                checkpoint.resolveSibling("batch_mock.results.jsonl").writeText(
                    buildJsonObject {
                        put("customId", customId)
                        put("result", Json.encodeToJsonElement(LlmResult.serializer(), result))
                    }.toString() + "\n"
                )
            } else {
                server.enqueue(batch("ended"))
                server.enqueue(MockResponse().setBody(
                    """{"custom_id":$customId,"result":{"type":"succeeded","message":${
                        message()
                    }}}""" + "\n"
                ))
            }
            val stages = mutableListOf<RunStage>()
            assertEquals(0, runtime.runScheduled(options.copy(batchOverride = if (batchDisabled) false else null,
                onStage = stages::add)).exitCode)
            assertEquals(RunStage.RECOVER, stages.first())
            assertEquals(if (batchDisabled) 1 else 3, server.requestCount)
            if (!batchDisabled) assertEquals(listOf("/v1/messages/batches/batch_mock",
                "/v1/messages/batches/batch_mock/results"), List(2) { server.takeRequest().path })
            assertTrue(!isPendingBatch(checkpoint))
            assertTrue(ledger.batchReservations(checkpoint.name).values.all { it.isEmpty() })
            assertEquals(0.0000015, ledger.total(day), 1e-12)
            assertTrue(ledger.total(day) < reserved)
            assertTrue(paths.outDir.resolve("mail/scheduled.json").isFile)
        }
    }

    private fun message() =
        """{"id":"msg_mock","type":"message","role":"assistant","model":"claude-haiku-5-5","content":[{"type":"text","text":${kotlinx.serialization.json.JsonPrimitive(classified)}}],"stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":4}}"""
}

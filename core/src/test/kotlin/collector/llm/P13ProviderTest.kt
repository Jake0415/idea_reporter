package collector.llm

import collector.config.PipelineConfig
import collector.dev.FileCostLedger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class P13ProviderTest {
    @TempDir lateinit var out: File
    private val model = "claude-haiku-5-5"
    private val request = LlmRequest(model, "system", "user", buildJsonObject { put("type", "object") }, "classify", 128)
    private fun response(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun batch(status: String) = response("""{"id":"batch_1","processing_status":"$status"}""")
    private fun message(content: String = """[{"type":"text","text":"{}"}]""", usage: String = "") =
        """{"id":"msg_1","type":"message","role":"assistant","model":"$model","content":$content,"stop_reason":"end_turn",
            "stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":4,"cache_read_input_tokens":20,"cache_creation_input_tokens":30$usage}}""".replace("\n", "")
    private fun row(index: Int, result: String) = """{"custom_id":"classify-2026-11-23-$index","result":$result}"""
    private val success get() = """{"type":"succeeded","message":${message()}}"""
    private val errored = """{"type":"errored","error":{"type":"error","error":{"type":"invalid_request_error","message":"mock"}}}"""

    @Test fun batchMatchesUnorderedResultsAndDiscountsAllTokens() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val costs = CostGuard(1.0)
                val router = LlmRouter(listOf(provider), costs)
                server.enqueue(batch("in_progress"))
                server.enqueue(batch("ended"))
                server.enqueue(response(row(2, success) + "\n" + row(1, success) + "\n"))
                val results = router.batch("classify", "2026-11-23", out, PipelineConfig.Batch(pollSeconds = 1),
                    listOf(request, request), mapOf("classify" to "default"))
                assertEquals(listOf("{}", "{}"), results.responses.values.map { it.getOrThrow().json })
                assertEquals(2, costs.calls.size)
                assertTrue(costs.calls.all { it.batch })
                assertEquals(0.00000695, costs.calls.sumOf { it.costUsd }, 1e-12)
                val submitted = server.takeRequest()
                assertEquals("/v1/messages/batches", submitted.path)
                val requests = Json.parseToJsonElement(submitted.body.readUtf8()).jsonObject["requests"]!!.jsonArray
                assertTrue(requests[0].jsonObject.getValue("custom_id").jsonPrimitive.content.matches(Regex("classify-2026-11-23-[0-9a-f]{8}-1")))
                assertEquals("/v1/messages/batches/batch_1", server.takeRequest().path)
                assertEquals("/v1/messages/batches/batch_1/results", server.takeRequest().path)
            }
        }
    }

    @Test fun erroredRequestIsAvailableForSynchronousFallback() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val router = LlmRouter(listOf(provider), CostGuard(1.0))
                server.enqueue(batch("ended"))
                server.enqueue(response(row(1, errored) + "\n" + row(2, success) + "\n"))
                val result = router.batch("classify", "2026-11-23", out, PipelineConfig.Batch(),
                    listOf(request, request), mapOf("classify" to "default"))
                assertEquals(setOf(1), result.responses.keys)
                assertTrue(result.issues.any { "동기 실행" in it })
            }
        }
    }

    @Test fun resumesPendingBatchWithNoSecondSubmission() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val ledger = FileCostLedger(out.resolve("cost"))
                val failedCosts = CostGuard(1.0, ledger = ledger)
                val router = LlmRouter(listOf(provider), failedCosts)
                server.enqueue(batch("in_progress"))
                server.enqueue(response("{}").setResponseCode(503))
                val versions = mapOf("classify" to "default")
                val config = PipelineConfig.Batch(pollSeconds = 1)
                val first = router.batch("classify", "2026-11-23", out, config, listOf(request), versions)
                assertTrue(first.issues.single().contains("다음 실행"))
                assertTrue(out.resolve("batch/2026-11-23-classify.json").isFile)
                val costs = CostGuard(1.0, ledger = ledger)
                server.enqueue(batch("in_progress"))
                server.enqueue(batch("ended"))
                server.enqueue(response(row(1, success) + "\n"))
                val resumed = LlmRouter(listOf(provider), costs).batch("classify", "2026-11-23", out, config,
                    listOf(request), versions)
                assertEquals(listOf("Batch 재개"), resumed.issues)
                assertEquals("{}", resumed.responses[0]?.getOrThrow()?.json)
                assertEquals(0.000003475, ledger.total(java.time.LocalDate.now()), 1e-12)
                val pointer = batchPath(out, "2026-11-23", "classify")
                assertTrue(pointer.isFile)
                assertFalse(isPendingBatch(pointer))
                markBatchConsumed(pointer)
                assertFalse(pointer.exists())
                assertEquals(listOf("POST", "GET", "GET", "GET", "GET"),
                    List(5) { server.takeRequest().method })
            }
        }
    }

    @Test fun timeoutCancelsAndKeepsSuccessfulResults() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val router = LlmRouter(listOf(provider), CostGuard(1.0))
                server.enqueue(batch("in_progress"))
                server.enqueue(batch("canceling"))
                server.enqueue(batch("ended"))
                server.enqueue(response(row(1, success) + "\n" + row(2, """{"type":"canceled"}""") + "\n"))
                val result = router.batch("classify", "2026-11-23", out, PipelineConfig.Batch(pollSeconds = 1, maxWaitMinutes = 0),
                    listOf(request, request), mapOf("classify" to "default"))
                val paths = List(server.requestCount) { server.takeRequest().path }
                assertEquals(setOf(0), result.responses.keys, "${result.issues} $paths")
                assertEquals(listOf("Batch 시간 초과 · 1묶음 동기 실행"), result.issues)
                assertEquals("/v1/messages/batches/batch_1/cancel", paths[1])
            }
        }
    }

    @Test fun submissionFailureReleasesReservationsForSyncFallback() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val costs = CostGuard(1.0)
                val router = LlmRouter(listOf(provider), costs, preflightBudget = true)
                server.enqueue(response("{}").setResponseCode(400))
                val result = router.batch("classify", "2026-11-23", out, PipelineConfig.Batch(),
                    listOf(request), mapOf("classify" to "default"))
                assertTrue(result.synchronousFallback)
                assertTrue(result.responses.isEmpty())
                assertEquals(0.0, costs.calls.single().reservedUsd)
                assertFalse(out.resolve("batch/2026-11-23-classify.json").exists())
                server.enqueue(response(message()))
                router.complete("classify", request, "classify", "default")
                assertFalse(costs.calls.last().batch)
                assertEquals("/v1/messages", List(2) { server.takeRequest().path }.last())
            }
        }
    }

    @Test fun transientSubmissionErrorsKeepTheIntent() = runBlocking {
        for (status in listOf(429, 503)) {
            BatchMockServer().use { server ->
                AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                    val directory = out.resolve(status.toString())
                    val router = LlmRouter(listOf(provider), CostGuard(1.0))
                    server.enqueue(response("{}").setResponseCode(status))
                    val outcome = router.batch("classify", "2026-11-23", directory, PipelineConfig.Batch(),
                        listOf(request), mapOf("classify" to "default"))
                    assertTrue(outcome.pending)
                    assertFalse(outcome.synchronousFallback)
                    assertTrue(batchPath(directory, "2026-11-23", "classify").isFile)
                    assertEquals(1, server.requestCount)
                    if (status == 503) {
                        val created = java.time.Instant.now().plusSeconds(1)
                        server.enqueue(response("""{"data":[{"id":"batch_1","created_at":"$created","processing_status":"ended"}],
                            "has_more":false,"first_id":"batch_1","last_id":"batch_1"}"""))
                        server.enqueue(response(row(1, success) + "\n"))
                        val restarted = LlmRouter(listOf(provider), CostGuard(1.0))
                        assertEquals("{}", restarted.batch("classify", "2026-11-23", directory,
                            PipelineConfig.Batch(), listOf(request), mapOf("classify" to "default"))
                            .responses.getValue(0).getOrThrow().json)
                        assertEquals(listOf("POST", "GET", "GET"), List(3) { server.takeRequest().method })
                    }
                }
            }
        }
    }

    @Test fun lostSubmitIsAdoptedAndReusedWithoutAnotherSubmission() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val date = "2026-11-23"
                val versions = mapOf("classify" to "default")
                val config = PipelineConfig.Batch(pollSeconds = 1)
                val ledger = FileCostLedger(out.resolve("cost"))
                val first = LlmRouter(listOf(provider), CostGuard(1.0, ledger = ledger))
                server.enqueue(response("{}").setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
                assertTrue(first.batch("classify", date, out, config, listOf(request, request), versions).pending)
                val estimated = Json.parseToJsonElement(out.resolve("cost/${java.time.LocalDate.now()}.json").readText())
                    .jsonObject.values.first().jsonObject
                assertEquals(estimated["reservedUsd"], estimated["actualUsd"])
                val created = java.time.Instant.now().plusSeconds(1)
                server.enqueue(response("""{"data":[{"id":"other_batch","created_at":"$created","processing_status":"ended"},
                    {"id":"batch_1","created_at":"$created",
                    "processing_status":"ended","request_counts":{"processing":0,"succeeded":1,"errored":0,"canceled":0,"expired":0}}],
                    "has_more":false,"first_id":"batch_1","last_id":"batch_1"}"""))
                server.enqueue(response(row(1, success).replace("classify-2026-11-23-1", "classify-2026-11-23-deadbeef-1") + "\n"))
                server.enqueue(response(row(1, success) + "\n"))
                server.enqueue(response(row(1, success) + "\n"))
                val restarted = LlmRouter(listOf(provider), CostGuard(1.0, ledger = ledger))
                assertEquals("{}", restarted.batch("classify", date, out, config, listOf(request), versions)
                    .responses.getValue(0).getOrThrow().json)
                assertEquals(5, server.requestCount)
                // Simulate interruption before the caller saves a report.
                val batchDir = out.resolve("batch")
                val resultFile = batchDir.resolve("batch_1.results.jsonl")
                assertTrue(resultFile.setLastModified(1_000L))
                cleanBatchFiles(batchDir, 14)
                assertTrue(resultFile.isFile)
                assertFalse(batchDir.resolve("$date-classify.done").isFile)
                val recovered = LlmRouter(emptyList(), CostGuard(1.0, ledger = ledger))
                assertFalse(recovered.batch("classify", date, out, config, listOf(request), versions).pending)
                assertEquals(5, server.requestCount)
                assertEquals(listOf("/v1/messages/batches", "/v1/messages/batches", "/v1/messages/batches/other_batch/results",
                    "/v1/messages/batches/batch_1/results", "/v1/messages/batches/batch_1/results"),
                    List(5) { server.takeRequest().path })
                assertTrue(ledger.total(java.time.LocalDate.now()) > 0.000003475)
            }
        }
    }

    @Test fun cannotSubmitWithoutDurableIntent() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val costs = CostGuard(1.0)
                val router = LlmRouter(listOf(provider), costs)
                out.resolve("batch/2026-11-23-classify.json").mkdirs()
                val result = router.batch("classify", "2026-11-23", out, PipelineConfig.Batch(),
                    listOf(request), mapOf("classify" to "default"))
                assertTrue(result.synchronousFallback)
                assertEquals(0, server.requestCount)
                assertTrue(costs.calls.isEmpty())
            }
        }
    }

    @Test fun matchingInProgressCandidateIsAdoptedAndPolled() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val date = "2026-11-23"
                val versions = mapOf("classify" to "default")
                val config = PipelineConfig.Batch(pollSeconds = 1)
                val router = LlmRouter(listOf(provider), CostGuard(1.0))
                server.enqueue(response("{}").setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
                assertTrue(router.batch("classify", date, out, config, listOf(request), versions).pending)
                val created = java.time.Instant.now().minusSeconds(120)
                server.enqueue(response("""{"data":[{"id":"batch_1","created_at":"$created",
                    "processing_status":"in_progress"}],"has_more":false}"""))
                server.enqueue(response(row(1, success) + "\n"))
                server.enqueue(batch("ended"))
                server.enqueue(response(row(1, success) + "\n"))
                val result = router.batch("classify", date, out, config, listOf(request), versions)
                assertEquals("{}", result.responses.getValue(0).getOrThrow().json)
                assertEquals(listOf("/v1/messages/batches", "/v1/messages/batches", "/v1/messages/batches/batch_1/results",
                    "/v1/messages/batches/batch_1", "/v1/messages/batches/batch_1/results"), List(5) { server.takeRequest().path })
            }
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test fun invalidLocalBatchParametersFallBackWithoutSubmitting() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val router = LlmRouter(listOf(provider), CostGuard(1.0))
                val invalid = request.copy(jsonSchema = buildJsonObject {
                    put("value", JsonUnquotedLiteral("NaN"))
                })
                val result = router.batch("classify", "2026-11-23", out, PipelineConfig.Batch(),
                    listOf(invalid), mapOf("classify" to "default"))
                assertTrue(result.synchronousFallback)
                assertFalse(batchPath(out, "2026-11-23", "classify").exists())
                assertEquals(0, server.requestCount)
                server.enqueue(response(message()))
                assertEquals("{}", router.complete("classify", request, "classify", "default").json)
                assertEquals("/v1/messages", server.takeRequest().path)
            }
        }
    }

    @Test fun brokenCheckpointIsQuarantinedAndDoesNotBlockClassification() = runBlocking {
        val path = batchPath(out, "2026-11-23", "classify")
        path.parentFile.mkdirs()
        path.writeText("{")
        val router = LlmRouter(emptyList(), CostGuard(1.0))
        assertTrue(router.recoverBatches(out, PipelineConfig.Batch()) { true }.any { "quarantine" in it })
        assertFalse(path.exists())
        assertEquals("{", path.resolveSibling("quarantine").listFiles()!!.single().readText())
        assertFalse(router.batch("classify", "2026-11-23", out, PipelineConfig.Batch(),
            listOf(request), mapOf("classify" to "default")).pending)
    }

    @Test fun twoRunsWithNotFoundQuarantineTheBatch() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val config = PipelineConfig.Batch(pollSeconds = 1)
                val ledger = FileCostLedger(out.resolve("cost"))
                fun router() = LlmRouter(listOf(provider), CostGuard(1.0, ledger = ledger))
                val day = "2026-11-23"
                val path = batchPath(out, day, "classify")
                server.enqueue(batch("in_progress"))
                server.enqueue(response("{}").setResponseCode(503))
                assertTrue(router().batch("classify", day, out, config, listOf(request),
                    mapOf("classify" to "default")).pending)
                repeat(2) { run ->
                    server.enqueue(response("{}").setResponseCode(404))
                    val issues = router().recoverBatches(out, config) { true }
                    assertEquals(run == 0, path.isFile)
                    assertEquals(run == 1, issues.any { "consecutive 404" in it })
                }
                assertTrue(ledger.total(java.time.LocalDate.now()) > 0)
            }
        }
    }

    @Test fun missingProviderKeyWaitsThenQuarantines() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val config = PipelineConfig.Batch(pollSeconds = 1)
                val ledger = FileCostLedger(out.resolve("cost"))
                val day = "2026-11-23"
                val path = batchPath(out, day, "classify")
                server.enqueue(batch("in_progress"))
                server.enqueue(response("{}").setResponseCode(503))
                assertTrue(LlmRouter(listOf(provider), CostGuard(1.0, ledger = ledger)).batch(
                    "classify", day, out, config, listOf(request), mapOf("classify" to "default")).pending)
                fun offline() = LlmRouter(emptyList(), CostGuard(1.0, ledger = ledger))
                assertTrue(offline().recoverBatches(out, config) { true }.any { "provider key missing" in it })
                val checkpoint = Json.parseToJsonElement(path.readText()).jsonObject
                assertNotNull(checkpoint["firstMissingKeyAt"])
                path.writeText(JsonObject(checkpoint + ("firstMissingKeyAt" to JsonPrimitive("2020-01-01T00:00:00Z"))).toString())
                assertTrue(offline().recoverBatches(out, config) { true }.any { "quarantine" in it })
                assertFalse(path.exists())
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun intentPrecedesReservationAndOnlyExistingReservationIsSettledAfterInterruption() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val path = batchPath(out, "2026-11-23", "classify")
                val ledger = FileCostLedger(out.resolve("cost"))
                var reservations = 0
                val interrupted = object : CostLedger by ledger {
                    override fun reserve(day: java.time.LocalDate, id: String, amount: Double) {
                        assertTrue(path.readText().contains(id))
                        if (++reservations == 2) throw AssertionError("interrupted")
                        ledger.reserve(day, id, amount)
                    }
                }
                val router = LlmRouter(listOf(provider), CostGuard(1.0, ledger = interrupted))
                assertFailsWith<AssertionError> {
                    router.batch("classify", "2026-11-23", out, PipelineConfig.Batch(),
                        listOf(request, request), mapOf("classify" to "default"))
                }
                assertEquals(0, server.requestCount)
                val intent = Json.parseToJsonElement(path.readText()).jsonObject
                path.writeText(JsonObject(intent + ("submittedAt" to JsonPrimitive("2020-01-01T00:00:00Z"))).toString())
                val costs = CostGuard(1.0, ledger = ledger)
                val issues = LlmRouter(emptyList(), costs).recoverBatches(out, PipelineConfig.Batch()) { true }
                assertTrue(issues.any { "intent expired" in it })
                assertEquals(1, costs.calls.size)
                assertEquals(ledger.total(java.time.LocalDate.now()), costs.calls.single().costUsd)
                assertFalse(path.exists())
            }
        }
    }

    @Test fun firstSettlementFailureDoesNotSkipTheNextReservation() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val ledger = FileCostLedger(out.resolve("cost"))
                var attempts = 0
                val faulty = object : CostLedger by ledger {
                    override fun settleBatch(day: java.time.LocalDate, id: String, amount: Double) {
                        if (++attempts != 2) error("simulated ledger failure")
                        ledger.settleBatch(day, id, amount)
                    }
                }
                server.enqueue(batch("ended"))
                server.enqueue(response(row(1, success) + "\n" + row(2, success) + "\n"))
                val outcome = LlmRouter(listOf(provider), CostGuard(1.0, ledger = faulty)).batch(
                    "classify", "2026-11-23", out, PipelineConfig.Batch(), listOf(request, request),
                    mapOf("classify" to "default"))
                assertEquals(2, attempts)
                assertFalse(outcome.pending)
                assertEquals(setOf(0, 1), outcome.responses.keys)
                assertTrue(outcome.issues.any { "settlement failed" in it })
                val path = batchPath(out, "2026-11-23", "classify")
                assertFalse(isPendingBatch(path))
                val checkpoint = Json.parseToJsonElement(path.readText()).jsonObject
                assertNotNull(checkpoint["firstSettleFailureAt"])
                path.writeText(JsonObject(checkpoint + ("firstSettleFailureAt" to JsonPrimitive("2000-01-01T00:00:00Z"))).toString())
                LlmRouter(emptyList(), CostGuard(1.0, ledger = faulty))
                    .recoverBatches(out, PipelineConfig.Batch()) { true }
                assertEquals(3, attempts)
                // Settlement expiry keeps the paid results reusable; only the reservations go to quarantine.
                assertTrue(path.exists())
                assertTrue(Json.parseToJsonElement(path.readText()).jsonObject["settled"].toString() == "true")
                assertTrue(out.resolve("batch/batch_1.results.jsonl").isFile)
                assertTrue(out.resolve("batch/quarantine/batch_1.unsettled.json").isFile)
            }
        }
    }

    @Test fun pollingStopsFiveMinutesBeforeTheProcessDeadline() = runBlocking {
        val now = java.time.Instant.parse("2026-11-23T02:00:00Z")
        val router = LlmRouter(emptyList(), CostGuard(1.0))
        router.startRun(now.minusSeconds(126 * 60), 130, now)
        assertTrue(router.remainingMillis() in 230_000..240_000)
        assertEquals(0, router.pollRemainingMillis())
    }

    @Test fun missingRowsAreRetriedAndChargedAndDuplicatesUseFirstSuccess() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val costs = CostGuard(1.0)
                val router = LlmRouter(listOf(provider), costs)
                server.enqueue(batch("ended"))
                server.enqueue(response(listOf(row(1, errored), row(1, success), row(1, success)).joinToString("\n", postfix = "\n")))
                server.enqueue(response(row(1, success) + "\n"))
                val result = router.batch("classify", "2026-11-23", out, PipelineConfig.Batch(),
                    listOf(request, request), mapOf("classify" to "default"))
                assertEquals(setOf(0), result.responses.keys)
                assertEquals(3, server.requestCount)
                assertTrue(result.issues.any { "중복 ID" in it })
                assertTrue(result.issues.any { "결과 누락" in it })
                assertTrue(costs.calls.single { it.error == "batch_missing" }.let { it.costUsd > 0 && it.priceUnverified })
            }
        }
    }

    @Test fun missingRowRecheckTimeoutKeepsBatchPendingUntilTheNextRun() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val date = "2026-11-23"
                val path = batchPath(out, date, "classify")
                val config = PipelineConfig.Batch(pollSeconds = 1)
                val versions = mapOf("classify" to "default")
                val requests = listOf(request, request)
                val costs = CostGuard(1.0)
                server.enqueue(batch("in_progress"))
                server.enqueue(response("{}").setResponseCode(503))
                assertTrue(LlmRouter(listOf(provider), costs).batch("classify", date, out, config, requests, versions).pending)
                server.enqueue(batch("ended"))
                server.enqueue(response(row(1, success) + "\n"))
                server.enqueue(response(row(2, success) + "\n").setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS))
                val capped = runMessageBatch(provider, costs, null, path, config, requests, emptyList(),
                    null, emptyList(), { 600_000 }, recoveryRemainingMillis = { 500 })
                assertTrue(capped.pending)
                assertFalse(capped.synchronousFallback)
                assertTrue(costs.calls.isEmpty())
                server.enqueue(response(row(2, success) + "\n"))
                val retry = LlmRouter(listOf(provider), costs).batch("classify", date, out, config, requests, versions)
                assertEquals(setOf(0, 1), retry.responses.keys)
                assertEquals(6, server.requestCount)
                assertEquals(0, List(6) { server.takeRequest() }.count { it.path == "/v1/messages" })
            }
        }
    }

    @Test fun startupSettlesAnOlderBatchWithBatchDisabled() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val ledger = FileCostLedger(out.resolve("cost"))
                val config = PipelineConfig.Batch(pollSeconds = 1)
                server.enqueue(batch("in_progress"))
                server.enqueue(response("{}").setResponseCode(503))
                val first = LlmRouter(listOf(provider), CostGuard(1.0, ledger = ledger))
                assertTrue(first.batch("classify", "2026-11-23", out, config, listOf(request),
                    mapOf("classify" to "default")).pending)
                server.enqueue(batch("ended"))
                server.enqueue(response(row(1, success) + "\n"))
                val recovered = LlmRouter(listOf(provider), CostGuard(1.0, ledger = ledger))
                recovered.recoverBatches(out, config.copy(classify = false)) { false }
                assertFalse(out.resolve("batch/2026-11-23-classify.done").isFile)
                assertFalse(isPendingBatch(batchPath(out, "2026-11-23", "classify")))
                assertEquals(0.000003475, ledger.total(java.time.LocalDate.now()), 1e-12)
                val saved = out.resolve("batch/batch_1.results.jsonl")
                saved.writeText("not read during startup")
                recovered.recoverBatches(out, config) { false }
                assertTrue(saved.isFile)
                assertFalse(out.resolve("batch/quarantine").exists())
            }
        }
    }

    @Test fun recoveryPollingCapDoesNotCancelAnInProgressBatch() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val path = batchPath(out, "2026-11-23", "classify")
                val versions = mapOf("classify" to "default")
                val config = PipelineConfig.Batch(pollSeconds = 1)
                val costs = CostGuard(1.0)
                server.enqueue(batch("in_progress"))
                server.enqueue(response("{}").setResponseCode(503))
                LlmRouter(listOf(provider), costs).batch("classify", "2026-11-23", out, config, listOf(request), versions)
                server.enqueue(batch("in_progress"))
                val result = runMessageBatch(provider, costs, null, path, config, emptyList(), emptyList(),
                    null, emptyList(), { 600_000 }, recoveryRemainingMillis = { 200 })
                assertTrue(result.pending)
                assertEquals(3, server.requestCount)
                assertEquals(listOf("POST", "GET", "GET"), List(3) { server.takeRequest().method })
            }
        }
    }

    @Test fun startupIndexesBackfillResultsForReuseBeforeConsumption() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val date = "2026-11-23"
                val versions = mapOf("classify" to "default")
                val config = PipelineConfig.Batch(pollSeconds = 1)
                val ledger = FileCostLedger(out.resolve("cost"))
                fun router() = LlmRouter(listOf(provider), CostGuard(1.0, ledger = ledger))
                server.enqueue(batch("in_progress"))
                server.enqueue(response("{}").setResponseCode(503))
                assertTrue(router().batch("backfill", date, out, config, listOf(request), versions).pending)
                server.enqueue(batch("ended"))
                server.enqueue(response(row(1, success).replace("classify-", "backfill-") + "\n"))
                router().recoverBatches(out, config) { false }
                val pointer = batchPath(out, date, "backfill")
                assertTrue(pointer.delete())
                assertFalse(out.resolve("batch/$date-backfill.done").exists())
                assertTrue(hasSavedBatch(out, date, "backfill"))
                val reused = LlmRouter(emptyList(), CostGuard(1.0, ledger = ledger))
                assertEquals("{}", reused.batch("backfill", date, out, config, listOf(request), versions)
                    .responses.getValue(0).getOrThrow().json)
                assertEquals(4, server.requestCount)
            }
        }
    }

    @Test fun corruptBackfillIndexIsRebuiltWithoutQuarantiningResults() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val date = "2026-11-23"
                val versions = mapOf("classify" to "default")
                val config = PipelineConfig.Batch(pollSeconds = 1)
                val ledger = FileCostLedger(out.resolve("cost"))
                fun router() = LlmRouter(listOf(provider), CostGuard(1.0, ledger = ledger))
                server.enqueue(batch("in_progress"))
                server.enqueue(response("{}").setResponseCode(503))
                assertTrue(router().batch("backfill", date, out, config, listOf(request), versions).pending)
                server.enqueue(batch("ended"))
                server.enqueue(response(row(1, success).replace("classify-", "backfill-") + "\n"))
                router().recoverBatches(out, config) { false }
                val pointer = batchPath(out, date, "backfill")
                val index = out.resolve("batch/index.json")
                index.writeText("{")
                val issues = router().recoverBatches(out, config) { true }
                assertTrue(pointer.isFile)
                assertTrue(out.resolve("batch/batch_1.results.jsonl").isFile)
                assertFalse(issues.any { "quarantine" in it })
                assertTrue(index.parentFile.listFiles().orEmpty().any { it.name.startsWith("index.json.corrupt-") })
                assertTrue(pointer.delete())
                assertFalse(out.resolve("batch/$date-backfill.done").exists())
                assertTrue(hasSavedBatch(out, date, "backfill"))
                // The index is now the only pointer; corrupting it must still not lose the paid result.
                index.writeText("{")
                assertTrue(hasSavedBatch(out, date, "backfill"))
                val reused = LlmRouter(emptyList(), CostGuard(1.0, ledger = ledger))
                assertEquals("{}", reused.batch("backfill", date, out, config, listOf(request), versions)
                    .responses.getValue(0).getOrThrow().json)
                assertEquals(4, server.requestCount)
            }
        }
    }

    @Test fun authenticationWaitsAndExpiredQuarantineRetriesAnUnreadableLedger() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val date = "2026-11-23"
                val path = batchPath(out, date, "classify")
                val versions = mapOf("classify" to "default")
                val config = PipelineConfig.Batch(pollSeconds = 1)
                val ledger = FileCostLedger(out.resolve("cost"))
                server.enqueue(batch("in_progress"))
                server.enqueue(response("{}").setResponseCode(401))
                val outcome = LlmRouter(listOf(provider), CostGuard(1.0, ledger = ledger))
                    .batch("classify", date, out, config, listOf(request), versions)
                assertTrue(outcome.pending)
                assertTrue(path.isFile)
                assertFalse(out.resolve("batch/quarantine").exists())
                val saved = Json.parseToJsonElement(path.readText()).jsonObject
                val expired = saved + ("firstMissingKeyAt" to kotlinx.serialization.json.JsonPrimitive("2000-01-01T00:00:00Z"))
                path.writeText(kotlinx.serialization.json.JsonObject(expired).toString())
                server.enqueue(batch("ended"))
                server.enqueue(response("{}").setResponseCode(401))
                assertTrue(LlmRouter(listOf(provider), CostGuard(1.0, ledger = ledger))
                    .batch("classify", date, out, config, listOf(request), versions).pending)
                val reset = Json.parseToJsonElement(path.readText()).jsonObject
                assertNotEquals(expired["firstMissingKeyAt"], reset["firstMissingKeyAt"])
                path.writeText(JsonObject(reset + ("firstMissingKeyAt" to expired.getValue("firstMissingKeyAt"))).toString())
                val unreadable = object : CostLedger by ledger {
                    override fun total(day: java.time.LocalDate): Double = error("unreadable ledger")
                    override fun hasReservation(day: java.time.LocalDate, id: String): Boolean = error("unreadable ledger")
                }
                val failed = LlmRouter(emptyList(), CostGuard(1.0, ledger = unreadable))
                    .recoverBatches(out, config) { true }
                assertTrue(failed.any { "settlement failed" in it })
                assertTrue(path.readText().contains("settleRetry"))
                assertFalse(out.resolve("batch/quarantine").exists())
                val quarantine = out.resolve("batch/quarantine").apply { writeText("blocks the move") }
                val restartCosts = CostGuard(1.0, ledger = ledger)
                val restarted = LlmRouter(emptyList(), restartCosts)
                val moveIssues = restarted.recoverBatches(out, config) { true }
                assertTrue(moveIssues.any { "quarantine retry" in it })
                assertTrue(path.exists())
                assertEquals("batch_quarantine", restartCosts.calls.single().error)
                assertTrue(quarantine.delete())
                val retryCosts = CostGuard(1.0, ledger = ledger)
                val retry = LlmRouter(emptyList(), retryCosts).recoverBatches(out, config) { true }
                assertTrue(retry.any { "quarantine" in it })
                assertTrue(retryCosts.calls.isEmpty())
                assertFalse(path.exists())
                assertEquals(4, server.requestCount)
            }
        }
    }

    @Test fun invalidCheckpointSettlementDeadlineSurvivesRestart() = runBlocking {
        val path = batchPath(out, "2026-11-23", "classify")
        path.parentFile.mkdirs()
        path.writeText("{")
        val ledger = FileCostLedger(out.resolve("cost"))
        val unreadable = object : CostLedger by ledger {
            override fun batchReservations(owner: String): Map<java.time.LocalDate, Map<String, Double>> =
                error("unreadable ledger")
        }
        suspend fun recover() = LlmRouter(emptyList(), CostGuard(1.0, ledger = unreadable))
            .recoverBatches(out, PipelineConfig.Batch()) { true }
        recover()
        val retry = path.resolveSibling(path.name + ".retry.json")
        assertTrue(path.isFile)
        assertNotNull(Json.parseToJsonElement(retry.readText()).jsonObject["firstSettleFailureAt"])
        retry.writeText("""{"firstSettleFailureAt":"2000-01-01T00:00:00Z"}""")
        recover()
        assertFalse(path.exists())
        assertFalse(retry.exists())
        assertTrue(path.resolveSibling("quarantine").listFiles().orEmpty().any { it.name.endsWith(".unsettled.json") })
    }

    @Test fun staleQuarantineSettlementKeepsTheReservationsForStartupRetry() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val date = "2026-11-23"
                val path = batchPath(out, date, "classify")
                val versions = mapOf("classify" to "default")
                val config = PipelineConfig.Batch(pollSeconds = 1)
                val ledger = FileCostLedger(out.resolve("cost"))
                server.enqueue(batch("in_progress"))
                server.enqueue(response("{}").setResponseCode(401))
                val initial = LlmRouter(listOf(provider), CostGuard(1.0, ledger = ledger))
                assertTrue(initial.batch("classify", date, out, config, listOf(request), versions).pending)
                val checkpoint = Json.parseToJsonElement(path.readText()).jsonObject
                path.writeText(JsonObject(checkpoint + mapOf(
                    "firstMissingKeyAt" to JsonPrimitive("2000-01-01T00:00:00Z"),
                    "settleRetry" to JsonPrimitive("2000-01-01T00:00:00Z")
                )).toString())
                val faulty = object : CostLedger by ledger {
                    override fun settleBatch(day: java.time.LocalDate, id: String, amount: Double) = error("ledger unavailable")
                }
                val issues = LlmRouter(emptyList(), CostGuard(1.0, ledger = faulty))
                    .recoverBatches(out, config) { true }
                assertTrue(issues.any { "quarantine" in it })
                val unsettled = out.resolve("batch/quarantine/batch_1.unsettled.json")
                assertTrue(unsettled.readText().contains(path.name))
                assertFalse(path.exists())
                val costs = CostGuard(1.0, ledger = ledger)
                LlmRouter(emptyList(), costs).recoverBatches(out, config) { true }
                assertFalse(unsettled.exists())
                assertEquals("batch_quarantine", costs.calls.single().error)
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun unexpectedIoSettlesReservationAndExpiredDeadlineMakesNoCall() = runBlocking {
        val settlements = mutableListOf<Double>()
        val ledger = object : CostLedger {
            override fun total(day: java.time.LocalDate) = 0.0
            override fun reserve(day: java.time.LocalDate, id: String, amount: Double) = Unit
            override fun settle(day: java.time.LocalDate, id: String, amount: Double) { settlements += amount }
        }
        var requests = 0
        val provider = object : LlmProvider {
            override val id = ProviderId.ANTHROPIC
            override suspend fun listModels() = emptyList<ModelInfo>()
            override suspend fun complete(req: LlmRequest): LlmResult {
                requests++
                throw java.io.IOException("mock")
            }
        }
        val costs = CostGuard(1.0, ledger = ledger)
        val router = LlmRouter(listOf(provider), costs, preflightBudget = true)
        assertFailsWith<java.io.IOException> { router.complete("classify", request, "classify", "default") }
        assertTrue(settlements.single() > 0)
        assertEquals(settlements.single(), costs.calls.single().costUsd)
        assertTrue(costs.calls.single().priceUnverified)
        router.startRun(java.time.Instant.now().minusSeconds(120), 1)
        assertFailsWith<LlmServiceException> { router.complete("classify", request, "classify", "default") }
        assertEquals(1, requests)
    }

    @Test fun searchTimeoutSettlesItsFullReservation() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val ledger = FileCostLedger(out.resolve("cost"))
                val costs = CostGuard(1.0, ledger = ledger)
                val router = LlmRouter(listOf(provider), costs, timeoutMillis = 200)
                server.enqueue(response("{}").setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals("timeout", assertFailsWith<LlmServiceException> {
                    router.supplementSearch("mock", PipelineConfig.Search(true, 2))
                }.reason)
                val charge = costs.calls.single()
                assertTrue(charge.costUsd > 0.02)
                assertTrue(charge.priceUnverified)
                assertEquals(charge.costUsd, ledger.total(java.time.LocalDate.now()))
            }
        }
    }

    @Test fun generateBatchResultDownloadUsesRunDeadline() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val router = LlmRouter(listOf(provider), CostGuard(1.0),
                    stageModels = mapOf("generate" to StageModel(ProviderId.ANTHROPIC, model)))
                val config = PipelineConfig.Batch(generate = true, pollSeconds = 1)
                val versions = mapOf("ideas" to "default")
                server.enqueue(batch("in_progress"))
                server.enqueue(response("{}").setResponseCode(503))
                assertTrue(router.batch("generate", "2026-11-23", out, config, listOf(request), versions).pending)
                server.enqueue(batch("ended"))
                server.enqueue(response(row(1, success) + "\n").setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS))
                router.startRun(java.time.Instant.now().minusSeconds(58), 1)
                val outcome = router.batch("generate", "2026-11-23", out, PipelineConfig.Batch(generate = true),
                    listOf(request), mapOf("ideas" to "default"))
                assertTrue(outcome.pending)
                assertTrue(outcome.responses.isEmpty())
                assertTrue(out.resolve("batch/2026-11-23-generate.json").isFile)
                assertEquals(4, server.requestCount)
            }
        }
    }

    @Test fun openAiSkipsBatchAndRetainsSyncRouting() = runBlocking {
        val fake = object : LlmProvider {
            override val id = ProviderId.OPENAI
            override suspend fun listModels() = emptyList<ModelInfo>()
            override suspend fun complete(req: LlmRequest) = LlmResult("{}", id, req.model, 1, 1, 0, 0.0)
        }
        val costs = CostGuard(1.0)
        val router = LlmRouter(listOf(fake), costs)
        val result = router.batch("classify", "2026-11-23", out, PipelineConfig.Batch(),
            listOf(request), mapOf("classify" to "default"))
        assertEquals(listOf("Batch 미지원 공급자 · 동기 실행"), result.issues)
        assertEquals(ProviderId.OPENAI, router.complete("classify", request, "classify", "default").provider)
        assertFalse(costs.calls.single().batch)
    }

    @Test fun searchDoesNotCallProviderWhenDisabledOrOverBudget() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val router = LlmRouter(listOf(provider), CostGuard(0.005))
                assertTrue(router.supplementSearch("candidate", PipelineConfig.Search(false, 3)).isEmpty())
                assertFailsWith<BudgetExceededException> {
                    router.supplementSearch("candidate", PipelineConfig.Search(true, 3))
                }
                assertEquals(0, server.requestCount)
            }
        }
    }

    @Test fun searchOnlyReturnsServerResultLinksAndChargesUsage() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val costs = CostGuard(1.0)
                server.enqueue(response(message("""[{"type":"text","text":"https://example.net/invented"},
                    {"type":"web_search_tool_result","tool_use_id":"srvtool_1","caller":{"type":"direct"},"content":[
                    {"type":"web_search_result","url":"https://example.com/result","title":"Result","encrypted_content":"mock","page_age":null}]}]""",
                    ""","server_tool_use":{"web_search_requests":2}""")))
                val result = LlmRouter(listOf(provider), costs).supplementSearch("candidate", PipelineConfig.Search(true, 3))
                assertEquals(listOf("https://example.com/result"), result.map { it.url })
                assertEquals(2, costs.calls.single().webSearchRequests)
                assertTrue(costs.calls.single().costUsd >= 0.02)
                val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                assertEquals(JsonPrimitive("web_search_20260209"), body["tools"]!!.jsonArray.single().jsonObject["type"])
            }
        }
    }
}


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
import kotlinx.serialization.json.*
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
                    val result = runtime.runScheduled(ScheduledOptions(batchOverride = false,
                        runDate = java.time.LocalDate.now(clock), onStage = {
                        stages += it
                        if (cancelMail && it == RunStage.MAIL) {
                            val journal = paths.outDir.resolve("mail/sent.json").readText()
                            assertTrue(journal.contains("\"kind\": \"trend\""))
                            context.cancel()
                        }
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
            assertTrue(paths.outDir.resolve("mail/sent.json").isFile)
            if (cancelMail) {
                val trendSubject = paths.outDir.resolve("trend/$day.txt").useLines { it.first() }
                val tomorrow = Clock.offset(clock, java.time.Duration.ofDays(1))
                paths.sourcesConfig.writeText(ConfigLoader.json.encodeToString(SourcesFile(emptyList())))
                val retry = CollectorRuntime(paths, SecretProvider { null }, tomorrow, emptyList(), { emptyList() },
                    { Mailer { mail += it.subject } })
                repeat(2) { retry.runScheduled(ScheduledOptions(runDate = java.time.LocalDate.now(tomorrow))) }
                assertEquals(1, mail.count { it == trendSubject })
            }
            assertEquals("working directory marker", marker.readText())
            assertEquals(before, listOf(snapshot(working.outDir), snapshot(working.configDir)))
        } finally {
            marker.delete()
        }
    }

    @org.junit.jupiter.api.Test
    fun damagedMailJournalDoesNotBlockPublication() = runBlocking {
        val paths = paths()
        paths.sourcesConfig.writeText(ConfigLoader.json.encodeToString(SourcesFile(emptyList())))
        paths.outDir.resolve("mail").mkdirs()
        paths.outDir.resolve("mail/sent.json").writeText("damaged")
        val runtime = CollectorRuntime(paths, SecretProvider { null }, clock, emptyList(), { emptyList() },
            { Mailer { error("Sending requires a valid journal") } })
        val summary = runtime.runScheduled(ScheduledOptions(runDate = java.time.LocalDate.now(clock)))
        assertTrue(summary.daily?.published == true)
        assertTrue(paths.outDir.resolve("$day.json").isFile)
        assertEquals(1, summary.exitCode)
    }

    @org.junit.jupiter.api.Test
    fun phoneRunDoesNotPublishWhenEverySourceFailsBeforeAnyCharge() = runBlocking {
        val paths = paths()
        val failed = object : SourceAdapter {
            override val id = "hf"
            override suspend fun fetch(config: SourceConfig, now: Instant): List<Item> =
                throw java.io.IOException("fixture network failure")
        }
        val runtime = CollectorRuntime(paths, SecretProvider { null }, clock, listOf(failed), { emptyList() })
        val date = java.time.LocalDate.parse(day)
        val summary = runtime.runScheduled(ScheduledOptions(mail = false, batchOverride = false, runDate = date))
        assertEquals(1, summary.exitCode)
        assertEquals(0.0, runtime.dailyCost(date))
        assertTrue(!paths.outDir.resolve("$day.json").exists())
    }

    @org.junit.jupiter.api.Test
    fun deferringBatchStopsAfterDurableSubmission() = runBlocking {
        val paths = paths()
        val checkpoint = paths.outDir.resolve("batch/$day-classify.json")
        BatchMockServer().use { server ->
            server.enqueue(MockResponse().addHeader("Content-Type", "application/json")
                .setBody("""{"id":"phone_batch","processing_status":"in_progress"}"""))
            val runtime = CollectorRuntime(paths, SecretProvider { null }, clock, listOf(source),
                { listOf(AnthropicProvider("mock", server.url("/").toString())) })
            kotlin.test.assertFailsWith<BatchDeferredException> {
                withTimeout(10_000) {
                    runtime.runScheduled(ScheduledOptions(mail = false, batchOverride = true, deferBatch = true))
                }
            }
            assertTrue(isPendingBatch(checkpoint))
            assertTrue("phone_batch" in checkpoint.readText())
            assertEquals(1, server.requestCount)
            assertTrue(!paths.outDir.resolve("$day.json").exists())
        }
    }

    @org.junit.jupiter.api.Test
    fun olderBatchDoesNotDeferTodaysRun() = runBlocking {
        val paths = paths()
        val today = java.time.LocalDate.parse(day)
        val yesterday = today.minusDays(1)
        fun pending() = MockResponse().addHeader("Content-Type", "application/json")
            .setBody("""{"id":"phone_batch","processing_status":"in_progress"}""")
        BatchMockServer().use { server ->
            server.enqueue(pending())
            val runtime = CollectorRuntime(paths, SecretProvider { null }, clock, listOf(source),
                { listOf(AnthropicProvider("mock", server.url("/").toString())) })
            kotlin.test.assertFailsWith<BatchDeferredException> {
                runtime.runScheduled(ScheduledOptions(mail = false, batchOverride = true, deferBatch = true, runDate = yesterday))
            }
            server.enqueue(pending())
            paths.sourcesConfig.writeText(ConfigLoader.json.encodeToString(SourcesFile(emptyList())))
            val todayRuntime = CollectorRuntime(paths, SecretProvider { null }, clock, emptyList(),
                { listOf(AnthropicProvider("mock", server.url("/").toString())) })
            val result = withTimeout(20_000) {
                todayRuntime.runScheduled(ScheduledOptions(mail = false, batchOverride = true, deferBatch = true, runDate = today))
            }
            assertEquals(0, result.exitCode)
            assertTrue(paths.outDir.resolve("$day.json").isFile)
            assertTrue(runtime.hasPendingBatch(yesterday))
        }
    }

    @org.junit.jupiter.api.Test
    fun settledYesterdayBatchCanPublishExactlyOnceWithoutNewLlmCalls() = runBlocking {
        val paths = paths()
        val today = java.time.LocalDate.now(clock)
        val yesterday = today.minusDays(1)
        fun state(status: String) = MockResponse().addHeader("Content-Type", "application/json")
            .setBody("""{"id":"previous","processing_status":"$status"}""")
        val mail = mutableListOf<String>()
        BatchMockServer().use { server ->
            server.enqueue(state("in_progress"))
            val runtime = CollectorRuntime(paths, SecretProvider { null }, clock, listOf(source),
                { listOf(AnthropicProvider("mock", server.url("/").toString())) }, { Mailer { mail += it.subject } })
            val options = ScheduledOptions(batchOverride = true, deferBatch = true)
            kotlin.test.assertFailsWith<BatchDeferredException> {
                runtime.runScheduled(options.copy(runDate = yesterday))
            }
            val customId = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                .getValue("requests").jsonArray.single().jsonObject.getValue("custom_id")
            server.enqueue(state("ended"))
            server.enqueue(MockResponse().setBody(
                """{"custom_id":$customId,"result":{"type":"succeeded","message":${message()}}}""" + "\n"))
            val sourceSettings = paths.sourcesConfig.readText()
            paths.sourcesConfig.writeText(ConfigLoader.json.encodeToString(SourcesFile(emptyList())))
            val unpublished = mutableListOf<java.time.LocalDate>()
            assertEquals(0, runtime.runScheduled(options.copy(runDate = today,
                onUnpublishedBatch = { unpublished += it })).exitCode)
            assertEquals(listOf(yesterday), unpublished)
            assertTrue(!runtime.hasPendingBatch(yesterday))
            assertTrue(runtime.hasBatchToResume(yesterday))
            paths.sourcesConfig.writeText(sourceSettings)
            val continuation = options.copy(runDate = yesterday, resumeBatch = true)
            assertEquals(0, runtime.runScheduled(continuation).exitCode)
            val report = paths.outDir.resolve("$yesterday.json")
            val published = report.readText()
            assertTrue(runtime.runScheduled(continuation).skipped)
            assertEquals(published, report.readText())
            assertEquals(1, mail.count { it == paths.outDir.resolve("$yesterday.txt").useLines { lines -> lines.first() } })
            assertEquals(3, server.requestCount)
        }
    }

    @org.junit.jupiter.api.Test
    fun cancellationAfterPublicationLeavesExactlyOneMailForNextRun() = runBlocking {
        val paths = paths()
        val today = java.time.LocalDate.parse(day)
        val subjects = mutableListOf<String>()
        paths.sourcesConfig.writeText(ConfigLoader.json.encodeToString(SourcesFile(emptyList())))
        val runtime = CollectorRuntime(paths, SecretProvider { null }, clock, emptyList(), { emptyList() },
            { collector.mail.Mailer { subjects += it.subject } })
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
            runtime.runScheduled(ScheduledOptions(runDate = today, onStage = {
                if (it == RunStage.MAIL) {
                    assertTrue(paths.outDir.resolve("$day.json").isFile)
                    throw kotlinx.coroutines.CancellationException("fixture")
                }
            }))
        }
        assertTrue(subjects.isEmpty())
        val originalSubject = paths.outDir.resolve("$day.txt").useLines { it.first() }
        val nextClock = Clock.offset(clock, java.time.Duration.ofDays(1))
        val nextRuntime = CollectorRuntime(paths, SecretProvider { null }, nextClock, emptyList(), { emptyList() },
            { collector.mail.Mailer { subjects += it.subject } })
        repeat(2) { nextRuntime.runScheduled(ScheduledOptions(runDate = today.plusDays(1))) }
        assertEquals(3, subjects.size)
        assertEquals(1, subjects.count { it == originalSubject })
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
            val options = ScheduledOptions(mail = false, deferMailDelivery = true)
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

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource("0,current", "1,current", "1,legacy", "1,public-legacy",
        "1,malformed-legacy")
    fun classificationContinuationGeneratesAndReusesPaidCard(daysAgo: Long, checkpointKind: String) = runBlocking {
        val legacySchema = checkpointKind.endsWith("legacy")
        val malformed = checkpointKind == "malformed-legacy"
        val publicCurrent = checkpointKind == "public-current"
        val requestedWhilePending = checkpointKind == "requested-while-pending"
        val paths = paths()
        val date = java.time.LocalDate.now(clock).minusDays(daysAgo)
        paths.sourcesConfig.writeText(ConfigLoader.json.encodeToString(SourcesFile(listOf(
            HfConfig(profile = listOf("tech-signal", "demand-signal"))))))
        val items = listOf(
            Item("hf", "1", "Local speech recognition", "https://example.invalid/tech", 10, publishedAt = clock.instant()),
            Item("hf", "2", "Requests for meeting notes", "https://example.invalid/demand", 9, publishedAt = clock.instant())
        )
        val adapter = object : SourceAdapter {
            override val id = "hf"
            override suspend fun fetch(config: SourceConfig, now: Instant) = items.map { it.copy(publishedAt = now) }
        }
        val refs = items.map { collector.model.SignalRef(it.key, it.url, "Evidence") }
        val card = collector.model.IdeaCard("fixture", date.toString(), "Meeting notes", "One line", "Today", "Problem",
            listOf(refs[0]), listOf(refs[1]), emptyList(), emptyList(), "Two weeks", "Difference",
            "Risk", "Validation", emptyList(), "dev-tools", tags = listOf("meeting-notes"),
            targetCustomer = "회의 진행자", coreTech = "음성 인식", pain = "회의 기록 누락")
        val older = date.minusDays(1)
        val previous = collector.pipeline.PipelineResult(emptyList(), emptyList(),
            collector.model.RunReport(older.toString(), older.atStartOfDay(ZoneOffset.UTC).toInstant().toString(),
                emptyList(), emptyList(), emptyMap(), 0), cards = listOf(card.copy(runDate = older.toString(), title = "Archived agenda tracker")))
        paths.outDir.mkdirs()
        paths.outDir.resolve("$older.json").writeText(collector.dev.outputJson.encodeToString(previous))
        val submitted = mutableMapOf<String, Map<String, collector.llm.LlmRequest>>()
        var synchronous = 0
        val provider = object : collector.llm.BatchCapableProvider {
            override val id = ProviderId.ANTHROPIC
            override suspend fun listModels() = emptyList<collector.llm.ModelInfo>()
            override suspend fun complete(req: collector.llm.LlmRequest): LlmResult {
                synchronous++
                check(malformed && req.schemaName == "generate")
                val missingFields = card.copy(targetCustomer = "", coreTech = "", pain = "")
                val body = Json.encodeToString(mapOf("cards" to listOf(missingFields)))
                return LlmResult(body, id, req.model, 10, 4, 0, 0.001)
            }
            override fun prepareBatch(requests: Map<String, collector.llm.LlmRequest>): suspend () -> collector.llm.BatchState = {
                val stage = requests.values.single().schemaName
                check(submitted.put(stage, requests) == null)
                collector.llm.BatchState(stage, false, false)
            }
            override suspend fun findBatches(since: Instant) = emptyList<collector.llm.BatchState>()
            override suspend fun retrieveBatch(id: String) = collector.llm.BatchState(id, true, false)
            override suspend fun cancelBatch(id: String) = error("Unexpected batch cancellation")
            override suspend fun batchResults(id: String, receive: (String, Result<LlmResult>?) -> Unit) {
                val req = submitted.getValue(id).entries.single()
                val json = if (id == "classify") """{"items":[
                    {"itemId":"hf:1","profile":"tech-signal","score":9,"reason":"tech","summary":"Tech","tags":["speech"],"topicKey":"speech","topicLabel":"Speech"},
                    {"itemId":"hf:2","profile":"demand-signal","score":9,"reason":"demand","summary":"Demand","tags":["notes"],"topicKey":"notes","topicLabel":"Notes"}
                ]}""" else if (malformed) "{}" else {
                    val generated = when {
                        legacySchema -> card.copy(targetCustomer = "", coreTech = "", pain = "")
                        publicCurrent -> {
                            val seed = Json.parseToJsonElement(req.value.user).jsonObject
                                .getValue("expansionSeeds").jsonArray.first().jsonObject
                            card.copy(kind = "expanded", lens = seed.getValue("lens").jsonPrimitive.content,
                                seedId = seed.getValue("id").jsonPrimitive.content,
                                targetCustomer = seed.getValue("targetCustomer").jsonPrimitive.content,
                                speculation = listOf("다른 고객도 사용할 수 있습니다."))
                        }
                        else -> card
                    }
                    Json.encodeToString(mapOf("cards" to listOf(generated)))
                }
                receive(req.key, Result.success(LlmResult(json, this.id, req.value.model, 10, 4, 0, 0.001)))
            }
        }
        val runtime = CollectorRuntime(paths, SecretProvider { null }, clock, listOf(adapter), { listOf(provider) })
        val options = ScheduledOptions(batchOverride = true, deferBatch = true, mail = false, runDate = date)
        kotlin.test.assertFailsWith<BatchDeferredException> { runtime.runScheduled(options) }
        assertEquals(setOf("classify"), submitted.keys)
        val resume = options.copy(resumeBatch = true)
        kotlin.test.assertFailsWith<BatchDeferredException> { runtime.runScheduled(resume) }
        assertEquals(setOf("classify", "generate"), submitted.keys)
        val generationCheckpoint = paths.outDir.resolve("batch/$date-generate.json")
        val checkpoint = Json.parseToJsonElement(generationCheckpoint.readText()).jsonObject
        assertEquals(JsonPrimitive(2), checkpoint["generationSchemaVersion"])
        if (legacySchema) {
            val fieldsAddedByX1 = setOf("targetCustomer", "coreTech", "pain", "kind", "lens", "seedId", "speculation")
            val canonical = checkpoint.getValue("generationInput").jsonObject
            val schema = canonical.getValue("jsonSchema").jsonObject
            val root = schema.getValue("properties").jsonObject
            val cards = root.getValue("cards").jsonObject
            val items = cards.getValue("items").jsonObject
            val oldItems = JsonObject(items + mapOf(
                "properties" to JsonObject(items.getValue("properties").jsonObject - fieldsAddedByX1),
                "required" to JsonArray(items.getValue("required").jsonArray.filterNot { it.jsonPrimitive.content in fieldsAddedByX1 })
            ))
            val oldSchema = JsonObject(schema + ("properties" to JsonObject(root +
                ("cards" to JsonObject(cards + ("items" to oldItems))))))
            val (key, request) = checkpoint.getValue("requests").jsonObject.entries.single()
            val oldRequest = JsonObject(request.jsonObject.getValue("request").jsonObject + ("jsonSchema" to oldSchema))
            val oldCanonical = JsonObject(canonical + ("jsonSchema" to oldSchema))
            val requestValue = Json.decodeFromString<collector.llm.LlmRequest>(oldCanonical.toString())
            val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
                .digest(requestValue.copy(model = submitted.getValue("generate").values.single().model)
                    .toString().toByteArray()).joinToString("") { "%02x".format(it) }
            generationCheckpoint.writeText(JsonObject(checkpoint - setOf("generationSchemaVersion", "generationSeeds") + mapOf(
                "generationInput" to oldCanonical,
                "requests" to JsonObject(mapOf(key to JsonObject(request.jsonObject + mapOf(
                    "request" to oldRequest, "fingerprint" to JsonPrimitive(fingerprint)
                ))))
            )).toString())
            if (checkpointKind == "public-legacy") {
                generationCheckpoint.writeText(collector.llm.publicBatchCheckpoint(generationCheckpoint.readText()))
            }
        }
        if (publicCurrent) {
            generationCheckpoint.writeText(collector.llm.publicBatchCheckpoint(generationCheckpoint.readText()))
            val inputFile = paths.outDir.resolve("batch/$date-generate.input")
            val input = Json.parseToJsonElement(inputFile.readText()).jsonObject
            inputFile.writeText(JsonObject(input + ("expansionSeeds" to JsonArray(emptyList()))).toString())
        }
        if (requestedWhilePending) {
            val requested = paths.outDir.resolve("requested").apply { mkdirs() }
            val requestedRun = previous.copy(report = previous.report.copy(runDate = date.toString(),
                startedAt = clock.instant().toString()), cards = listOf(card.copy(id = "requested-after-submit")))
            requested.resolve("$date-requested.json").writeText(collector.dev.outputJson.encodeToString(requestedRun))
            val result = runtime.runScheduled(resume)
            assertEquals(0, result.daily?.cards)
            val failed = paths.outDir.resolve("runs/failed").listFiles().orEmpty().single { it.name.startsWith("$date-") }
            assertEquals(mapOf("REPEAT" to 1),
                collector.dev.outputJson.decodeFromString<collector.pipeline.PipelineResult>(failed.readText()).metrics.cardRejections)
            assertEquals(0, synchronous)
            return@runBlocking
        }
        if (malformed) {
            val result = runtime.runScheduled(resume)
            assertEquals(0, result.daily?.cards)
            assertEquals(false, result.daily?.published)
            val failed = paths.outDir.resolve("runs/failed").listFiles().orEmpty().single { it.name.startsWith("$date-") }
            val archived = collector.dev.outputJson.decodeFromString<collector.pipeline.PipelineResult>(failed.readText())
            assertEquals(mapOf("MISSING_FIELD" to 1), archived.metrics.cardRejections)
            assertEquals(1, synchronous)
            return@runBlocking
        }
        val receiver = CollectorRuntime(paths, SecretProvider { null }, clock, emptyList(), { listOf(provider) })
        receiver.runScheduled(options.copy(runDate = date.plusDays(1)), SourcesFile(emptyList()), clock.instant(), clock)
        assertTrue(!runtime.hasPendingBatch(date))
        runtime.applyLocalChanges(listOf(collector.data.InboxRequest.Reaction(1, java.util.UUID.randomUUID().toString(),
            clock.instant().toString(), card.id, collector.model.Feedback.Kind.STAR, "Changed after generation")))
        // 제출 뒤 사용자가 바꾼 추적 상태(중지)는 이어받기 게시가 스냅샷으로 덮어쓰면 안 된다.
        val stopped = collector.model.TrackedIdea("user-stopped", "other-card", "Stopped by user", "dev-tools", listOf("notes"),
            emptyList(), collector.model.TrackedIdea.Status.STOPPED, clock.instant(), null, 0.0, collector.model.TrackedIdea.Trend.FLAT)
        paths.outDir.resolve("tracked.json").writeText(collector.dev.outputJson.encodeToString(listOf(stopped)))
        val result = runtime.runScheduled(resume)
        assertTrue(paths.outDir.resolve("tracked.json").readText().contains("user-stopped"))
        assertEquals(1, result.daily?.cards)
        assertTrue(result.daily?.published == true)
        assertTrue(runtime.runScheduled(resume).skipped)
        assertEquals(0, synchronous)
        assertEquals(setOf("classify", "generate"), submitted.keys)
    }

    @org.junit.jupiter.api.Test
    fun earlierMailFailureAndExpiryAppearInTheScheduledResultOnce() = runBlocking {
        val paths = paths()
        paths.sourcesConfig.writeText(ConfigLoader.json.encodeToString(SourcesFile(emptyList())))
        val firstDay = java.time.LocalDate.now(clock)
        var attempts = 0
        val mailer = Mailer {
            if (it.subject == "Earlier report") {
                attempts++
                throw java.io.IOException("fixture SMTP failure")
            }
        }
        val existing = paths.outDir.resolve("$firstDay.txt")
        existing.parentFile.mkdirs()
        existing.writeText("Earlier report\nBody")
        paths.outDir.resolve("$firstDay.html").writeText("<p>Body</p>")
        val journal = collector.mail.MailDelivery(paths.outDir, mailer, clock = clock)
        assertEquals(collector.mail.MailSendResult.FAILED, journal.send(collector.mail.MailKind.DAILY, firstDay.toString()))
        suspend fun run(afterDays: Long): ScheduledSummary {
            val later = Clock.offset(clock, java.time.Duration.ofDays(afterDays))
            return CollectorRuntime(paths, SecretProvider { null }, later, emptyList(), { emptyList() }, { mailer })
                .runScheduled(ScheduledOptions(runDate = firstDay.plusDays(afterDays)))
        }
        val retried = run(1)
        assertEquals(mapOf(collector.mail.MailSendResult.FAILED to 1), retried.previousMailRetries)
        assertEquals(ScheduledMailStatus.SENT, retried.mailStatus)
        assertEquals(1, run(4).expiredMailRetries)
        assertEquals(0, run(5).expiredMailRetries)
        assertEquals(2, attempts)
    }

    private fun message() =
        """{"id":"msg_mock","type":"message","role":"assistant","model":"claude-haiku-5-5","content":[{"type":"text","text":${kotlinx.serialization.json.JsonPrimitive(classified)}}],"stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":4}}"""
}

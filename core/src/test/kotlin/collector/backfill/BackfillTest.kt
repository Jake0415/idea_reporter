package collector.backfill

import collector.budget.BudgetSettings
import collector.budget.MonthlyBudget
import collector.config.HnConfig
import collector.config.GitHubConfig
import collector.config.PipelineConfig
import collector.config.SourcesFile
import collector.dev.FileCostLedger
import collector.dev.FileHistorySearch
import collector.dev.JsonFileSeenStore
import collector.dev.outputJson
import collector.dev.readJson
import collector.dev.writeAtomically
import collector.llm.*
import collector.model.DropReason
import collector.model.RunReport
import collector.pipeline.PipelineResult
import collector.pipeline.Pipeline
import collector.profile.ProfileLoader
import collector.prompt.FilePromptStore
import collector.prompt.PromptResolver
import collector.source.GitHubAdapter
import collector.source.HackerNewsAdapter
import collector.stage.Classifier
import collector.trend.TrendInput
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.*

class BackfillTest {
    @TempDir lateinit var out: File
    private val date = LocalDate.parse("2026-11-20")
    private val newest = date.minusDays(1)
    private val previous = date.minusDays(2)
    private val defaults = PipelineConfig.defaults
    private val model = defaults.models.classify
    private val messages = mutableListOf<String>()
    private var llmCalls = 0
    private val requestedKeys = mutableListOf<List<String>>()

    private fun backfill(server: MockWebServer, budget: Double, github: MockWebServer? = null,
                         batchSize: Int = defaults.classifyBatchSize,
                         hnConfig: HnConfig = HnConfig(queries = listOf("AI")),
                         writeCache: (File, String) -> Unit = File::writeAtomically,
                         monthlyBudget: MonthlyBudget? = null): Backfill {
        val http = OkHttpClient()
        val guard = CostGuard(budget, ledger = FileCostLedger(out.resolve("cost-ledger")), budgetPerRun = true)
        val fake = object : LlmProvider {
            override val id = model.provider
            override suspend fun listModels() = emptyList<ModelInfo>()
            override suspend fun complete(req: LlmRequest): LlmResult {
                llmCalls++
                val keys = Json.parseToJsonElement(req.user).jsonObject.getValue("items").jsonArray
                    .map { it.jsonObject.getValue("itemId").jsonPrimitive.content }
                requestedKeys += keys
                val items = keys.joinToString(",") {
                    """{"itemId":"$it","profile":"tech-signal","score":80,"reason":"r","summary":"s","tags":["ai"],"topicKey":"ai","topicLabel":"AI"}"""
                }
                return LlmResult("""{"items":[$items]}""", id, req.model, 100, 100, 0, 0.1)
            }
        }
        val classifier = Classifier(defaults.copy(classifyBatchSize = batchSize), ProfileLoader.loadDefaults(),
            PromptResolver(FilePromptStore(out.resolve("prompts").toPath())),
            LlmRouter(listOf(fake), guard, mapOf("classify" to model), preflightBudget = true))
        val sources = listOfNotNull(hnConfig,
            github?.let { GitHubConfig(queries = listOf("AI")) })
        val githubAdapter = github?.let { GitHubAdapter(http, { null }, it.url("/").toString()) }
            ?: GitHubAdapter(http, { null })
        return Backfill(out, SourcesFile(sources),
            HackerNewsAdapter(http, server.url("/").toString()), githubAdapter,
            model, guard, classifier, messages::add,
            clock = { date.atStartOfDay().toInstant(ZoneOffset.UTC) }, writeCache = writeCache, monthlyBudget = monthlyBudget)
    }

    @Test fun overallMonthlyCapRejectsBeforeCollectionOrClassification() = runTest {
        FileCostLedger(out.resolve("cost-ledger-analysis")).reserve(date, "analysis", 0.25)
        val monthly = MonthlyBudget(out, BudgetSettings(monthlyTotalUsd = 0.50))
        MockWebServer().use { server ->
            backfill(server, 0.50, monthlyBudget = monthly).run(date, 1, false, true, false)
            assertEquals(0, server.requestCount)
            assertEquals(0, llmCalls)
            assertContains(messages.single(), "전체 월 상한")
        }
    }

    @Test fun backfillRestoresClassificationsForDailyRunSkippedByMonthlyCap() = runTest {
        val capped = MonthlyBudget(out, BudgetSettings(monthlyTotalUsd = 0.0))
        val skipped = Pipeline(emptyList(), JsonFileSeenStore(out.resolve("seen.json")),
            clock = { newest.atStartOfDay().toInstant(ZoneOffset.UTC) }, zone = ZoneOffset.UTC,
            paidStageSkipReason = checkNotNull(capped.dailySkipReason(newest, 0.50))).run(SourcesFile(emptyList()))
        val archive = out.resolve("runs/$newest-skipped.json")
        val skippedJson = outputJson.encodeToString(skipped)
        archive.writeAtomically(skippedJson)
        val state = out.resolve("state/$newest.json")
        val brokenJson = "{"
        state.writeAtomically(brokenJson)
        assertEquals(skipped, TrendInput.read(out, date).runs[newest])

        MockWebServer().use { server ->
            server.story(newest)
            val uncapped = MonthlyBudget(out, BudgetSettings())
            backfill(server, 0.50, monthlyBudget = uncapped).run(date, 1, false, true, false)
            assertEquals(1, llmCalls)
            val trend = TrendInput.read(out, date)
            assertEquals(setOf(newest), trend.backfillDates)
            assertEquals(result(newest)!!.classified.single(), trend.runs.getValue(newest).classified.single())
        }
        assertContains(messages, "읽을 수 없는 실행 파일: ${state.path}")
        assertEquals(brokenJson, state.readText())
        assertEquals(skippedJson, archive.readText())
        assertFalse(out.resolve("$newest.json").exists())
    }

    private fun MockWebServer.story(day: LocalDate, id: String = "story") {
        val created = day.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        enqueue(MockResponse().setBody("""{"hits":[{"objectID":"$id","title":"Test story","points":50,"created_at_i":$created}]}"""))
    }

    private fun result(day: LocalDate) = readJson<PipelineResult>(out.resolve("backfill/$day.json"))

    private fun dailyResultJson(day: String): String {
        val report = RunReport(day, "${day}T00:00:00Z", emptyList(), emptyList(), emptyMap(), 0)
        return outputJson.encodeToString(PipelineResult(emptyList(), emptyList(), report))
    }

    @Test fun forceDryRunPreservesInterruptedReplacement() = runTest {
        MockWebServer().use { server ->
            server.story(newest, "old")
            backfill(server, 0.5).run(date, 1, dryRun = false, yes = true, force = false)
            server.story(newest, "replacement")
            backfill(server, 0.0).run(date, 1, dryRun = false, yes = true, force = true)
            val checkpoint = out.resolve("backfill/interrupted/$newest.json")
            val cache = out.resolve("backfill/collected/$newest.json")
            fun state() = Json.parseToJsonElement(cache.readText()).jsonObject
            assertTrue(checkpoint.isFile)
            val replacementId = state().getValue("replacementId")
            server.story(newest, "replacement")
            backfill(server, 0.5).run(date, 1, dryRun = true, yes = true, force = true)
            assertFalse(checkpoint.exists())
            assertEquals(replacementId, state().getValue("replacementId"))
            assertTrue(state().getValue("replacing").jsonPrimitive.boolean)
            assertEquals("old", result(newest)!!.classified.single().item.externalId)
            val calls = llmCalls
            backfill(server, 0.5).run(date, 1, dryRun = false, yes = true, force = false)
            assertEquals(calls + 1, llmCalls)
            assertEquals(3, server.requestCount)
            assertEquals("replacement", result(newest)!!.classified.single().item.externalId)
            assertFalse(state().getValue("replacing").jsonPrimitive.boolean)
        }
    }

    @Test fun failedCacheReplacementKeepsCheckpoint() = runTest {
        MockWebServer().use { server ->
            server.story(newest, "old")
            backfill(server, 0.5).run(date, 1, dryRun = false, yes = true, force = false)
            server.story(newest, "replacement")
            backfill(server, 0.0).run(date, 1, dryRun = false, yes = true, force = true)
            val checkpoint = out.resolve("backfill/interrupted/$newest.json")
            assertTrue(checkpoint.isFile)
            val saved = checkpoint.readText()
            server.story(newest, "replacement-2")
            val failing = backfill(server, 0.5, writeCache = { _, _ -> throw IOException("cache write failed") })
            assertFailsWith<IOException> { failing.run(date, 1, dryRun = true, yes = true, force = true) }
            // The old cache was not replaced, so its paired checkpoint must survive for the next resume.
            assertTrue(checkpoint.isFile)
            assertEquals(saved, checkpoint.readText())
        }
    }

    @Test fun identicalForcedRefreshDoesNotMatchPreviousResultHash() = runTest {
        MockWebServer().use { server ->
            repeat(3) { server.story(newest) }
            backfill(server, 0.5).run(date, 1, dryRun = false, yes = true, force = false)
            backfill(server, 0.5).run(date, 1, dryRun = false, yes = true, force = true)
            val cache = out.resolve("backfill/collected/$newest.json")
            fun state() = Json.parseToJsonElement(cache.readText()).jsonObject
            val previous = state()
            backfill(server, 0.0).run(date, 1, dryRun = false, yes = true, force = true)
            assertEquals(previous.getValue("sources"), state().getValue("sources"))
            assertNotEquals(previous.getValue("replacementId"), state().getValue("replacementId"))
            val calls = llmCalls
            backfill(server, 0.5).run(date, 1, dryRun = false, yes = true, force = false)
            assertEquals(calls + 1, llmCalls)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun completedReplacementIsNotClassifiedAgainAfterCacheUpdateFails() = runTest {
        MockWebServer().use { server ->
            server.story(newest, "old")
            backfill(server, 0.5).run(date, 1, dryRun = false, yes = true, force = false)
            server.story(newest, "replacement")
            val replacing = backfill(server, 0.5, writeCache = { file, content ->
                if (!Json.parseToJsonElement(content).jsonObject.getValue("replacing").jsonPrimitive.boolean) {
                    throw IOException("Interrupted before clearing the replacement flag")
                }
                file.writeAtomically(content)
            })
            assertFailsWith<IOException> {
                replacing.run(date, 1, dryRun = false, yes = true, force = true)
            }
            val completed = result(newest)!!
            assertEquals("replacement", completed.classified.single().item.externalId)
            val callsBeforeResume = llmCalls
            backfill(server, 0.5).run(date, 1, dryRun = false, yes = true, force = false)
            assertEquals(callsBeforeResume, llmCalls)
            assertEquals(2, server.requestCount)
            assertEquals(completed, result(newest))
            val cache = out.resolve("backfill/collected/$newest.json")
            assertFalse(Json.parseToJsonElement(cache.readText()).jsonObject.getValue("replacing").jsonPrimitive.boolean)
        }
    }

    @Test fun dryRunAndMissingConfirmationReuseCollectionAndPreserveDailyFiles() = runTest {
        MockWebServer().use { server ->
            val seenContent = """{"keyDates":{"hn:story":"$previous"}}"""
            val seen = out.resolve("seen.json").apply { writeText(seenContent) }
            val metrics = out.resolve("metrics.json").apply { writeText("[]") }
            val dailyContent = dailyResultJson(previous.toString())
            val daily = out.resolve("$previous.json").apply { writeText(dailyContent) }
            server.story(newest)
            val backfill = backfill(server, 0.5)
            backfill.run(date, 2, dryRun = true, yes = true, force = false)
            assertEquals(0, llmCalls)
            assertNull(result(newest))
            assertTrue(out.resolve("backfill/collected/$newest.json").isFile)
            val price = ModelCatalog.defaults.requirePrice(model.provider, model.model)
            assertEquals((1000 * price.inputUsd + 250 * price.outputUsd) / 1_000_000,
                Backfill.estimateCost(model, 1), 1e-12)
            assertTrue(messages.any { it.contains("추정 분류 USD") && it.contains("1건") })
            backfill.run(date, 2, dryRun = false, yes = false, force = false)
            assertEquals(0, llmCalls)
            assertEquals(1, server.requestCount)
            assertTrue("실행하려면 --yes" in messages)
            backfill.run(date, 2, dryRun = false, yes = true, force = false)
            assertEquals("story", result(newest)!!.classified.single().item.externalId)
            assertEquals(1, llmCalls)
            assertEquals(seenContent, seen.readText())
            assertEquals("[]", metrics.readText())
            assertEquals(dailyContent, daily.readText())
            assertTrue(FileHistorySearch(out, 30).runs(date, 1).isEmpty())
        }
    }

    @Test fun skipsCompletedAndActualRunsEvenWhenForcingBackfill() = runTest {
        MockWebServer().use { server ->
            val paths = listOf("$previous.json", "state/${date.minusDays(3)}.json", "runs/${date.minusDays(4)}-archive.json")
            val dailyFiles = paths.associateWith { path ->
                dailyResultJson(path.substringAfterLast('/').take(10))
                    .also { out.resolve(path).writeAtomically(it) }
            }
            server.story(newest)
            val backfill = backfill(server, 0.5)
            backfill.run(date, 4, dryRun = false, yes = true, force = false)
            backfill.run(date, 4, dryRun = false, yes = true, force = false)
            assertEquals(1, server.requestCount)
            server.story(newest, "replacement")
            backfill.run(date, 4, dryRun = false, yes = true, force = true)
            assertEquals(2, server.requestCount)
            assertEquals("replacement", result(newest)!!.items.single().externalId)
            dailyFiles.forEach { (path, json) -> assertEquals(json, out.resolve(path).readText()) }
        }
    }

    @Test fun budgetStopsAtUnfinishedDayAndNextRunReusesItsCollection() = runTest {
        MockWebServer().use { server ->
            server.story(newest)
            server.story(previous)
            backfill(server, 0.1).run(date, 2, dryRun = false, yes = true, force = false)
            assertEquals(1, llmCalls)
            assertEquals(0.1, result(newest)!!.report.costUsd)
            assertNull(result(previous))
            val interrupted = out.resolve("backfill/interrupted").listFiles()!!.single()
            assertEquals(DropReason.BUDGET_EXCEEDED,
                outputJson.decodeFromString<PipelineResult>(interrupted.readText()).dropped.single().reason)
            backfill(server, 0.1).run(date, 2, dryRun = false, yes = true, force = false)
            assertEquals(2, llmCalls)
            assertEquals(2, server.requestCount)
            assertEquals(0.1, result(previous)!!.report.costUsd)
            assertEquals(0.2, FileCostLedger(out.resolve("cost-ledger")).total(LocalDate.now()), 1e-12)
            val dailyGuard = CostGuard(0.25, ledger = FileCostLedger(out.resolve("cost-ledger")))
            assertFailsWith<BudgetExceededException> { dailyGuard.reserve(0.06) }
            dailyGuard.reserve(0.04)
            assertFailsWith<BudgetExceededException> { dailyGuard.reserve(0.02) }
        }
    }

    @Test fun failedSourceIsRetriedAloneBeforeFinalizing() = runTest {
        MockWebServer().use { hn ->
            MockWebServer().use { github ->
                hn.story(newest)
                hn.story(newest)
                hn.story(newest)
                github.enqueue(MockResponse().setResponseCode(500))
                backfill(hn, 0.5, github).run(date, 1, dryRun = true, yes = false, force = false)
                val cache = out.resolve("backfill/collected/$newest.json")
                val cachedSources = Json.parseToJsonElement(cache.readText()).jsonObject.getValue("sources").jsonObject
                assertEquals(setOf("hn"), cachedSources.keys)
                assertTrue(cachedSources.getValue("hn").jsonObject.getValue("ok").jsonPrimitive.boolean)
                assertTrue(cachedSources.getValue("hn").jsonObject.getValue("complete").jsonPrimitive.boolean)
                repeat(3) { github.enqueue(MockResponse().setBody("""{"incomplete_results":true}""")) }
                github.enqueue(MockResponse().setBody("""{"items":[]}"""))
                backfill(hn, 0.5, github).run(date, 1, dryRun = false, yes = true, force = false)
                assertNull(result(newest))
                assertEquals(0, llmCalls)
                assertEquals(1, hn.requestCount)
                assertEquals(4, github.requestCount)
                assertEquals(setOf("hn"), Json.parseToJsonElement(cache.readText())
                    .jsonObject.getValue("sources").jsonObject.keys)
                assertTrue(messages.last().contains("미완료 1일"))
                backfill(hn, 0.5, github).run(date, 1, dryRun = false, yes = true, force = false)
                assertEquals("story", result(newest)!!.classified.single().item.externalId)
                assertEquals(1, hn.requestCount)
                assertEquals(5, github.requestCount)
            }
        }
    }

    @Test fun legacyCollectionWithoutCompleteIsRefetched() = runTest {
        MockWebServer().use { server ->
            out.resolve("backfill/collected/$newest.json").apply {
                parentFile.mkdirs()
                writeText("""{"sources":{"hn":{"items":[],"millis":0,"ok":true}}}""")
            }
            server.story(newest)
            backfill(server, 0.5).run(date, 1, dryRun = false, yes = true, force = false)
            assertEquals(1, server.requestCount)
            assertEquals("story", result(newest)!!.items.single().externalId)
        }
    }

    @Test fun forceDryRunClearsCheckpointBeforeNextClassification() = runTest {
        MockWebServer().use { server ->
            val created = newest.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
            server.enqueue(MockResponse().setBody("""{"hits":[
                {"objectID":"one","title":"First story","points":60,"created_at_i":$created},
                {"objectID":"two","title":"Second story","points":50,"created_at_i":$created}
            ]}"""))
            backfill(server, 0.1, batchSize = 1).run(date, 1, dryRun = false, yes = true, force = false)
            val checkpoint = out.resolve("backfill/interrupted/$newest.json")
            assertTrue(checkpoint.isFile)
            server.story(newest, "one")
            backfill(server, 0.5).run(date, 1, dryRun = true, yes = true, force = true)
            assertFalse(checkpoint.exists())
            assertEquals(1, llmCalls)
            backfill(server, 0.5).run(date, 1, dryRun = false, yes = true, force = false)
            assertEquals(listOf(listOf("hn:one"), listOf("hn:one")), requestedKeys)
            assertEquals(0.1, result(newest)!!.report.costUsd)
        }
    }

    @Test fun refinementMatchesDailyPipelineWithoutSeen() = runBlocking {
        MockWebServer().use { server ->
            val created = newest.atStartOfDay().toEpochSecond(ZoneOffset.UTC) + 1
            repeat(2) {
                server.enqueue(MockResponse().setBody("""{"hits":[
                    {"objectID":"one","title":"First story","url":"https://example.com/shared","points":60,"created_at_i":$created},
                    {"objectID":"two","title":"Second story","url":"https://example.com/shared","points":50,"created_at_i":$created},
                    {"objectID":"three","title":"Third story","points":40,"created_at_i":$created}
                ]}"""))
            }
            val config = HnConfig(queries = listOf("AI"), maxItems = 2)
            val adapter = HackerNewsAdapter(OkHttpClient(), server.url("/").toString())
            val daily = Pipeline(listOf(adapter), JsonFileSeenStore(out.resolve("seen.json")),
                clock = { newest.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC) }, zone = ZoneOffset.UTC)
                .run(SourcesFile(listOf(config)))
            backfill(server, 0.5, hnConfig = config).run(date, 1, dryRun = false, yes = true, force = false)
            assertEquals(listOf("one"), daily.items.map { it.externalId })
            assertEquals(daily.items, result(newest)!!.items)
            assertEquals(daily.dropped, result(newest)!!.dropped)
        }
    }

    @Test fun resumesOnlyUnclassifiedBatchAndKeepsAccumulatedCost() = runTest {
        MockWebServer().use { server ->
            val created = newest.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
            server.enqueue(MockResponse().setBody("""{"hits":[
                {"objectID":"one","title":"First story","points":60,"created_at_i":$created},
                {"objectID":"two","title":"Second story","points":50,"created_at_i":$created}
            ]}"""))
            backfill(server, 0.1, batchSize = 1).run(date, 1, dryRun = false, yes = true, force = false)
            assertNull(result(newest))
            assertEquals(listOf(listOf("hn:one")), requestedKeys)
            val checkpoint = out.resolve("backfill/interrupted/$newest.json")
            assertEquals(0.1, readJson<PipelineResult>(checkpoint)!!.report.costUsd)
            backfill(server, 0.1, batchSize = 1).run(date, 1, dryRun = false, yes = true, force = false)
            assertEquals(listOf(listOf("hn:one"), listOf("hn:two")), requestedKeys)
            assertEquals(2, result(newest)!!.classified.size)
            assertEquals(0.2, result(newest)!!.report.costUsd, 1e-12)
            assertFalse(checkpoint.exists())
        }
    }
}

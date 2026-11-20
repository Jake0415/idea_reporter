package collector.pipeline

import collector.NOW
import collector.budget.BudgetSettings
import collector.budget.MonthlyBudget
import collector.config.GitHubConfig
import collector.config.GitHubIssuesConfig
import collector.config.PipelineConfig
import collector.config.SourceConfig
import collector.config.SourcesFile
import collector.dev.FileHistorySearch
import collector.dev.FileCostLedger
import collector.dev.JsonFileSeenStore
import collector.dev.JsonFileTrackedStore
import collector.dev.outputJson
import collector.dev.publishRun
import collector.report.HtmlRenderer
import collector.item
import collector.llm.*
import collector.model.*
import collector.profile.ProfileLoader
import collector.prompt.FilePromptStore
import collector.prompt.PromptResolver
import collector.source.SourceAdapter
import collector.source.GitHubIssuesAdapter
import collector.source.GitHubAdapter
import collector.source.GitHubSearch
import collector.stage.HistoryHit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.*

class TrackingPipelineTest {
    @TempDir lateinit var directory: Path
    private val day = LocalDate.of(2026, 11, 19)
    private val idea = TrackedIdea("tracking", "card", "Local runtime", "dev-tools", listOf("runtime"),
        emptyList(), TrackedIdea.Status.TRACKING, NOW.minusSeconds(120 * 86400L), null, 0.0, TrackedIdea.Trend.FLAT)

    @Test fun gitHubIssuesAreCollectedAndClassifiedAsDemandSignals() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"incomplete_results":true,"items":[
                {"id":1,"number":1,"title":"Support local inference","html_url":"https://github.com/owner/repo/issues/1",
                 "created_at":"$NOW","comments":2,"reactions":{"+1":5},"body":"Please add local inference."},
                {"id":2,"number":2,"title":"Streaming request","html_url":"https://github.com/owner/repo/issues/2",
                 "created_at":"$NOW","comments":3,"reactions":{"+1":2},"body":"Can streaming be added?"}]}"""))
            server.enqueue(MockResponse().setBody("""{"stargazers_count":50}"""))
            val fake = object : LlmProvider {
                override val id = ProviderId.ANTHROPIC
                override suspend fun listModels() = emptyList<ModelInfo>()
                override suspend fun complete(req: LlmRequest): LlmResult {
                    assertEquals("classify", req.schemaName)
                    val input = Json.parseToJsonElement(req.user).jsonObject.getValue("items").jsonArray.single().jsonObject
                    assertEquals("GitHub 이슈", input.getValue("source").jsonPrimitive.content)
                    assertEquals("github-issues:1", input.getValue("itemId").jsonPrimitive.content)
                    assertEquals(listOf("demand-signal"), input.getValue("candidates").jsonArray.map { it.jsonPrimitive.content })
                    val row = ClassifiedItem("github-issues:1", "demand-signal", 8.0, "reason", "summary",
                        listOf("inference"), "local-inference", "Local inference")
                    return LlmResult("""{"items":[${Json.encodeToString(row)}]}""", id, req.model, 0, 0, 0, 0.0)
                }
            }
            server.enqueue(MockResponse().setBody("""{"items":[]}"""))
            val adapter = GitHubIssuesAdapter(OkHttpClient(), { null }, server.url("/").toString(), directory.resolve("github-repos.json").toFile(), directory.toFile())
            val config = GitHubIssuesConfig(queries = listOf("inference"), commentItems = 0,
                includeDiscussions = false, maxItems = 1)
            val result = Pipeline(listOf(adapter), JsonFileSeenStore(directory.resolve("seen.json").toFile()),
                clock = { NOW }, zone = ZoneOffset.UTC, full = full(fake)).run(SourcesFile(listOf(config)))
            assertEquals("github-issues", result.report.sources.single().source)
            assertEquals(2, result.report.sources.single().fetched)
            assertTrue(result.report.issues.any { "부분 결과" in it })
            assertEquals("github-issues:1", result.items.single().key)
            assertEquals("demand-signal", result.classified.single().classification.profile)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun dailyGitHubSearchesPrecedeTrackingWhenRemainingIsLow() = runBlocking {
        MockWebServer().use { server ->
            val http = OkHttpClient()
            var time = NOW
            val waits = mutableListOf<Pair<Int, Long>>()
            GitHubSearch.shared(http) {
                GitHubSearch({ time }, { waits += server.requestCount to it; time = time.plusMillis(it) })
            }
            repeat(3) {
                server.enqueue(MockResponse().setBody("""{"items":[]}""")
                    .setHeader("X-RateLimit-Remaining", "2")
                    .setHeader("X-RateLimit-Reset", NOW.plusSeconds(120).epochSecond))
            }
            repeat(3) { server.enqueue(MockResponse().setBody("""{"items":[]}""")) }
            val base = server.url("/").toString()
            val repositories = GitHubAdapter(http, { null }, base)
            val issues = GitHubIssuesAdapter(http, { null }, base, directory.resolve("github-repos.json").toFile(), directory.toFile())
            val tracked = JsonFileTrackedStore(directory.resolve("tracked.json").toFile())
            tracked.save(listOf(idea))
            val pipeline = Pipeline(listOf(repositories, issues), JsonFileSeenStore(directory.resolve("seen.json").toFile()),
                clock = { NOW }, full = full(tracked = tracked))
            val result = pipeline.run(SourcesFile(listOf(
                GitHubIssuesConfig(queries = listOf("ollama"), includeDiscussions = false), GitHubConfig())))
            assertTrue(result.report.failedSources.isEmpty())
            assertTrue(result.report.sources.single { it.source == "github" }.millis < 45_000)
            assertEquals(listOf(3 to 120_000L), waits)
            repeat(3) {
                val request = server.takeRequest().requestUrl!!
                assertEquals("/search/repositories", request.encodedPath)
                assertFalse("runtime" in request.queryParameter("q").orEmpty())
            }
            repeat(2) { assertEquals("/search/issues", server.takeRequest().requestUrl!!.encodedPath) }
            val tracking = server.takeRequest().requestUrl!!
            assertEquals("/search/repositories", tracking.encodedPath)
            assertContains(tracking.queryParameter("q").orEmpty(), "runtime")
        }
    }

    @Test fun monthlyCapSkipsPaidStagesAndRecordsReasonAfterCodeStages() = runTest {
        val out = directory.toFile()
        val today = LocalDate.ofInstant(NOW, ZoneOffset.UTC)
        FileCostLedger(out.resolve("cost-ledger-analysis")).reserve(today, "analysis", 0.25)
        val monthly = MonthlyBudget(out, BudgetSettings(monthlyTotalUsd = 0.50))
        val collected = item("github", rawScore = 250)
        val adapter = object : SourceAdapter {
            override val id = "github"
            override suspend fun fetch(config: SourceConfig, now: Instant) = listOf(collected)
        }
        val fake = object : LlmProvider {
            override val id = ProviderId.ANTHROPIC
            override suspend fun listModels() = emptyList<ModelInfo>()
            override suspend fun complete(req: LlmRequest): LlmResult = error("unexpected paid stage")
        }
        val result = Pipeline(listOf(adapter), JsonFileSeenStore(out.resolve("seen.json")), clock = { NOW },
            zone = ZoneOffset.UTC, full = full(fake), paidStageSkipReason = monthly.dailySkipReason(today, 0.50))
            .run(SourcesFile(listOf(GitHubConfig())))

        assertEquals(listOf(collected.key), result.items.map { it.key })
        assertEquals(listOf("collect", "filter", "dedupe", "score"), result.report.stages.map { it.stage })
        assertTrue(result.report.llmCalls.isEmpty())
        assertContains(result.report.issues.single(), "전체 월 상한")
        assertNull(MonthlyBudget(out, BudgetSettings()).dailySkipReason(today, 0.50))
    }

    @Test
    fun `추적 근거는 재분류 없이 점수 또는 댓글이 증가한 항목만 다시 인용한다`() = runTest {
        val priorItems = (1..3).map { index ->
            item("github", "$index", title = "Runtime $index").copy(commentCount = 2)
        }
        val date = day.minusDays(100).toString()
        val signals = priorItems.map {
            Signal(it, ClassifiedItem(it.key, "tech-signal", 8.0, "reason", "summary", listOf("runtime"),
                "runtime", "Runtime", listOf(idea.id)))
        }
        val priorUpdate = TrackingUpdate("prior", idea.id, date, "Previous finding", emptyList(),
            listOf("github:1", "github:2", "github:3"), 0.0, idea.trend)
        writeRun(date, priorItems, signals, listOf(priorUpdate))
        directory.resolve("track-classified.json").toFile().writeText(outputJson.encodeToString(
            signals.associate { it.item.key to TrackClassification(day.minusDays(1).toString(), it.classification) }
        ))
        val trackStore = JsonFileTrackedStore(directory.resolve("tracked.json").toFile())
        trackStore.save(listOf(idea))
        val calls = mutableListOf<LlmRequest>()
        val provider = object : LlmProvider {
            override val id = ProviderId.ANTHROPIC
            override suspend fun listModels() = emptyList<ModelInfo>()
            override suspend fun complete(req: LlmRequest): LlmResult {
                calls += req
                val response = if (req.schemaName == "track")
                    """{"summary":"New finding","changes":[
                        {"kind":"TECH_RELEASE","note":"score","itemKey":"github:1"},
                        {"kind":"TECH_RELEASE","note":"comments","itemKey":"github:2"},
                        {"kind":"TECH_RELEASE","note":"little change","itemKey":"github:3"}]}"""
                else """{"items":[]}"""
                return LlmResult(response, id, req.model, 0, 0, 0, 0.0)
            }
        }
        val adapter = object : SourceAdapter {
            override val id = "github"
            override suspend fun fetch(config: SourceConfig, now: Instant) = if (config.minRawScore == 60) listOf(
                priorItems[0].copy(rawScore = 150),
                priorItems[1].copy(commentCount = 3),
                priorItems[2].copy(rawScore = 149)
            ) else emptyList()
        }
        val pipeline = Pipeline(listOf(adapter), JsonFileSeenStore(directory.resolve("seen.json").toFile()),
            clock = { NOW }, zone = ZoneOffset.UTC, full = full(provider, trackStore))

        val result = pipeline.run(SourcesFile(listOf(GitHubConfig())))

        assertEquals(listOf("track"), calls.map { it.schemaName })
        val request = Json.parseToJsonElement(calls.single().user).jsonObject
        assertEquals(listOf("github:1", "github:2"), request.getValue("items").jsonArray
            .map { it.jsonObject.getValue("itemId").jsonPrimitive.content })
        assertEquals("Previous finding", request.getValue("previous").jsonArray.single()
            .jsonObject.getValue("summary").jsonPrimitive.content)
        assertEquals(listOf("github:1", "github:2"), result.trackingUpdates.single().itemKeys)
    }

    @Test
    fun `GitHub 추적 검색은 순차 실행하고 같은 URL의 힌트를 합친다`() = runTest {
        var active = 0
        var maximum = 0
        val adapter = object : SourceAdapter {
            override val id = "github"
            override suspend fun fetch(config: SourceConfig, now: Instant): List<Item> {
                active++
                maximum = maxOf(maximum, active)
                delay(5)
                active--
                return listOf(item("github"))
            }
        }
        val pipeline = full()

        val (results, issues) = pipeline.search(listOf(idea, idea.copy(id = "another")), listOf(GitHubConfig()),
            mapOf("github" to adapter), NOW, emptyList())

        assertEquals(1, maximum)
        assertEquals(listOf("tracking", "another"), results.single().trackHint)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `파일 이력 검색은 날짜 범위 안의 신호 태그를 읽고 중복 항목은 한 번만 반환한다`() {
        val previous = item(id = "11", title = "Previous release", snippet = "Prior detail")
        val signal = Signal(previous, ClassifiedItem(previous.key, "tech-signal", 8.0, "reason", "summary",
            listOf("runtime"), "runtime", "Runtime"))
        writeRun(day.minusDays(1).toString(), listOf(previous), listOf(signal))
        writeRun(day.minusDays(2).toString(), listOf(previous), listOf(signal))
        writeRun(day.toString(), listOf(item(id = "12", title = "Runtime")), emptyList())
        writeRun(day.minusDays(4).toString(), listOf(item(id = "13", title = "Runtime")), emptyList())
        val history = FileHistorySearch(directory.toFile(), 3)
        val query = signal.copy(item = item(id = "18", title = "Local runtime"))

        assertEquals(listOf(HistoryHit(day.minusDays(1).toString(), "Previous release", "Prior detail", "hn:11")),
            history.search(query, day))
    }

    @Test
    fun cachesIrrelevantTrackingSearchResultsAndExpiresOldEntries() = runTest {
        val hit = item("github", "irrelevant").copy(trackHint = listOf(idea.id))
        val row = ClassifiedItem(hit.key, "none", null, "unrelated", "", emptyList(), "", "")
        val requests = mutableListOf<List<String>>()
        val newIdea = idea.copy(id = "new")
        val provider = object : LlmProvider {
            override val id = ProviderId.ANTHROPIC
            override suspend fun listModels() = emptyList<ModelInfo>()
            override suspend fun complete(req: LlmRequest): LlmResult {
                if (req.schemaName == "track") {
                    return LlmResult("""{"summary":"No change","changes":[]}""", id, req.model, 0, 0, 0, 0.0)
                }
                assertEquals("classify", req.schemaName)
                val tracked = Json.parseToJsonElement(req.user).jsonObject.getValue("tracked").jsonArray
                    .map { it.jsonObject.getValue("trackedId").jsonPrimitive.content }
                requests += tracked
                val response = if (tracked == listOf(newIdea.id)) row.copy(reason = "changed", trackedIdeaIds = tracked) else row
                val output = buildJsonObject { put("items", Json.encodeToJsonElement(listOf(response))) }
                return LlmResult(output.toString(), id, req.model, 0, 0, 0, 0.0)
            }
        }
        suspend fun run(date: LocalDate, hits: List<Item>, active: List<TrackedIdea> = listOf(idea),
                        cards: List<Item> = emptyList()): PipelineResult {
            val at = date.atStartOfDay().toInstant(ZoneOffset.UTC)
            val report = RunReport(date.toString(), at.toString(), emptyList(), emptyList(), emptyMap(), 0)
            val result = full(provider).run(PipelineResult(cards, emptyList(), report), emptyList(),
                hits, active, at, date) {}
            publishRun(directory.toFile(), FileHistorySearch(directory.toFile(), 90), HtmlRenderer(emptyList()), result)
            return result
        }
        assertEquals(row, run(day, listOf(hit)).classified.single().classification)
        val reused = run(day.plusDays(14), listOf(hit), listOf(idea, newIdea))
        assertEquals(row.copy(trackedIdeaIds = listOf(newIdea.id)), reused.classified.single().classification)
        assertEquals(DropReason.NOT_RELEVANT, reused.dropped.single().reason)
        assertEquals(row, reused.trackClassified.getValue(hit.key).classification)
        run(day.plusDays(14), listOf(hit), listOf(idea, newIdea))
        assertEquals(listOf(listOf(idea.id), listOf(newIdea.id)), requests)
        assertTrue(hit.key in run(day.plusDays(30), emptyList()).trackClassified)
        assertTrue(run(day.plusDays(31), emptyList()).trackClassified.isEmpty())
        run(day, listOf(hit))
        run(day.plusDays(15), listOf(hit))
        run(day.plusDays(15), listOf(hit), cards = listOf(hit))
        assertEquals(5, requests.size)
    }

    private fun writeRun(date: String, items: List<Item>, signals: List<Signal>, updates: List<TrackingUpdate> = emptyList()) {
        val report = RunReport(date, "${date}T00:00:00Z", emptyList(), emptyList(), emptyMap(), 0)
        val run = PipelineResult(items, emptyList(), report, classified = signals, trackingUpdates = updates)
        directory.resolve("$date.json").toFile().writeText(outputJson.encodeToString(PipelineResult.serializer(), run))
    }

    private fun full(provider: LlmProvider? = null,
                     tracked: JsonFileTrackedStore = JsonFileTrackedStore(directory.resolve("tracked.json").toFile())): FullPipeline {
        val costs = CostGuard(0.5)
        val config = PipelineConfig.defaults
        return FullPipeline(config, ProfileLoader.loadDefaults(), PromptResolver(FilePromptStore(directory)),
            LlmRouter(listOfNotNull(provider), costs), costs, OkHttpClient(), FileHistorySearch(directory.toFile(), 90), tracked)
    }
}

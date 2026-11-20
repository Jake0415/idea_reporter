package collector.dev

import collector.config.GitHubConfig
import collector.config.PipelineConfig
import collector.config.SourceConfig
import collector.config.SourcesFile
import collector.item
import collector.llm.*
import collector.mail.Mailer
import collector.model.*
import collector.pipeline.FullPipeline
import collector.pipeline.Pipeline
import collector.pipeline.PipelineResult
import collector.profile.ProfileLoader
import collector.prompt.FilePromptStore
import collector.prompt.PromptResolver
import collector.report.HtmlRenderer
import collector.source.SourceAdapter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.*

class RunPersistenceTest {
    @TempDir lateinit var out: File
    private val now = Instant.parse("2026-11-19T12:00:00Z")
    private val day = LocalDate.parse("2026-11-19")
    private val config = PipelineConfig.defaults.copy(classifyBatchSize = 1)
    private val first = item("github", "one", "fast inference engine", rawScore = 1000, publishedAt = now)
    private val second = item("github", "two", "vector index library", rawScore = 900, publishedAt = now)
    private val card = IdeaCard(title = "카드", oneLiner = "한 줄", whyToday = "오늘", problem = "문제",
        techSignals = listOf(SignalRef(first.key, first.url, "기술")), demandSignals = emptyList(),
        competitors = listOf(SignalRef(second.key, second.url, "경쟁")), history = emptyList(),
        effort = "2주", differentiation = "차별화", biggestRisk = "위험", validationPlan = "검증",
        caveats = emptyList(), category = "dev-tools", tags = listOf("ai"))

    @Test fun scheduledEntryPointDoesNotMailPreservedCards() = runTest {
        val clock = Clock.fixed(now, java.time.ZoneId.systemDefault())
        val key = LocalDate.now(clock).toString()
        val prior = PipelineResult(emptyList(), emptyList(), RunReport(key, now.minusSeconds(60).toString(),
            emptyList(), emptyList(), emptyMap(), 0), cards = listOf(card))
        publishRun(out, FileHistorySearch(out, 30), HtmlRenderer(emptyList()), prior)
        val before = out.resolve("$key.txt").readText()
        var smtpCalls = 0
        runMain(arrayOf("--scheduled"), out, SourcesFile(emptyList()) to emptyList(),
            scheduledClock = clock, scheduledMailer = { Mailer { smtpCalls++ } }) {
            listOf(agent { error("빈 출처에서는 공급자를 호출하지 않아야 합니다.") })
        }
        assertEquals(0, smtpCalls)
        assertEquals(before, out.resolve("$key.txt").readText())
        assertContains(out.resolve("logs/$key.log").readText(), "일일 요약 · 카드 0개")
    }

    @Test fun replacesAndArchivesSameDayThenKeepsCardsWhenRerunIsEmpty() = runTest {
        val renderer = HtmlRenderer(emptyList())
        val seen = JsonFileSeenStore(out.resolve("seen.json"))
        suspend fun run(offset: Long, newCards: List<IdeaCard>): PipelineResult = withRunLock(out) {
            val history = FileHistorySearch(out, config.historyDays)
            val result = pipeline(now.plusSeconds(offset), history, seen, listOf(first, second), agent { req ->
                if (req.schemaName == "generate") {
                    """{"cards":${Json.encodeToJsonElement(newCards)}}""" to 0.001
                } else classify(req) to 0.0
            }).run(sources())
            publishRun(out, history, renderer, result)
            result
        }
        val original = run(0, listOf(card))
        assertEquals("카드", original.cards.single().title)
        val replacement = run(1, listOf(card.copy(title = "새 카드")))
        assertEquals("새 카드", current().cards.single().title)
        assertEquals(original.report.startedAt, archived().single().report.startedAt)
        val empty = run(2, emptyList())
        assertTrue(empty.cards.isEmpty())
        assertEquals(replacement.report.startedAt, current().report.startedAt)
        assertContains(out.resolve("$day.txt").readText(), "새 카드")
        assertContains(out.resolve("$day.html").readText(), "새 카드")
        assertEquals(setOf(original.report.startedAt, empty.report.startedAt), archived().map { it.report.startedAt }.toSet())
    }

    @Test fun excludesTrackingOnlyAndFailedClassificationFromSeenStore() = runTest {
        val invalid = item("github", "bad", "invalid labels", rawScore = 800, publishedAt = now)
        val blocked = item("github", "blocked", "blocked forecast", rawScore = 700, publishedAt = now)
        val tracking = item("github", "only", "tracking only", rawScore = 100, publishedAt = now)
        val seen = JsonFileSeenStore(out.resolve("seen.json"))
        val tracked = JsonFileTrackedStore(out.resolve("tracked.json"))
        tracked.save(listOf(TrackedIdea("idea", "card", "추적", "dev-tools", listOf("tracking"), emptyList(),
            TrackedIdea.Status.TRACKING, now.minusSeconds(86_400), null, 0.0, TrackedIdea.Trend.FLAT)))
        val normal = listOf(first, invalid, blocked)
        val result = pipeline(now, FileHistorySearch(out, config.historyDays), seen, normal, agent { req ->
            assertEquals("classify", req.schemaName)
            if (idOf(req) == invalid.key) """{"items":[]}""" to 0.5 else classify(req) to 0.0
        }, tracking = listOf(tracking), budget = 0.5).run(sources())
        assertEquals(setOf(tracking.key), result.trackingOnlyKeys)
        assertEquals(DropReason.BUDGET_EXCEEDED, result.dropped.first { it.item.key == invalid.key }.reason)
        assertEquals(DropReason.BUDGET_EXCEEDED, result.dropped.first { it.item.key == blocked.key }.reason)
        assertEquals(result.dropped.count { it.reason == DropReason.BUDGET_EXCEEDED },
            result.report.dropCounts[DropReason.BUDGET_EXCEEDED])
        seen.markSeen(itemsToMarkSeen(result), day)
        assertEquals(listOf(invalid, blocked, tracking), seen.filterUnseen(normal + tracking, day.plusDays(1)))
    }

    @Test fun profileMetricsCountOnlyClassifiedCardInputs() = runTest {
        val tracking = item("github", "only", "tracking only", rawScore = 100, publishedAt = now)
        val tracked = JsonFileTrackedStore(out.resolve("tracked.json"))
        tracked.save(listOf(TrackedIdea("idea", "card", "추적", "dev-tools", listOf("tracking"), emptyList(),
            TrackedIdea.Status.TRACKING, now.minusSeconds(86_400), null, 0.0, TrackedIdea.Trend.FLAT)))
        val result = pipeline(now, FileHistorySearch(out, config.historyDays), JsonFileSeenStore(out.resolve("seen.json")),
            listOf(first), agent { req ->
                (if (req.schemaName == "generate") """{"cards":[]}""" else classify(req)) to 0.0
            }, tracking = listOf(tracking)).run(sources())

        assertEquals(setOf(tracking.key), result.trackingOnlyKeys)
        assertEquals(setOf(first.key, tracking.key), result.classified.map { it.item.key }.toSet())
        assertEquals(1, result.metrics.profiles.getValue("tech-signal").classified)
    }

    @Test fun conflictingLockIsRejectedAndReleasedAfterException() = runTest {
        assertFailsWith<IllegalStateException> {
            withRunLock(out) {
                assertFailsWith<RunLockedException> { withRunLock(out) { fail("중복 진입") } }
                error("잠금을 잡은 작업의 실패")
            }
        }
        assertEquals("해제", withRunLock(out) { "해제" })
    }

    @Test fun restoresEarlierOutputFilesWhenPublicationFailsPartway() = runTest {
        val seen = JsonFileSeenStore(out.resolve("seen.json"))
        val renderer = HtmlRenderer(emptyList())
        val provider = agent { req ->
            if (req.schemaName == "generate") """{"cards":${Json.encodeToJsonElement(listOf(card))}}""" to 0.0
            else classify(req) to 0.0
        }
        val originalHistory = FileHistorySearch(out, config.historyDays)
        val original = pipeline(now, originalHistory, seen, listOf(first, second), provider).run(sources())
        assertNotNull(publishRun(out, originalHistory, renderer, original))
        val html = out.resolve("$day.html")
        val previousHtml = html.readText()
        val text = out.resolve("$day.txt")
        assertTrue(text.delete())
        assertTrue(text.mkdir())
        val replacement = original.copy(cards = listOf(card.copy(id = "failed-card", title = "Runtime replacement")), report = original.report.copy(
            startedAt = now.plusSeconds(1).toString()))
        assertFailsWith<java.io.IOException> {
            publishOrArchiveFailure(out, FileHistorySearch(out, config.historyDays), renderer, replacement, false)
        }
        assertEquals(original.report.startedAt, current().report.startedAt)
        assertEquals(previousHtml, html.readText())
        assertTrue(archived().isEmpty())
        val failed = outputJson.decodeFromString<PipelineResult>(out.resolve("runs/failed").listFiles()!!.single().readText())
        assertEquals(replacement.cards, failed.cards)
        val reopened = FileHistorySearch(out, 30)
        assertNull(reopened.cardRun(replacement.cards.single().id))
        val feedback = Feedback(replacement.cards.single().id, Feedback.Kind.STAR)
        out.resolve("feedback.json").writeText(outputJson.encodeToString(listOf(feedback)))
        assertTrue(reopened.feedback().isEmpty())
        assertTrue(reopened.search(replacement.classified.first().let { it.copy(item = it.item.copy(title = "Runtime replacement")) },
            day.plusDays(1)).none { it.id == replacement.cards.single().id })
    }

    @Test fun keepsTrackingStateWhenEmptyRunPreservesCards() = runTest {
        val renderer = HtmlRenderer(emptyList())
        val original = PipelineResult(emptyList(), emptyList(), RunReport(day.toString(), now.toString(),
            emptyList(), emptyList(), emptyMap(), 0), cards = listOf(card))
        publishRun(out, FileHistorySearch(out, 30), renderer, original)
        val tracked = TrackedIdea("tracked", "card", "제목", "dev-tools", emptyList(), emptyList(),
            TrackedIdea.Status.TRACKING, now, now.plusSeconds(1), 0.5, TrackedIdea.Trend.UP)
        val update = TrackingUpdate("update", tracked.id, day.toString(), "변화", emptyList(),
            listOf(first.key), 0.5, TrackedIdea.Trend.UP)
        val topic = TopicHistory("topic", "주제", day.toString(), listOf(first.key))
        val metric = MetricSnapshot(first.key, first.rawScore, now.plusSeconds(1))
        val profileMetrics = mapOf("tech-signal" to ProfileMetrics(3, 2, 1))
        val empty = original.copy(cards = emptyList(), tracked = listOf(tracked), trackingUpdates = listOf(update),
            topics = listOf(topic), metrics = RunMetrics(listOf(metric), profileMetrics),
            report = original.report.copy(startedAt = now.plusSeconds(1).toString()))
        assertNull(publishRun(out, FileHistorySearch(out, 30), renderer, empty))
        val reopened = FileHistorySearch(out, 30)
        assertEquals(listOf(card), current().cards)
        assertEquals(listOf(tracked), JsonFileTrackedStore(out.resolve("tracked.json")).load())
        assertEquals(listOf(metric), reopened.metrics(now))
        assertEquals(profileMetrics, archived().single().metrics.profiles)
        val state = reopened.stateRuns(day.plusDays(1), 1).single()
        assertEquals(listOf(update), state.trackingUpdates)
        assertEquals(listOf(topic), state.topics)
    }

    @Test fun readsLegacySnapshotArrayInRunJson() {
        val run = PipelineResult(emptyList(), emptyList(), RunReport(day.toString(), now.toString(),
            emptyList(), emptyList(), emptyMap(), 0))
        val snapshot = MetricSnapshot(first.key, first.rawScore, now)
        val legacy = JsonObject(outputJson.encodeToJsonElement(run).jsonObject +
            ("metrics" to outputJson.encodeToJsonElement(listOf(snapshot))))
        val decoded = outputJson.decodeFromJsonElement<PipelineResult>(legacy)
        assertEquals(listOf(snapshot), decoded.metrics.snapshots)
    }

    @Test fun restoresReportsAndMetricsWhenTrackedSaveFails() = runTest {
        val renderer = HtmlRenderer(emptyList())
        val original = PipelineResult(emptyList(), emptyList(), RunReport(day.toString(), now.toString(),
            emptyList(), emptyList(), emptyMap(), 0), cards = listOf(card),
            metrics = RunMetrics(snapshots = listOf(MetricSnapshot(first.key, 10, now))))
        publishRun(out, FileHistorySearch(out, 30), renderer, original)
        val names = listOf("$day.json", "$day.html", "$day.txt", "metrics.json", "seen.json", "state/$day.json", "track-classified.json")
        val saved = names.associateWith { out.resolve(it).takeIf(File::isFile)?.readText() }
        val tracked = out.resolve("tracked.json")
        if (tracked.isFile) assertTrue(tracked.delete())
        assertTrue(tracked.mkdir())
        val replacement = original.copy(cards = listOf(card.copy(title = "교체")),
            metrics = RunMetrics(snapshots = listOf(MetricSnapshot(first.key, 20, now.plusSeconds(1)))),
            report = original.report.copy(startedAt = now.plusSeconds(1).toString()))
        assertFailsWith<java.io.IOException> {
            publishRun(out, FileHistorySearch(out, 30), renderer, replacement)
        }
        assertEquals(saved, names.associateWith { out.resolve(it).takeIf(File::isFile)?.readText() })
        assertTrue(archived().isEmpty())
    }

    @Test fun restoresOutstandingReservationsAndSettlesKnownCosts() = runTest {
        val directory = out.resolve("cost-ledger")
        val ledger = FileCostLedger(directory) { 0.02 }
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val firstGuard = CostGuard(0.1, clock, ledger = ledger)
        val settled = firstGuard.reserve(0.03)
        val unsettled = firstGuard.reserve(0.04)
        val saved = FileCostLedger(directory)
        assertEquals(0.09, saved.total(day), 1e-12)
        firstGuard.record(LlmCall("classify", ProviderId.ANTHROPIC, "fake", "classify", "default",
            costUsd = 0.005), settled)
        assertEquals(0.065, saved.total(day), 1e-12)
        val restored = CostGuard(0.1, clock, ledger = saved)
        assertFailsWith<BudgetExceededException> { restored.reserve(0.04) }
        restored.record(LlmCall("track", ProviderId.ANTHROPIC, "fake", "track", "default",
            error = "timeout"), unsettled, settled = false)
        assertEquals(0.065, saved.total(day), 1e-12)
        assertEquals(0.0, saved.total(day.plusDays(1)))
    }

    private fun current() = outputJson.decodeFromString<PipelineResult>(out.resolve("$day.json").readText())
    private fun archived() = out.resolve("runs").listFiles().orEmpty().filter(File::isFile).map {
        outputJson.decodeFromString<PipelineResult>(it.readText())
    }

    private fun sources() = SourcesFile(listOf(GitHubConfig(minRawScore = 200)))

    private fun agent(respond: (LlmRequest) -> Pair<String, Double>) = object : LlmProvider {
        override val id = ProviderId.ANTHROPIC
        override suspend fun listModels() = emptyList<ModelInfo>()
        override suspend fun complete(req: LlmRequest): LlmResult {
            val (json, cost) = respond(req)
            return LlmResult(json, id, req.model, 0, 0, 0, cost)
        }
    }

    private fun idOf(req: LlmRequest) = Json.parseToJsonElement(req.user).jsonObject.getValue("items").jsonArray
        .single().jsonObject.getValue("itemId").jsonPrimitive.content

    private fun classify(req: LlmRequest): String {
        val id = idOf(req)
        val row = ClassifiedItem(id, "tech-signal", 8.0, "이유", "요약", listOf("tag-${id.substringAfter(':')}"),
            "topic-${id.substringAfter(':')}", "주제")
        return """{"items":${Json.encodeToJsonElement(listOf(row))}}"""
    }

    private fun pipeline(at: Instant, history: FileHistorySearch, seen: JsonFileSeenStore, regular: List<Item>,
                         provider: LlmProvider, tracking: List<Item> = emptyList(), budget: Double = 1.0): Pipeline {
        val adapter = object : SourceAdapter {
            override val id = "github"
            override suspend fun fetch(config: SourceConfig, now: Instant): List<Item> =
                if ((config as GitHubConfig).minRawScore == 200) regular else tracking
        }
        val costs = CostGuard(budget)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(404).message("Not Found").body("".toResponseBody()).build()
        }.build()
        val full = FullPipeline(config, ProfileLoader.loadDefaults(), PromptResolver(FilePromptStore(out.toPath())),
            LlmRouter(listOf(provider), costs), costs, http, history, JsonFileTrackedStore(out.resolve("tracked.json")))
        return Pipeline(listOf(adapter), seen, clock = { at }, zone = ZoneOffset.UTC, full = full)
    }
}

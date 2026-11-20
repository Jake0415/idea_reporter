package collector.analysis

import collector.budget.BudgetSettings
import collector.budget.OverBudget
import collector.dev.FileCostLedger
import collector.dev.outputJson
import collector.dev.readJson
import collector.dev.writeAtomically
import collector.llm.*
import collector.model.*
import collector.pipeline.PipelineResult
import collector.prompt.FilePromptStore
import collector.prompt.PromptResolver
import collector.trend.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.*

class AnalysisTest {
    @TempDir lateinit var out: File

    private val config = AnalysisConfig()
    private val date = LocalDate.parse("2026-11-19")
    private val now = Instant.parse("2026-11-20T01:23:45Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val report = TrendReport(date.toString(), "2026-10-23", false, 20,
        themes = listOf(ThemeRow("local-doc", name = "로컬 문서"), ThemeRow("other")))
    private val demand = signal(1, "hn", "demand-signal")
    private val demand2 = signal(2, "reddit", "demand-signal")
    private val repo = signal(3, "github", "tech-signal")
    private val score = OpportunityScore(4.0, "평가 근거")
    private val opportunity = Opportunity("local-doc", "<script>alert(1)</script>", "기업", "수작업 검토",
        "수요 증가", "팀장", listOf(ref(repo)), listOf(ref(demand), ref(demand2)), listOf(ref(repo)), emptyList(),
        scoreLabels.keys.associateWith { score }, false, "", "유료 고객 확인", emptyList())

    @Test fun oneTimeCapSettlementBlocksUntilConfiguredCapChanges() = runTest {
        seed()
        val lines = mutableListOf<String>()
        val fake = Fake(ProviderId.OPENAI) { req ->
            LlmResult(Json.encodeToString(OpportunityOutput(listOf(opportunity))), ProviderId.OPENAI,
                req.model, 100, 50, 0, 0.60)
        }
        val command = AnalysisCommand(out, config, PromptResolver(FilePromptStore(out.resolve("prompts").toPath())),
            { fake }, clock, lines::add)
        command.run(arrayOf("--analyze", "local-doc", "--budget", "0.40", "--yes"))
        command.run(arrayOf("--analyze", "local-doc", "--yes"))
        assertEquals(1, fake.requests.size)
        val day = LocalDate.now(clock)
        val status = collector.budget.MonthlyBudget(out, BudgetSettings.load(out)).status(day).joinToString()
        assertContains(status, "USD 0.400000")
        assertContains(status, "--set-budget")
        collector.dev.runMain(arrayOf("--set-budget", "warnAtPercent=70"), out)
        command.run(arrayOf("--analyze", "local-doc", "--yes"))
        assertEquals(1, fake.requests.size)
        collector.dev.runMain(arrayOf("--set-budget", "analysisRequestUsd=1.10"), out)
        command.run(arrayOf("--analyze", "local-doc", "--yes"))
        assertEquals(2, fake.requests.size)
        collector.dev.runMain(arrayOf("--set-budget", "analysisRequestUsd=1.00"), out)
        command.run(arrayOf("--analyze", "local-doc", "--yes", "--force"))
        assertEquals(3, fake.requests.size)
    }

    @Test fun analysisBlockExpiresOnFirstDayOfNextMonthWithoutSettingsChange() = runTest {
        seed()
        val fake = Fake(ProviderId.OPENAI) { req ->
            LlmResult(Json.encodeToString(OpportunityOutput(listOf(opportunity))), ProviderId.OPENAI,
                req.model, 100, 50, 0, 0.60)
        }
        val prompts = PromptResolver(FilePromptStore(out.resolve("prompts").toPath()))
        val lastDay = Clock.fixed(Instant.parse("2026-11-30T23:59:59Z"), ZoneOffset.UTC)
        val command = AnalysisCommand(out, config, prompts, { fake }, lastDay)
        command.run(arrayOf("--analyze", "local-doc", "--budget", "0.40", "--yes"))
        val args = arrayOf("--analyze", "local-doc", "--yes")
        command.run(args)
        assertEquals(1, fake.requests.size)
        val firstDay = Clock.fixed(lastDay.instant().plusSeconds(1), ZoneOffset.UTC)
        AnalysisCommand(out, config, prompts, { fake }, firstDay).run(args)
        assertEquals(2, fake.requests.size)
    }

    @Test fun sameDateReportWithChangedEvidenceUrlIsNotReused() = runTest {
        seed()
        val fake = Fake(ProviderId.OPENAI) { req ->
            LlmResult(Json.encodeToString(OpportunityOutput(listOf(opportunity))), ProviderId.OPENAI,
                req.model, 100, 50, 0, 0.001)
        }
        val command = command(mutableListOf()) { fake }
        val args = arrayOf("--analyze", "local-doc", "--yes")
        val evidence = TrendEvidence(demand.item.key, demand.item.title, demand.item.url, "hn", 10.0)
        val trend = report.copy(themes = listOf(report.themes.first().copy(evidence = mapOf(
            "demand-signal" to listOf(evidence)))))
        val reportFile = out.resolve("trend/$date.json")
        reportFile.writeAtomically(outputJson.encodeToString(trend))
        repeat(2) { command.run(args) }
        assertEquals(1, fake.requests.size)
        val changedUrl = "https://example.test/updated"
        val updated = trend.copy(themes = listOf(trend.themes.single().copy(evidence = mapOf(
            "demand-signal" to listOf(evidence.copy(url = changedUrl))))))
        reportFile.writeAtomically(outputJson.encodeToString(updated))
        command.run(args)
        assertEquals(2, fake.requests.size)
        assertContains(fake.requests.last().user, changedUrl)
    }

    @Test fun changedJudgmentThresholdInvalidatesReuse() = runTest {
        seed()
        val fake = Fake(ProviderId.OPENAI) { req ->
            LlmResult(Json.encodeToString(OpportunityOutput(listOf(opportunity))), ProviderId.OPENAI,
                req.model, 100, 50, 0, 0.001)
        }
        val args = arrayOf("--analyze", "local-doc", "--yes")
        val reports = AnalysisReports(out.resolve("analysis"))
        command(mutableListOf()) { fake }.run(args)
        assertEquals(OpportunityDecision.REQUEST_CANDIDATE, reports.list().single().opportunities.single().decision)

        val updated = command(mutableListOf(), config.copy(threshold = 4.5)) { fake }
        repeat(2) { updated.run(args) }
        assertEquals(2, fake.requests.size)
        assertEquals(setOf(OpportunityDecision.REQUEST_CANDIDATE, OpportunityDecision.NOT_VIABLE),
            reports.list().map { it.opportunities.single().decision }.toSet())
    }

    @Test fun invalidResultsAreNotReused() = runTest {
        seed()
        val fake = Fake(ProviderId.OPENAI) { req ->
            LlmResult(Json.encodeToString(OpportunityOutput(listOf(opportunity.copy(scores = emptyMap())))),
                ProviderId.OPENAI, req.model, 100, 50, 0, 0.001)
        }
        val command = command(mutableListOf()) { fake }
        repeat(2) { command.run(arrayOf("--analyze", "local-doc", "--yes")) }
        assertEquals(2, fake.requests.size)
    }

    @Test fun promptChangesInvalidateReuse() = runTest {
        seed()
        val store = FilePromptStore(out.resolve("prompts").toPath())
        val fake = Fake(ProviderId.OPENAI) { req ->
            LlmResult(Json.encodeToString(OpportunityOutput(listOf(opportunity))),
                ProviderId.OPENAI, req.model, 100, 50, 0, 0.001)
        }
        val command = command(mutableListOf()) { fake }
        val args = arrayOf("--analyze", "local-doc", "--yes")
        command.run(args)
        store.save("criteria", "수정한 사업성 기준")
        command.run(args)
        store.save("analysis-opportunity", "수정한 분석 프롬프트")
        command.run(args)
        command.run(args)
        assertEquals(3, fake.requests.size)
    }

    @Test fun reportUrlIsAuthoritativeAndMissingDetailsKeepReportEvidence() {
        seed()
        val url = "https://example.test/report"
        val refs = listOf(TrendEvidence(demand.item.key, "리포트 제목", url, "hn", 10.0),
            TrendEvidence("reddit:missing", "상세 없음", "$url/missing", "reddit", 8.0))
        val trend = report.copy(themes = listOf(ThemeRow("local-doc", evidence = mapOf("demand-signal" to refs))))
        val input = AnalysisInputBuilder(config).build(trend, listOf("local-doc"), TrendInput.read(out, date), "기준")
        val (linked, missing) = input.evidence.getValue("local-doc")
        assertEquals(url, linked.url)
        assertTrue(linked.body.isNotEmpty())
        assertEquals("상세 없음", missing.title)
        assertTrue(missing.body.isBlank() && missing.comments.isEmpty())
        assertTrue(missing.detailsMissing)
        assertEquals(1, input.reportUrlMismatches)
        val judged = OpportunityJudge(config).judge(listOf(opportunity.copy(demandRefs = listOf(
            ref(demand), ref(demand).copy(url = url)
        ))), input).single()
        assertEquals(listOf(url), judged.validated.demandRefs.map { it.url })
    }

    @Test fun equalReservationsAreOmittedWhenThemeHasFewEvidenceItems() = runTest {
        seed()
        val lines = mutableListOf<String>()
        command(lines, settings = BudgetSettings(analysisRequestUsd = 0.30)) { error("preview only") }
            .run(arrayOf("--analyze", "local-doc"))
        assertEquals(listOf("맞춤안 3"), lines.filter { it.startsWith("맞춤안 ") }.map { it.substringBefore(" ·") })
    }

    @Test fun oversizedBaselineCanFitByReducingThemesAndReportsWhenNoneFit() = runTest {
        seed(emptyList())
        val prompts = PromptResolver(FilePromptStore(out.resolve("prompts").toPath()))
        val criteria = prompts.resolve("criteria").text
        val singleSize = AnalysisInputBuilder(config).build(report, listOf("local-doc"),
            TrendInput.read(out, date), criteria).json.toString().length
        val lines = mutableListOf<String>()
        val fake = Fake(ProviderId.OPENAI) { req ->
            LlmResult(Json.encodeToString(OpportunityOutput(listOf(opportunity))), ProviderId.OPENAI,
                req.model, 100, 50, 0, 0.001)
        }
        val settings = BudgetSettings(overBudget = OverBudget.AUTO_FIT)
        val args = arrayOf("--analyze", "local-doc", "other", "--yes")
        command(lines, config.copy(maxInputChars = singleSize - 1), settings) { fake }.run(args)
        assertTrue(fake.requests.isEmpty())
        assertContains(lines.last(), "입력 상한 초과")
        assertContains(lines.last(), "--set-budget")

        lines.clear()
        command(lines, config.copy(maxInputChars = singleSize), settings) { fake }.run(args)
        assertContains(lines, "기본 구성 · 입력 상한 초과")
        assertEquals(16_000, fake.requests.single().maxOutputTokens)
        assertEquals(listOf("local-doc"), AnalysisReports(out.resolve("analysis")).list().single().themes)
    }

    @Test fun legacyLocalBudgetFieldsAreIgnoredWithOneWarning() {
        val file = out.resolve("analysis.local.json")
        file.writeText("""{"requestBudgetUsd":"invalid","monthlyBudgetUsd":-1,"maxOutputTokens":24000}""")
        val lines = mutableListOf<String>()
        val loaded = AnalysisConfig.load(file, config, lines::add)
        assertEquals(config.copy(maxOutputTokens = 24_000), loaded)
        assertContains(lines.single(), "budget.json 사용")
    }

    @Test fun solPriceAndLongContext() {
        val catalog = ModelCatalog.defaults
        val price = assertNotNull(catalog.find(ProviderId.OPENAI, "gpt-6.2-sol"))
        assertTrue(price.priceVerified)
        assertEquals(1202, analysisInputTokens("한글x"))
        assertEquals(0.593998, catalog.analysisCostUsd(config.model, 216999, 16000), 1e-9)
        assertEquals(1.108, catalog.analysisCostUsd(config.model, 217000, 16000), 1e-9)
        assertEquals(0.704, catalog.costUsd(ProviderId.OPENAI, price.model, 272000, 16000, 0), 0.000001)
        assertEquals(1.328004, catalog.costUsd(ProviderId.OPENAI, price.model, 272001, 16000, 0), 0.000001)
        assertEquals(1.327624, catalog.costUsd(ProviderId.OPENAI, price.model, 272001, 16000, 100), 0.000001)
    }

    @Test fun openAiSendsSolHighAndStrictOpportunitySchema() = runTest {
        val opportunitySchema = opportunitySchema(listOf("local-doc", "other"))
        MockWebServer().use { server ->
            OpenAiProvider("mock", server.url("/v1").toString()).use { provider ->
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"id":"chat_1","object":"chat.completion","created":1,"model":"gpt-6.2-sol",
                    "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"{\"opportunities\":[]}"}}],
                    "usage":{"prompt_tokens":100,"completion_tokens":5,"total_tokens":105}}"""
                ))
                val result = provider.complete(LlmRequest(config.model.model, "system", "{}", opportunitySchema,
                    "analysis", config.maxOutputTokens, config.effort))
                assertEquals(0.00025, result.costUsd, 1e-10)
                val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                assertEquals(JsonPrimitive("high"), body["reasoning_effort"])
                val schema = body.getValue("response_format").jsonObject.getValue("json_schema").jsonObject
                assertEquals(JsonPrimitive(true), schema["strict"])
                assertEquals(opportunitySchema, schema["schema"])
                val root = schema.getValue("schema").jsonObject.getValue("properties").jsonObject
                val fields = root.getValue("opportunities").jsonObject.getValue("items").jsonObject
                    .getValue("properties").jsonObject
                assertEquals(JsonArray(listOf(JsonPrimitive("local-doc"), JsonPrimitive("other"))),
                    fields.getValue("themeId").jsonObject["enum"])
            }
        }
    }

    @Test fun reportEvidenceWinsAndCommentsFillInPriorityOrder() {
        val item = demand.item.copy(comments = listOf(Comment("item", "원본 댓글")))
        val signal = demand.copy(item = item, hnContext = HnContext(topComments = listOf("h".repeat(350))),
            enrichedComments = listOf(Comment("enriched", "보강 댓글")))
        val historical = signal.copy(classification = signal.classification.copy(profile = "tech-signal", score = 100.0))
        val day = RunReport(date.toString(), now.toString(), emptyList(), emptyList(), emptyMap(), 0)
        val run = PipelineResult(emptyList(), emptyList(), day, classified = listOf(signal, historical))
        val row = report.themes.first().copy(evidence = mapOf("demand-signal" to listOf(
            TrendEvidence(item.key, item.title, item.url, item.source, 5.0))))
        val input = AnalysisInputBuilder(config).build(report.copy(themes = listOf(row)), listOf(row.id),
            TrendInput(mapOf(date to run)), "기준")
        val evidence = input.evidence.getValue(row.id).single()
        assertEquals("demand-signal", evidence.profile)
        assertEquals(5.0, evidence.score)
        assertEquals(listOf("보강 댓글", "h".repeat(300), "원본 댓글"), evidence.comments.map { it.text })
        val judged = OpportunityJudge(config).judge(listOf(opportunity.copy(themeId = row.name)), input).single()
        assertEquals(row.id, judged.validated.themeId)
        assertTrue(judged.warnings.isNotEmpty())
        assertEquals(listOf(ref(demand)), judged.validated.demandRefs)
    }

    @Test fun longContextSettlementOverRequestCapBlocksMonth() = runTest {
        seed()
        val lines = mutableListOf<String>()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(buildJsonObject {
                put("id", "chat_1")
                put("object", "chat.completion")
                put("created", 1)
                put("model", "gpt-6.2-sol")
                putJsonArray("choices") {
                    addJsonObject {
                        put("index", 0)
                        put("finish_reason", "stop")
                        putJsonObject("message") {
                            put("role", "assistant")
                            put("content", Json.encodeToString(OpportunityOutput(listOf(opportunity))))
                        }
                    }
                }
                putJsonObject("usage") {
                    put("prompt_tokens", 272001)
                    put("completion_tokens", 50)
                    put("total_tokens", 272051)
                }
            }.toString()))
            withContext(Dispatchers.IO) {
                command(lines) { OpenAiProvider("mock", server.url("/v1").toString()) }
                    .run(arrayOf("--analyze", "local-doc", "--yes"))
            }
            assertEquals(1, server.requestCount)
        }
        val run = AnalysisReports(out.resolve("analysis")).list().single()
        assertEquals(1.088754, run.actualUsd, 1e-9)
        assertEquals(1.088754, FileCostLedger(out.resolve("cost-ledger-analysis")).total(LocalDate.now(clock)), 1e-9)
        assertEquals(1, run.opportunities.size)
        assertTrue(run.issues.any { it.startsWith("예산 초과 정산 $") })
        assertTrue(lines.any { it.startsWith("예산 초과 정산 $") })
        command(lines) { error("unexpected provider") }.run(arrayOf("--analyze", "other", "--yes"))
        assertContains(lines.last(), "월 상한")
    }

    @Test fun reserveAboveNinetyPercentIsRejected() = runTest {
        seed()
        val lines = mutableListOf<String>()
        command(lines, settings = BudgetSettings(analysisRequestUsd = 0.35, overBudget = OverBudget.STOP)) {
            error("unexpected provider")
        }
            .run(arrayOf("--analyze", "local-doc", "--yes"))
        assertContains(lines.last(), "90%")
        assertFalse(out.resolve("cost-ledger-analysis").exists())
    }

    @Test fun askPrintsDecreasingReservationsAndExplicitFitUsesDifferentReuseKey() = runTest {
        val profiles = listOf("demand-signal", "tech-signal", "market-signal")
        seed((1..15).map { signal(it, "hn", profiles[(it - 1) % profiles.size]) })
        val lines = mutableListOf<String>()
        val fake = Fake(ProviderId.OPENAI) { req -> LlmResult(Json.encodeToString(OpportunityOutput(listOf(opportunity))),
            ProviderId.OPENAI, req.model, 100, 50, 0, 0.0007) }
        val narrow = command(lines, settings = BudgetSettings(analysisRequestUsd = 0.40)) { fake }
        narrow.run(arrayOf("--analyze", "local-doc", "--yes"))
        assertEquals(0, fake.requests.size)
        val reservations = lines.filter { it.startsWith("맞춤안 ") }
            .map { it.substringAfter("예약 USD ").substringBefore(" ·").toDouble() }
        assertEquals(3, reservations.size)
        assertTrue(reservations.zipWithNext().all { (before, after) -> before > after })
        assertContains(lines.last(), "--fit")

        command(lines) { fake }.run(arrayOf("--analyze", "local-doc", "--yes"))
        narrow.run(arrayOf("--analyze", "local-doc", "--fit", "2", "--yes"))
        assertEquals(2, fake.requests.size)
        val fitted = AnalysisReports(out.resolve("analysis")).list().single { it.fit?.step == 2 }
        assertEquals(6, assertNotNull(fitted.fit).evidencePerTheme)
        assertEquals(6, fitted.input.evidenceCount)
        assertContains(out.resolve("analysis/${fitted.id}.txt").readText(), "맞춤 실행: 근거 6건")
        assertContains(AnalysisReports(out.resolve("analysis")).detail(fitted.id, "1"), "맞춤 실행: 근거 6건")
        narrow.run(arrayOf("--analyze", "local-doc", "--fit", "2", "--yes"))
        assertEquals(2, fake.requests.size)
        narrow.run(arrayOf("--analyze", "local-doc", "--yes"))
        assertContains(lines.last(), "저장된 분석")
    }

    @Test fun settlementAboveReservationBelowCapStillAllowsNextRequest() = runTest {
        seed()
        val lines = mutableListOf<String>()
        val fake = Fake(ProviderId.OPENAI) { req ->
            LlmResult(Json.encodeToString(OpportunityOutput(listOf(opportunity))), ProviderId.OPENAI,
                req.model, 195_000, 1_000, 0, 0.40)
        }
        val settings = BudgetSettings(analysisRequestUsd = 0.10)
        val command = command(lines, settings = settings) { fake }
        command.run(arrayOf("--analyze", "local-doc", "--budget", "1.0", "--yes"))
        val run = AnalysisReports(out.resolve("analysis")).list().single()
        assertTrue(run.issues.any { it.startsWith("예산 초과 정산 $") })
        command.run(arrayOf("--analyze", "local-doc", "--yes"))
        assertContains(lines.last(), "요청 상한")
        assertEquals(1, fake.requests.size)
        command.run(arrayOf("--analyze", "local-doc", "--budget", "1.0", "--yes"))
        assertEquals(2, fake.requests.size)
    }

    @Test fun autoFitKeepsHighestScoringThemeAndWarnsInConsoleReportAndList() = runTest {
        seed()
        val lines = mutableListOf<String>()
        val fake = Fake(ProviderId.OPENAI) { req -> LlmResult(Json.encodeToString(OpportunityOutput(listOf(opportunity))),
            ProviderId.OPENAI, req.model, 100, 50, 0, 0.0007) }
        val settings = BudgetSettings(analysisRequestUsd = 0.25, analysisMonthlyUsd = 1.10, overBudget = OverBudget.AUTO_FIT)
        FileCostLedger(out.resolve("cost-ledger-analysis")).reserve(date, "earlier", 0.88)
        val command = command(lines, settings = settings) { fake }
        command.run(arrayOf("--analyze", "local-doc", "other"))
        assertTrue(fake.requests.isEmpty())
        command.run(arrayOf("--analyze", "local-doc", "other", "--yes"))
        assertEquals(Effort.MEDIUM, fake.requests.single().effort)
        val run = AnalysisReports(out.resolve("analysis")).list().single()
        assertEquals(listOf("local-doc"), run.fit?.themes)
        val warning = run.budgetWarnings.single()
        assertContains(warning, "이번 달 $")
        assertContains(lines, warning)
        assertContains(out.resolve("analysis/${run.id}.html").readText(), warning)
        lines.clear()
        command.run(arrayOf("--analysis-list"))
        assertContains(lines, warning)
    }

    @Test fun overridesCannotBypassLowerMonthlyCaps() = runTest {
        seed()
        val lines = mutableListOf<String>()
        val settings = BudgetSettings(analysisRequestUsd = 0.10, analysisMonthlyUsd = 0.50, monthlyTotalUsd = 1.0)
        val args = arrayOf("--analyze", "local-doc", "--yes", "--budget", "20")
        val ledger = FileCostLedger(out.resolve("cost-ledger-analysis"))
        ledger.reserve(date, "analysis", 0.55)
        command(lines, settings = settings) { error("unexpected provider") }.run(args)
        assertContains(lines.last(), "분석 월 상한")
        assertContains(lines.last(), "남은 예산 USD 0.000000")
        assertContains(lines.last(), "2026-12-01")

        FileCostLedger(out.resolve("cost-ledger")).reserve(date, "cards", 0.40)
        command(lines, settings = settings.copy(analysisMonthlyUsd = 5.0)) { error("unexpected provider") }.run(args)
        assertContains(lines.last(), "전체 월 상한")
        assertContains(lines.last(), "--set-budget")
    }

    @Test fun analysisDoesNotFallbackWhenOpenAiFails() = runTest {
        val openAi = Fake(ProviderId.OPENAI) { throw LlmServiceException(reason = "http_5xx") }
        val other = Fake(ProviderId.ANTHROPIC) { error("fallback") }
        val router = LlmRouter(listOf(openAi, other), CostGuard(1.0))
        assertFailsWith<LlmServiceException> {
            router.complete("analysis", LlmRequest("", "system", "{}", opportunitySchema(listOf("local-doc")),
                "analysis", 128, Effort.HIGH), "analysis-opportunity", "default")
        }
        assertEquals("gpt-6.2-sol", openAi.requests.single().model)
        assertEquals(Effort.HIGH, openAi.requests.single().effort)
        assertTrue(other.requests.isEmpty())
    }

    @Test fun previewAndBudgetRejectionsNeverCreateProviderOrLedger() = runTest {
        seed()
        val lines = mutableListOf<String>()
        val provider = { error("unexpected provider") }
        command(lines, provider = provider).run(arrayOf("--analyze", "local-doc"))
        assertTrue(lines.any { "비용 추정" in it })
        assertTrue(lines.any { "이번 달" in it })
        assertFalse(out.resolve("cost-ledger-analysis").exists())

        lines.clear()
        command(lines, config.copy(requestBudgetUsd = 0.10), provider = provider)
            .run(arrayOf("--analyze", "local-doc", "--yes"))
        assertTrue(lines.any { "실행 불가" in it && "요청 상한" in it })
        val ledger = FileCostLedger(out.resolve("cost-ledger-analysis"))
        ledger.reserve(date, "earlier", 4.9)
        lines.clear()
        command(lines, provider = provider).run(arrayOf("--analyze", "local-doc", "--yes"))
        assertTrue(lines.any { "실행 불가" in it && "월 상한" in it })
        assertEquals(4.9, ledger.total(date))
        assertFalse(out.resolve("analysis").exists())
    }

    @Test fun inputReadsRunDetailsAndEnforcesLimits() {
        val profiles = listOf("demand-signal", "tech-signal", "market-signal")
        val signals = profiles.flatMapIndexed { group, profile ->
            (1..7).map { signal(100 * group + it, "github", profile) }
        }
        val reportOnly = repo.copy(classification = repo.classification.copy(score = 300.0, tags = emptyList()))
        seed(signals + reportOnly)
        val evidence = TrendEvidence(repo.item.key, repo.item.title, repo.item.url, repo.item.source, 300.0)
        val trend = report.copy(themes = listOf(ThemeRow("local-doc", evidence = mapOf("tech-signal" to listOf(evidence)))))
        val input = TrendInput.read(out, date)
        val builder = AnalysisInputBuilder(config)
        assertFailsWith<IllegalArgumentException> { builder.build(trend, listOf("missing"), input, "기준") }
        val full = builder.build(trend, listOf("local-doc"), input, "기준")
        val items = full.evidence.getValue("local-doc")
        assertEquals(15, items.size)
        assertEquals(reportOnly.item.key, items.first().itemId)
        assertEquals(listOf(5, 5, 5), items.groupingBy { it.profile }.eachCount().values.sorted())
        assertEquals(600, items.first().body.length)
        assertEquals(3, items.first().comments.size)
        assertEquals(Comment("writer", "c".repeat(300)), items.first().comments.first())
        assertEquals("MIT", items.first().license)
        assertEquals("Kotlin", items.first().language)
        assertEquals(100, items.first().stars)
        val cap = full.json.toString().length - 1
        val trimmed = AnalysisInputBuilder(config.copy(maxInputChars = cap))
            .build(trend, listOf("local-doc"), input, "기준")
        assertEquals(items.dropLast(1), trimmed.evidence.getValue("local-doc"))
        assertTrue(trimmed.json.toString().length <= cap)
    }

    @Test fun inputIncludesRecentCardsFeedbackAndReportDataStatus() {
        val card = IdeaCard(id = "card-1", title = "로컬 검토", oneLiner = "", whyToday = "", problem = "",
            techSignals = emptyList(), demandSignals = emptyList(), competitors = emptyList(), history = emptyList(),
            effort = "", differentiation = "", biggestRisk = "", validationPlan = "", caveats = emptyList(),
            category = "productivity", tags = emptyList())
        val run = PipelineResult(emptyList(), emptyList(),
            RunReport(date.toString(), now.toString(), emptyList(), emptyList(), emptyMap(), 0), cards = listOf(card))
        val input = TrendInput(mapOf(date to run), feedback = listOf(
            Feedback(card.id, Feedback.Kind.STAR),
            Feedback(card.id, Feedback.Kind.NOT_INTERESTED, reason = "이미 사용 중"),
            Feedback("other-card", Feedback.Kind.STAR)
        ))
        val report = report.copy(insufficientData = true, backfillDays = 6)
        val payload = AnalysisInputBuilder(config).build(report, listOf("local-doc"), input, "내 기준").json
        assertEquals(JsonPrimitive("내 기준"), payload["criteria"])
        val recent = payload.getValue("recentCards").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(card.title), recent["title"])
        assertEquals(JsonPrimitive(card.category), recent["category"])
        val feedback = payload.getValue("feedback").jsonArray.map { it.jsonObject }
        assertEquals(listOf("STAR", "NOT_INTERESTED"), feedback.map { it.getValue("kind").jsonPrimitive.content })
        assertEquals(JsonPrimitive("이미 사용 중"), feedback.last()["reason"])
        val status = payload.getValue("dataStatus").jsonObject
        assertEquals(JsonPrimitive(6), status["backfillDays"])
        assertEquals(JsonPrimitive(true), status["insufficientData"])
    }

    @Test fun judgeDropsInvalidCitationsAndSortsMissingScoresLast() {
        val invalidUrl = ref(demand).copy(url = "https://other.test")
        val wrongProfile = ref(repo)
        val clamped = opportunity.copy(demandRefs = opportunity.demandRefs + invalidUrl + wrongProfile,
            scores = opportunity.scores + ("demand" to score.copy(score = 9.0)))
        val missing = opportunity.copy(scores = opportunity.scores - "timing")
        val judged = OpportunityJudge(config).judge(listOf(missing, clamped), input())
        assertEquals("opp-2", judged.first().id)
        assertEquals(opportunity.demandRefs, judged.first().validated.demandRefs)
        assertEquals(mapOf("항목 ID–URL 불일치" to 1, "블록-프로필 불일치" to 1),
            judged.first().invalidCitations["demandRefs"])
        assertEquals(5.0, judged.first().validated.scores.getValue("demand").score)
        assertEquals(4.3, judged.first().score)
        assertEquals(1, judged.first().warnings.size)
        assertEquals(OpportunityDecision.INVALID, judged.last().decision)
        assertContains(judged.last().reason, "시기")
    }

    @Test fun gatesAndThresholdControlDecisionAndSummary() {
        val judge = OpportunityJudge(config)
        fun result(raw: Opportunity, input: AnalysisInput = input()) = judge.judge(listOf(raw), input).single()
        val provisional = result(opportunity, input().copy(dataDays = 13))
        assertTrue(provisional.provisional)
        assertEquals(OpportunityDecision.REQUEST_CANDIDATE, provisional.decision)
        assertContains(judge.summary(listOf(provisional)), "의뢰 후보 1건")

        val duplicate = result(opportunity.copy(demandRefs = listOf(ref(demand), ref(demand))))
        assertTrue(duplicate.gateFailures.any { "수요 근거 1건" in it })
        val sameSourceDemand = demand2.copy(item = demand2.item.copy(source = "hn"))
        val singleSource = result(opportunity.copy(materials = emptyList(), techRefs = emptyList(),
            demandRefs = listOf(ref(demand), ref(sameSourceDemand))), input(listOf(demand, sameSourceDemand)))
        assertTrue(singleSource.gateFailures.any { "인용 출처 1종" in it })
        val unlicensed = result(opportunity, input(listOf(demand, demand2, repo.copy(item = repo.item.copy(extra = emptyMap())))))
        assertTrue(unlicensed.gateFailures.any { "허용 라이선스" in it })
        val excluded = result(opportunity.copy(excludedByCriteria = true, excludedReason = "제외 분야"))
        assertTrue(excluded.gateFailures.any { "평가 기준 제외" in it })
        val low = result(opportunity.copy(scores = opportunity.scores.mapValues { score.copy(score = 3.0) }))
        assertEquals(OpportunityDecision.NOT_VIABLE, low.decision)
        assertContains(low.reason, "가장 약함")
        assertContains(judge.summary(listOf(low)), "사업성 미달")
    }

    @Test fun confirmedAnalysisIsSavedAndReusedByThemeSetUnlessForced() = runTest {
        seed()
        val cardLedger = out.resolve("cost-ledger/$date.json")
        cardLedger.writeAtomically("daily ledger sentinel")
        val lines = mutableListOf<String>()
        val analysisLedger = FileCostLedger(out.resolve("cost-ledger-analysis"))
        val fake = Fake(ProviderId.OPENAI) { req ->
            assertTrue(analysisLedger.total(LocalDate.now(clock)) > 0.32)
            LlmResult(Json.encodeToString(OpportunityOutput(listOf(opportunity))), ProviderId.OPENAI, req.model, 100, 50, 0, 0.0007)
        }
        val command = command(lines, provider = { fake })
        command.run(arrayOf("--analyze", "other", "local-doc", "--yes"))
        assertEquals(1, fake.requests.size)
        val files = out.resolve("analysis").listFiles().orEmpty()
        assertEquals(setOf("json", "txt", "html"), files.map { it.extension }.toSet())
        val json = files.single { it.extension == "json" }
        val run = assertNotNull(readJson<AnalysisRun>(json))
        assertEquals(0.0007, run.actualUsd)
        assertEquals(3, run.input.evidenceCount)
        assertEquals(0.0007, analysisLedger.total(LocalDate.now(clock)))
        assertEquals("daily ledger sentinel", cardLedger.readText())
        val html = files.single { it.extension == "html" }.readText()
        assertContains(html, "&lt;script&gt;")
        assertFalse("<script>" in html)
        assertContains(files.single { it.extension == "txt" }.readText(), "의뢰 후보 1건")

        val offline = command(lines, provider = { error("unexpected provider") })
        offline.run(arrayOf("--analyze", "local-doc", "other", "--date", date.toString(), "--yes"))
        assertContains(lines.last(), json.name)
        offline.run(arrayOf("--analysis-detail", run.id, "1"))
        assertContains(lines.last(), "유료 고객 확인")
        assertContains(lines.last(), demand.item.url)
        offline.run(arrayOf("--analysis-detail", run.id, "opp-1"))
        assertContains(lines.last(), "대상 고객")
        offline.run(arrayOf("--analysis-list"))
        assertContains(lines[lines.lastIndex - 1], run.id)
        assertContains(lines.last(), "USD 0.000700 / USD 5.000000")
        command.run(arrayOf("--analyze", "local-doc", "other", "--yes", "--force"))
        assertEquals(2, fake.requests.size)
        assertEquals(2, out.resolve("analysis").listFiles().orEmpty().count { it.extension == "json" })
    }

    @Test fun savedTruncationCanBeRunAgainAfterConfirmation() = runTest {
        seed()
        val lines = mutableListOf<String>()
        val fake = Fake(ProviderId.OPENAI) { throw LlmServiceException(reason = "truncated") }
        val command = command(lines, provider = { fake })
        val args = arrayOf("--analyze", "local-doc", "--yes")
        command.run(args)
        val ledger = FileCostLedger(out.resolve("cost-ledger-analysis"))
        val reserved = ledger.total(LocalDate.now(clock))
        assertTrue(reserved > 0.32)
        assertContains(lines.last(), "분석 실패")
        assertContains(AnalysisReports(out.resolve("analysis")).list().single().issues,
            "출력 잘림 — maxOutputTokens 상향 필요")
        command.run(args.dropLast(1).toTypedArray())
        assertEquals(1, fake.requests.size)
        assertEquals(reserved, ledger.total(LocalDate.now(clock)))
        command.run(args)
        assertEquals(2, fake.requests.size)
    }

    @Test fun reportWriteFailureDoesNotLeavePartialCompanions() {
        val directory = out.resolve("analysis")
        val id = "20261120-012345000-1234abcd"
        val conflict = directory.resolve("$id.html/conflict")
        conflict.writeAtomically("existing")
        val run = AnalysisRun(id, now.toString(), date.toString(), listOf("local-doc"), config.model, config.effort,
            0.0, 0.0, emptyList(), AnalysisRun.InputSummary(0, 0), emptyList(), "사업성 미달", emptyList())
        val reports = AnalysisReports(directory)
        assertFailsWith<java.io.IOException> { reports.save(run) }
        assertTrue(reports.list().isEmpty())
        assertFalse(directory.resolve("$id.txt").exists())
        assertEquals("existing", conflict.readText())
    }

    private fun seed(signals: List<Signal> = listOf(demand, demand2, repo)) {
        out.resolve("trend/$date.json").writeAtomically(outputJson.encodeToString(report))
        val run = PipelineResult(signals.map { it.item }, emptyList(),
            RunReport(date.toString(), now.toString(), emptyList(), emptyList(), emptyMap(), 0), classified = signals)
        out.resolve("$date.json").writeAtomically(outputJson.encodeToString(run))
    }

    private fun command(lines: MutableList<String>, config: AnalysisConfig = this.config,
                        settings: BudgetSettings = BudgetSettings(analysisRequestUsd = config.requestBudgetUsd,
                            analysisMonthlyUsd = config.monthlyBudgetUsd), provider: () -> LlmProvider) =
        AnalysisCommand(out, config, PromptResolver(FilePromptStore(out.resolve("prompts").toPath())),
            provider, clock, { lines += it }, settings)

    private fun input(signals: List<Signal> = listOf(demand, demand2, repo)): AnalysisInput {
        val day = RunReport(date.toString(), now.toString(), emptyList(), emptyList(), emptyMap(), 0)
        val run = PipelineResult(emptyList(), emptyList(), day, classified = signals)
        return AnalysisInputBuilder(config).build(report, listOf("local-doc"), TrendInput(mapOf(date to run)), "기준")
    }

    private fun signal(id: Int, source: String, profile: String): Signal {
        val item = Item(source, id.toString(), "항목 $id", "https://example.test/$id", 100, publishedAt = now,
            extra = mapOf("license" to "MIT", "language" to "Kotlin"))
        return Signal(item, ClassifiedItem(item.externalId, profile, id.toDouble(), "", "분류 요약",
            listOf("local-doc"), "topic-$id", "topic $id"), "b".repeat(700),
            enrichedComments = List(4) { Comment("writer", "c".repeat(350)) })
    }

    private fun ref(signal: Signal) = OpportunityRef(signal.item.key, signal.item.url, "인용 근거")

    private class Fake(override val id: ProviderId, val reply: (LlmRequest) -> LlmResult) : LlmProvider {
        val requests = mutableListOf<LlmRequest>()
        override suspend fun listModels() = emptyList<ModelInfo>()
        override suspend fun complete(req: LlmRequest): LlmResult {
            requests += req
            return reply(req)
        }
    }
}

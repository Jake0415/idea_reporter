package collector.analysis

import collector.runtime.AnalysisEstimate
import collector.runtime.RunLock
import collector.budget.BudgetSettings
import collector.budget.MonthlyBudget
import collector.budget.OverBudget
import collector.budget.nextBudgetMonth
import collector.dev.readJson
import collector.dev.withRunLock
import collector.llm.*
import collector.prompt.PromptResolver
import collector.stage.Response
import collector.stage.TRUNCATED_RESPONSE
import collector.stage.structuredCall
import collector.trend.ThemeRow
import collector.trend.TrendConfig
import collector.trend.TrendInput
import collector.trend.TrendReport
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

private const val ESTIMATED_OUTPUT_FRACTION = 0.5
private const val RESERVABLE_BUDGET_FRACTION = 0.9
private const val BUDGET_OVER_SETTLEMENT = "예산 초과 정산"

internal class AnalysisCommand(
    private val out: File,
    private val config: AnalysisConfig,
    private val prompts: PromptResolver,
    private val provider: () -> LlmProvider,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val print: (String) -> Unit = ::println,
    private val settingsOverride: BudgetSettings? = null,
    private val sendMail: (String) -> Unit = {},
    private val beforePaidRun: () -> Unit = {},
    private val runLock: RunLock? = null,
    private val onEstimate: (AnalysisEstimate) -> Unit = {}
) {
    private val reports = AnalysisReports(out.resolve("analysis"))
    private fun settings() = settingsOverride ?: BudgetSettings.load(out, print)

    suspend fun run(args: Array<String>, acquireLock: Boolean = true): Boolean {
        when (args.firstOrNull()) {
            "--analysis-list" -> {
                require(args.size == 1)
                for (run in reports.list()) {
                    print("${run.id} · ${run.createdAt} · ${run.themes.joinToString()} · ${run.judgment} · ${usd(run.actualUsd)}")
                }
                val today = LocalDate.now(clock)
                val settings = settings()
                val monthly = MonthlyBudget(out, settings)
                print(monthlySummary(monthly.analysisSpend(today), settings.analysisMonthlyUsd))
                monthly.warnings(today).forEach(print)
            }
            "--analysis-detail" -> {
                require(args.size == 3)
                print(reports.detail(args[1], args[2]))
            }
            "--analyze" -> {
                val themes = mutableListOf<String>()
                var date: LocalDate? = null
                var yes = false
                var force = false
                var budget: Double? = null
                var fit: Int? = null
                var index = 1
                while (index < args.size) {
                    val arg = args[index++]
                    when (arg) {
                        "--yes" -> yes = true
                        "--force" -> force = true
                        "--budget" -> budget = requireNotNull(args.getOrNull(index++)?.toDoubleOrNull()
                            ?.takeIf { it.isFinite() && it >= 0 }) { "--budget에는 0 이상의 금액을 지정하세요." }
                        "--fit" -> fit = requireNotNull(args.getOrNull(index++)?.toIntOrNull()?.takeIf { it > 0 }) {
                            "--fit에는 맞춤안 번호를 지정하세요."
                        }
                        "--date" -> date = parseDate(args.getOrNull(index++))
                        else -> {
                            require(!arg.startsWith("--")) { "지원하지 않는 분석 옵션입니다." }
                            themes += arg
                        }
                    }
                }
                require(themes.size in 1..3 && themes.distinct().size == themes.size) {
                    "서로 다른 테마 ID를 1~3개 지정하세요."
                }
                out.mkdirs()
                val execute: suspend () -> Boolean = { analyze(themes.sorted(), date, yes, force, budget, fit) }
                return when {
                    !acquireLock -> execute()
                    runLock != null -> runLock.withLock(out, execute)
                    else -> withRunLock(out, execute)
                }
            }
            else -> throw IllegalArgumentException("지원하지 않는 분석 명령입니다.")
        }
        return true
    }

    private data class Plan(
        val fit: AnalysisFit, val input: AnalysisInput, val schema: JsonObject, val estimate: Double, val reserve: Double
    ) {
        val inputHash: String by lazy { PromptResolver.hash(input.json.toString()) }
    }

    private suspend fun analyze(
        themes: List<String>, requestedDate: LocalDate?, yes: Boolean, force: Boolean, budgetOverride: Double?, fitNumber: Int?
    ): Boolean {
        val settings = settings()
        val requestBudget = budgetOverride ?: settings.analysisRequestUsd
        val monthly = MonthlyBudget(out, settings)
        val trendDir = out.resolve("trend")
        val date = requestedDate ?: trendDir.listFiles().orEmpty().asSequence()
            .filter { it.isFile && it.extension == "json" }.mapNotNull { it.nameWithoutExtension.asDate() }.maxOrNull()
        requireNotNull(date) { "저장된 트렌드 리포트가 없습니다." }
        val trend = readJson<TrendReport>(trendDir.resolve("$date.json"))?.takeIf { it.date == date.toString() }
        requireNotNull(trend) { "트렌드 리포트를 읽을 수 없습니다 · $date" }

        val originalFit = AnalysisFit(15, config.effort, themes, 0, config.maxOutputTokens)
        val system = prompts.resolve("analysis-opportunity")
        val criteria = prompts.resolve("criteria")
        val promptVersions = listOf(system, criteria).associate {
            it.promptId to "${it.version}:${PromptResolver.hash(it.text)}"
        }
        val trendConfig = TrendConfig.defaults
        val judgmentSettingsHash = PromptResolver.hash(Json.encodeToString(
            listOf(config.threshold.toString(), config.minDemandRefs.toString(), config.minSources.toString(),
                config.minDataDays.toString()) + scoreLabels.keys.map { config.weights.getValue(it).toString() } +
                trendConfig.allowedLicenses
        ))
        val reusable = if (force) emptyList() else reports.list().filter {
            it.trendDate == trend.date && it.model == config.model && it.issues.isEmpty() &&
                it.promptVersions == promptVersions && it.judgmentSettingsHash == judgmentSettingsHash &&
                it.opportunities.any { result ->
                    result.decision != OpportunityDecision.INVALID
                }
        }
        fun reuse(plan: Plan): Boolean {
            val fit = plan.fit
            val prior = reusable.firstOrNull { run ->
                run.inputHash == plan.inputHash && run.fit?.let {
                    it.themes == fit.themes && it.evidenceCounts == fit.evidenceCounts &&
                        it.effort == fit.effort && it.maxOutputTokens == fit.maxOutputTokens
                } == true
            } ?: return false
            print("저장된 분석 · ${reports.resultFile(prior.id)} · ${prior.judgment}")
            return true
        }
        val history = TrendInput.read(out, date)
        val builder = AnalysisInputBuilder(config)
        val catalog = ModelCatalog.defaults
        fun plan(fit: AnalysisFit): Plan? {
            val input = try {
                builder.build(trend, fit.themes, history, criteria.text, fit.evidencePerTheme)
            } catch (_: AnalysisInputLimitException) {
                val label = if (fit.step == 0) "기본 구성" else fit.description()
                print("$label · 입력 상한 초과")
                return null
            }
            val schema = opportunitySchema(fit.themes)
            val inputTokens = analysisInputTokens(system.text, input.json.toString(), schema.toString())
            fun cost(tokens: Int) = catalog.analysisCostUsd(config.model, inputTokens, tokens)
            return Plan(fit.copy(evidenceCounts = input.evidence.mapValues { it.value.size }),
                input, schema, cost((fit.maxOutputTokens * ESTIMATED_OUTPUT_FRACTION).toInt()),
                cost(fit.maxOutputTokens))
        }

        val baseline = plan(originalFit)
        val startedAt = clock.instant()
        val runClock = Clock.fixed(startedAt, clock.zone)
        val today = LocalDate.now(runClock)
        val spent = monthly.analysisSpend(today)
        val analysisRemaining = monthly.analysisRemaining(today, spent)
        if (fitNumber == null && baseline != null && reuse(baseline)) {
            onEstimate(AnalysisEstimate(0.0, 0.0, true))
            return true
        }
        val totalRemaining = monthly.remaining(today) ?: Double.POSITIVE_INFINITY
        val available = minOf(requestBudget, analysisRemaining, totalRemaining)
        val reservable = available * RESERVABLE_BUDGET_FRACTION
        if (baseline != null) {
            print("분석 비용 추정 · ${usd(baseline.estimate)} · 최대 출력 기준 예약 ${usd(baseline.reserve)} · 요청 상한 ${usd(requestBudget)}")
        }
        print(monthlySummary(spent, settings.analysisMonthlyUsd))
        fun reject(reservation: Double? = baseline?.reserve) {
            val limits = if (reservation == null) "입력 상한 초과" else listOf(
                "요청 상한" to requestBudget, "분석 월 상한" to analysisRemaining, "전체 월 상한" to totalRemaining
            ).filter { reservation > it.second * RESERVABLE_BUDGET_FRACTION }.joinToString(" 및 ") { it.first } + " 90% 기준"
            print("분석 실행 불가 · $limits · 남은 예산 ${usd(available)} · " +
                "월 초기화일 ${nextBudgetMonth(today)} · --set-budget key=value")
        }
        onEstimate(AnalysisEstimate(baseline?.estimate, baseline?.reserve, false))
        val selected = if (baseline != null && baseline.reserve <= reservable && fitNumber == null) baseline else {
            val options = buildList {
                var previousReserve = baseline?.reserve ?: Double.POSITIVE_INFINITY
                for (fit in fitOptions(originalFit, baseline?.input, trend)) {
                    val option = plan(fit) ?: continue
                    if (option.reserve < previousReserve) {
                        add(option)
                        previousReserve = option.reserve
                    }
                }
            }
            val requested = fitNumber?.let { number ->
                requireNotNull(options.find { it.fit.step == number }) { "존재하지 않는 맞춤안입니다." }
            }
            if (requested != null && reuse(requested)) {
                onEstimate(AnalysisEstimate(0.0, 0.0, true))
                return true
            }
            if (available <= 0 || (settings.overBudget == OverBudget.STOP &&
                    (baseline == null || baseline.reserve > reservable))) {
                monthly.analysisBlockReason(today)?.let(print)
                reject()
                return !yes
            }
            options.forEach {
                print("맞춤안 ${it.fit.step} · ${it.fit.description()} · 예약 ${usd(it.reserve)}" +
                    (if (it.reserve <= reservable) " · 실행 가능" else " · 상한 초과"))
            }
            print("반대 검토 끄기 · 해당 없음 (S4)")
            val choice = when {
                requested != null -> requested
                settings.overBudget == OverBudget.AUTO_FIT -> options.firstOrNull { it.reserve <= reservable }
                else -> null
            }
            if (choice != null && requested == null && reuse(choice)) {
                onEstimate(AnalysisEstimate(0.0, 0.0, true))
                return true
            }
            if (options.none { it.reserve <= reservable } || (choice != null && choice.reserve > reservable)) {
                reject(choice?.reserve ?: options.lastOrNull()?.reserve ?: baseline?.reserve)
                return !yes
            }
            if (choice == null) {
                print("맞춤안을 선택하려면 --fit <번호> --yes")
                return !yes
            }
            print("${choice.fit.display()} · 예약 ${usd(choice.reserve)}")
            choice
        }
        val (fit, input, schema, estimate, reserve) = selected
        onEstimate(AnalysisEstimate(estimate, reserve, true))
        if (input.reportUrlMismatches > 0) {
            print("근거 URL 불일치 ${input.reportUrlMismatches}건 · 리포트 URL 사용")
        }
        if (!yes) return true

        val guard = CostGuard(reservable, clock = runClock, ledger = monthly.analysisLedger, budgetPerRun = true)
        val openAi = provider()
        var publicationFailure: Exception? = null
        val response = try {
            val router = LlmRouter(listOf(openAi), guard, mapOf("analysis" to config.model),
                stageTimeoutMillis = mapOf("analysis" to config.timeoutMillis), preflightBudget = true)
            structuredCall<OpportunityOutput>(router, "analysis", listOf(system, criteria), system.text,
                input.json, schema, fit.maxOutputTokens, effort = fit.effort, onAttempt = {
                    try {
                        beforePaidRun()
                    } catch (e: Exception) {
                        publicationFailure = e
                        throw e
                    }
                })
        } finally {
            (openAi as? AutoCloseable)?.close()
        }
        publicationFailure?.let { throw it }
        val judge = OpportunityJudge(config, trendConfig)
        val opportunities = (response as? Response.Ok)?.value?.let { judge.judge(it.opportunities, input) }.orEmpty()
        val failure = (response as? Response.Failed)?.reason?.let {
            if (it == TRUNCATED_RESPONSE) "출력 잘림 — maxOutputTokens 상향 필요" else it
        }
        val actual = guard.calls.sumOf { it.costUsd }
        if (actual > available) {
            monthly.blockAnalysis(today, available, actual)
        }
        val settlementWarning = if (actual > reserve)
            "$BUDGET_OVER_SETTLEMENT ${usd(actual - reserve).replace("USD ", "$")}" else null
        val issues = listOfNotNull(failure, settlementWarning)
        val warnings = monthly.warnings(today)
        (listOfNotNull(settlementWarning) + warnings).forEach(print)
        val judgment = if (failure == null) judge.summary(opportunities) else "분석 실패 · $failure"
        val hash = PromptResolver.hash("$date/${themes.joinToString(",")}/$startedAt/${UUID.randomUUID()}").take(8)
        val id = "${idTime.format(startedAt.atZone(clock.zone))}-$hash"
        val run = AnalysisRun(id, startedAt.toString(), trend.date, fit.themes, config.model, fit.effort,
            estimate, actual, guard.calls,
            AnalysisRun.InputSummary(input.evidence.values.sumOf { it.size }, input.json.toString().length,
                input.reportUrlMismatches),
            opportunities, judgment, issues, fit, warnings, promptVersions, selected.inputHash, judgmentSettingsHash)
        print("분석 결과 · ${reports.save(run)} · $judgment")
        if (config.mail.analysis && issues.isEmpty() && opportunities.any { it.decision != OpportunityDecision.INVALID }) {
            sendMail(run.id)
        }
        return failure == null
    }

    private fun fitOptions(original: AnalysisFit, input: AnalysisInput?, trend: TrendReport): List<AnalysisFit> = buildList {
        val tokenFloor = minOf(config.minFitOutputTokens, original.maxOutputTokens)
        add(original.copy(evidencePerTheme = 10, step = 1))
        val fewerEvidence = original.copy(evidencePerTheme = 6, step = 2)
        add(fewerEvidence)
        val medium = if (original.effort > Effort.MEDIUM) {
            fewerEvidence.copy(effort = Effort.MEDIUM, step = 3,
                maxOutputTokens = (original.maxOutputTokens * 3 / 4).coerceAtLeast(tokenFloor)).also { add(it) }
        } else fewerEvidence
        val ranked = trend.themes.filter { it.id in original.themes }.sortedWith(
            compareByDescending<ThemeRow> { row ->
                maxOf(input?.evidence?.get(row.id).orEmpty().maxOfOrNull { it.score } ?: 0.0,
                    row.evidence.values.maxOfOrNull { refs -> refs.maxOfOrNull { it.score } ?: 0.0 } ?: 0.0)
            }.thenByDescending { it.growth }.thenBy { it.id }
        )
        for (count in original.themes.size - 1 downTo 1) {
            add(medium.copy(themes = ranked.take(count).map { it.id }.sorted(), step = last().step + 1,
                maxOutputTokens = (medium.maxOutputTokens * count / original.themes.size).coerceAtLeast(tokenFloor)))
        }
    }

    private fun monthlySummary(spent: Double, cap: Double) =
        "이번 달 분석 사용액(예약 포함) · ${usd(spent)} / ${usd(cap)}"

    private fun parseDate(value: String?): LocalDate =
        requireNotNull(value?.asDate()) { "날짜는 yyyy-MM-dd 형식으로 지정하세요." }

    private fun String.asDate(): LocalDate? =
        runCatching { LocalDate.parse(this) }.getOrNull()?.takeIf { length == 10 && it.toString() == this }

    private companion object {
        val idTime: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmssSSS")
    }
}

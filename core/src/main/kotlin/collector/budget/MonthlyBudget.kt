package collector.budget

import collector.analysis.usd
import collector.config.PipelineConfig
import collector.dev.FileCostLedger
import collector.dev.FileHistorySearch
import collector.dev.costLedger
import collector.dev.requestedCostLedger
import collector.dev.outputJson
import collector.dev.readJson
import collector.dev.writeAtomically
import collector.llm.CostLedger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

internal const val MONTHLY_TOTAL_EXCEEDED = "전체 월 상한 초과 · 유료 단계 생략"

internal class MonthlyBudget(private val out: File, private val settings: BudgetSettings) {
    val analysisLedger = FileCostLedger(out.resolve("cost-ledger-analysis"))
    private val cardLedger by lazy { costLedger(out, FileHistorySearch(out, PipelineConfig.defaults.historyDays)) }

    private val requestedLedger by lazy { requestedCostLedger(out) }

    fun analysisSpend(day: LocalDate) = analysisLedger.monthlyTotal(day)
    private fun totalSpend(day: LocalDate, analysis: Double = analysisSpend(day)) =
        cardLedger.monthlyTotal(day) + requestedLedger.monthlyTotal(day) + analysis

    private fun requestedBlock(day: LocalDate) = out.resolve("cost-ledger-requested/${YearMonth.from(day)}.blocked")

    fun requestedBlocked(day: LocalDate) = requestedBlock(day).isFile

    fun blockRequested(day: LocalDate, approvedUsd: Double, settledUsd: Double) {
        requestedBlock(day).writeAtomically(outputJson.encodeToString(
            mapOf("approvedUsd" to approvedUsd, "settledUsd" to settledUsd)
        ))
    }

    fun releaseRequested(day: LocalDate) {
        Files.deleteIfExists(requestedBlock(day).toPath())
    }

    fun remaining(day: LocalDate): Double? = settings.monthlyTotalUsd?.let {
        (it - totalSpend(day)).coerceAtLeast(0.0)
    }

    fun allows(day: LocalDate, reservation: Double): Boolean {
        val cap = settings.monthlyTotalUsd ?: return true
        val remaining = checkNotNull(remaining(day))
        return remaining > 0 && reservation - remaining <= Math.ulp(cap)
    }

    fun dailySkipReason(day: LocalDate, dailyBudgetUsd: Double): String? =
        MONTHLY_TOTAL_EXCEEDED.takeIf {
            settings.monthlyTotalUsd != null &&
                !allows(day, (dailyBudgetUsd - cardLedger.total(day)).coerceAtLeast(0.0))
        }

    private fun analysisBlock(day: LocalDate) = out.resolve("cost-ledger-analysis/${YearMonth.from(day)}.blocked")

    @Serializable
    private data class AnalysisBlock(
        val appliedCap: Double, val settledUsd: Double, val requestCap: Double, val monthlyCap: Double
    )

    fun blockAnalysis(day: LocalDate, appliedCap: Double, settledUsd: Double) {
        analysisBlock(day).writeAtomically(outputJson.encodeToString(
            AnalysisBlock(appliedCap, settledUsd, settings.analysisRequestUsd, settings.analysisMonthlyUsd)
        ))
    }

    fun analysisBlockReason(day: LocalDate): String? {
        val block = analysisBlock(day)
        if (!block.isFile) return null
        val record = readJson<AnalysisBlock>(block)
        // Older markers stored only the settlement amount, so use the settings timestamp for those.
        val released = if (record == null) out.resolve("settings/budget.json").lastModified() > block.lastModified()
            else record.requestCap != settings.analysisRequestUsd || record.monthlyCap != settings.analysisMonthlyUsd
        if (released) {
            Files.deleteIfExists(block.toPath())
            return null
        }
        val reason = record?.let { "적용 상한 ${usd(it.appliedCap)} · 정산 ${usd(it.settledUsd)}" } ?: "이전 실행의 예산 초과 정산"
        return "분석 차단 · 예산 초과 정산 · $reason · " +
            "--set-budget으로 analysisRequestUsd 또는 analysisMonthlyUsd 변경 · 월 초기화일 ${nextBudgetMonth(day)}"
    }

    fun analysisRemaining(day: LocalDate, spent: Double = analysisSpend(day)): Double =
        if (analysisBlockReason(day) != null) 0.0 else (settings.analysisMonthlyUsd - spent).coerceAtLeast(0.0)

    fun warnings(day: LocalDate): List<String> {
        val analysis = analysisSpend(day)
        return listOfNotNull(
            warning("분석", analysis, settings.analysisMonthlyUsd),
            settings.monthlyTotalUsd?.let { warning("전체", totalSpend(day, analysis), it) }
        )
    }

    private fun warning(label: String, spent: Double, cap: Double): String? =
        if (usagePercent(spent, cap) >= settings.warnAtPercent)
            "이번 달 ${dollars(spent)} / ${dollars(cap)} (${percent(spent, cap)}) · $label 예산 경고"
        else null

    fun status(day: LocalDate): List<String> {
        val cards = cardLedger.monthlyTotal(day)
        val analysis = analysisSpend(day)
        val total = totalSpend(day, analysis)
        val remainingTotal = remaining(day) ?: Double.POSITIVE_INFINITY
        val remainingAnalysis = analysisRemaining(day, analysis)
        fun monthly(label: String, spent: Double, cap: Double?, available: Double?) =
            "$label · 사용 ${usd(spent)} / ${cap?.let(::usd) ?: "상한 없음"} · " +
                "남은 예산 ${available?.let(::usd) ?: "제한 없음"} · " +
                "사용률 ${cap?.let { percent(spent, it) } ?: "-"}"
        return listOf(
            "일일 카드 상한 ${usd(settings.dailyCardUsd)} · 남은 예산 " +
                usd(minOf(remainingTotal, (settings.dailyCardUsd - cardLedger.total(day)).coerceAtLeast(0.0))),
            "분석 요청 상한 ${usd(settings.analysisRequestUsd)} · 남은 예산 " +
                usd(minOf(settings.analysisRequestUsd, remainingAnalysis, remainingTotal)),
            "과거 채우기 실행 상한 ${usd(settings.backfillRunUsd)} · 남은 예산 " +
                usd(minOf(settings.backfillRunUsd, remainingTotal)),
            "이번 달 일일 카드 원장 ${usd(cards)}",
            monthly("이번 달 분석 원장", analysis, settings.analysisMonthlyUsd, remainingAnalysis),
            monthly("이번 달 전체", total, settings.monthlyTotalUsd, settings.monthlyTotalUsd?.let { remainingTotal }),
            analysisBlockReason(day) ?: "분석 차단 없음",
            "경고 기준 ${settings.warnAtPercent}% · 상한 초과 ${settings.overBudget} · 월 초기화일 ${nextBudgetMonth(day)}"
        )
    }
}

private fun CostLedger.monthlyTotal(day: LocalDate): Double =
    (1..day.lengthOfMonth()).sumOf { total(day.withDayOfMonth(it)) }

internal fun nextBudgetMonth(day: LocalDate): LocalDate = YearMonth.from(day).plusMonths(1).atDay(1)
private fun usagePercent(spent: Double, cap: Double) = if (cap > 0) spent / cap * 100 else if (spent > 0) 100.0 else 0.0
private fun percent(spent: Double, cap: Double) = String.format(Locale.ROOT, "%.1f%%", usagePercent(spent, cap))
private fun dollars(amount: Double) = String.format(Locale.ROOT, "$%.6f", amount)

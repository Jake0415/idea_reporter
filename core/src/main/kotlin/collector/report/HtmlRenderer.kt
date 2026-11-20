package collector.report

import collector.pipeline.PipelineResult
import collector.stage.Category
import collector.model.TrackedIdea
import collector.model.SignalRef
import collector.source.GitHubIssuesAdapter
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.LocalDate
import java.util.Locale

class HtmlRenderer(categories: List<Category>) {
    private val names = categories.associate { it.id to it.name }

    data class Rendered(val subject: String, val html: String, val text: String)

    fun render(result: PipelineResult, monthCost: Double = result.report.costUsd, todayCost: Double = result.report.costUsd): Rendered {
        val report = result.report
        val date = LocalDate.parse(report.runDate)
        val candidates = report.stages.firstOrNull { it.stage == "select" }?.output ?: 0
        val subject = "[AI 아이디어] ${date.monthValue}월 ${date.dayOfMonth}일 — 카드 ${result.cards.size}개 (수집 ${report.fetchedTotal} → 후보 $candidates)"
        val funnel = report.stages.joinToString(" → ") { "${stageName(it.stage)} ${it.output}" }
        val failures = report.failedSources.joinToString(", ") { "${it.source} (${it.error})" }
        val costs = "오늘 비용 ${usd(todayCost)} · 이번 달 합계 ${usd(monthCost)} · 실행 시간 ${report.totalMillis}ms"
        val titles = result.tracked.associate { it.id to it.title }
        val text = buildString {
            appendLine(subject)
            appendLine("깔때기 · $funnel")
            if (failures.isNotEmpty()) appendLine("실패 출처 · $failures")
            report.issues.forEach { appendLine(it) }
            appendLine("\n목차")
            result.cards.forEachIndexed { index, card ->
                appendLine("${index + 1}. ${card.title} · ${names[card.category]} ${card.badges.joinToString(" · ")}".trimEnd())
            }
            for (card in result.cards) {
                appendLine("\n${card.title}")
                appendLine(card.oneLiner)
                appendLine("오늘 고른 이유 · ${card.whyToday}")
                evidence(card.techSignals, card.demandSignals + card.competitors).forEach { appendLine("- ${evidenceNote(it)} · ${it.url}") }
                appendLine("가장 큰 위험 · ${card.biggestRisk}")
                appendLine("앱에서 보기 · ideacollector://card/${card.id}")
            }
            appendLine("\n추적 업데이트")
            if (result.trackingUpdates.isEmpty()) appendLine("새 업데이트 없음")
            result.trackingUpdates.forEach {
                appendLine("${titles[it.trackedId] ?: it.trackedId} ${arrow(it.trend)} ${strength(it.strength)} · ${it.summary}")
                appendLine("앱에서 보기 · ideacollector://track/${it.trackedId}")
            }
            appendLine("\n$costs")
        }
        val html = buildString {
            appendLine("<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\"><title>${escape(subject)}</title></head><body>")
            appendLine("<h1>${escape(subject)}</h1><p>깔때기 · ${escape(funnel)}</p>")
            if (failures.isNotEmpty()) appendLine("<p>실패 출처 · ${escape(failures)}</p>")
            report.issues.forEach { appendLine("<p>${escape(it)}</p>") }
            appendLine("<h2>목차</h2><ol>")
            result.cards.forEach { card ->
                appendLine("<li><a href=\"#${escape(card.id)}\">${escape(card.title)}</a> ${pill(names[card.category].orEmpty())} " +
                    card.badges.joinToString(" ", transform = ::pill) + "</li>")
            }
            appendLine("</ol>")
            for (card in result.cards) {
                appendLine("<section id=\"${escape(card.id)}\"><h2>${escape(card.title)}</h2><p>${escape(card.oneLiner)}</p>")
                appendLine("<p><strong>오늘 고른 이유</strong> · ${escape(card.whyToday)}</p><ul>")
                evidence(card.techSignals, card.demandSignals + card.competitors).forEach {
                    val url = it.url.toHttpUrlOrNull()
                    appendLine(if (url == null) "<li>${escape(evidenceNote(it))}</li>" else
                        "<li><a href=\"${escape(url.toString())}\">${escape(evidenceNote(it))}</a></li>")
                }
                appendLine("</ul><p><strong>가장 큰 위험</strong> · ${escape(card.biggestRisk)}</p>")
                appendLine("<p><a href=\"ideacollector://card/${escape(card.id)}\">앱에서 보기</a></p></section>")
            }
            appendLine("<h2>추적 업데이트</h2>")
            if (result.trackingUpdates.isEmpty()) appendLine("<p>새 업데이트 없음</p>")
            result.trackingUpdates.forEach {
                appendLine("<p><strong>${escape(titles[it.trackedId] ?: it.trackedId)}</strong> ${arrow(it.trend)} ${strength(it.strength)} · " +
                    "${escape(it.summary)} · <a href=\"ideacollector://track/${escape(it.trackedId)}\">앱에서 보기</a></p>")
            }
            appendLine("<footer>${escape(costs)}</footer></body></html>")
        }
        return Rendered(subject, html, text)
    }

    private fun evidenceNote(ref: SignalRef): String {
        val note = ref.license?.let { "${ref.note} · 라이선스 $it" } ?: ref.note
        return if (ref.itemId.startsWith("github-issues:"))
            "${GitHubIssuesAdapter.evidenceLabel(ref.url)} · $note" else note
    }

    private fun <T> evidence(tech: List<T>, other: List<T>) = tech.take(1) + other.take(1)
    private fun pill(text: String) =
        "<span style=\"background:#eaeef8;color:#1c315c;padding:2px 6px;border-radius:4px\">${escape(text)}</span>"
    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    private fun usd(cost: Double) = String.format(Locale.ROOT, "$%.5f", cost)
    private fun strength(value: Double) = String.format(Locale.ROOT, "%.1f", value)
    private fun arrow(trend: TrackedIdea.Trend) = when (trend) {
        TrackedIdea.Trend.UP -> "↑"
        TrackedIdea.Trend.FLAT -> "→"
        TrackedIdea.Trend.DOWN -> "↓"
    }

    companion object {
        fun stageName(name: String) = when (name) {
            "collect" -> "수집"
            "filter" -> "필터"
            "dedupe" -> "중복 제거"
            "score" -> "점수 통일"
            "classify" -> "분류"
            "select" -> "후보"
            "enrich" -> "보강"
            "generate" -> "작성"
            "validate" -> "검증"
            "track" -> "추적"
            "deliver" -> "저장"
            else -> name
        }
    }
}

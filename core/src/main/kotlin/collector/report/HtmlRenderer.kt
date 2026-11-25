package collector.report

import collector.pipeline.PipelineResult
import collector.stage.Category
import collector.runtime.candidateView
import collector.model.TrackedIdea
import collector.model.SignalRef
import collector.source.GitHubIssuesAdapter
import collector.source.hasPrivateRssQuery
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.LocalDate
import java.util.Locale
import collector.report.MailTheme as T

/**
 * 일일 아이디어 메일. [appLinks] = "앱에서 보기"(ideacollector://) 링크 — 앱이 나오기 전에는 끈다(2026-11-23 결정, 4단계에서 켬).
 */
class HtmlRenderer(categories: List<Category>, private val appLinks: Boolean = false,
                   private val includeCandidates: Boolean = false) {
    private val names = categories.associate { it.id to it.name }

    data class Rendered(val subject: String, val html: String, val text: String)

    fun render(result: PipelineResult, monthCost: Double = result.report.costUsd, todayCost: Double = result.report.costUsd): Rendered {
        val report = result.report
        val date = LocalDate.parse(report.runDate)
        val candidates = report.stages.firstOrNull { it.stage == "select" }?.output ?: 0
        val subject = "[AI 아이디어] ${date.monthValue}월 ${date.dayOfMonth}일 — 카드 ${result.cards.size}개 (수집 ${report.fetchedTotal} → 카드 후보 $candidates)"
        val funnel = report.stages.joinToString(" → ") { "${stageName(it.stage)} ${it.output}" }
        val failures = report.failedSources.joinToString(", ") { "${it.source} (${it.error})" }
        val costs = "오늘 비용 ${usd(todayCost)} · 이번 달 합계 ${usd(monthCost)} · 실행 시간 ${report.totalMillis}ms"
        val batchWait = (report.classificationBatchWaitMillis / 60_000).takeIf { it > 0 }?.let { "분류 Batch 대기 ${it}분" }
        val titles = result.tracked.associate { it.id to it.title }
        val unused = if (includeCandidates) candidateView(result).candidates.filterNot { it.usedInCard }.take(15)
            .map { entry ->
                val (item, classification) = entry.signal
                val profile = when (classification.profile) {
                    "tech-signal" -> "쓸 수 있는 기술"
                    "demand-signal" -> "사람들의 요청·불편"
                    "market-signal" -> "이미 나온 제품"
                    else -> ""
                }
                val summary = classification.summary.ifBlank { item.title }.lineSequence().first()
                val score = classification.score?.let(::strength).orEmpty()
                // 원문 URL에 인증값(사용자 정보·민감 쿼리)이 있으면 메일에 싣지 않는다.
                val safeUrl = item.url.toHttpUrlOrNull()?.takeIf { url ->
                    url.username.isEmpty() && url.password.isEmpty() &&
                        (0 until url.querySize).none { url.hasPrivateRssQuery(it) }
                }?.toString()
                Triple(summary, listOf(profile, score.takeIf { it.isNotBlank() }?.plus("점").orEmpty())
                    .filter { it.isNotBlank() }.joinToString(" · "), safeUrl)
            } else emptyList()
        val text = buildString {
            appendLine(subject)
            appendLine("단계별 건수 · $funnel")
            batchWait?.let { appendLine(it) }
            if (failures.isNotEmpty()) appendLine("실패 출처 · $failures · 나머지 출처로 만들었습니다")
            report.issues.forEach { appendLine(it) }
            if (result.cards.isNotEmpty()) appendLine("\n목차")
            result.cards.forEachIndexed { index, card ->
                appendLine("${index + 1}. ${card.title} · ${names[card.category]} ${card.badges.joinToString(" · ")}".trimEnd())
            }
            for (card in result.cards) {
                appendLine("\n${card.title}")
                appendLine(card.oneLiner)
                appendLine("오늘 고른 이유 · ${card.whyToday}")
                evidence(card.techSignals, card.demandSignals + card.competitors).forEach { appendLine("- ${evidenceNote(it)} · ${it.url}") }
                if (card.supplementRefs.isNotEmpty()) {
                    appendLine("보충 자료(웹 검색)")
                    card.supplementRefs.forEach { appendLine("- ${it.title} · ${it.note} · ${it.url}") }
                }
                appendLine("가장 큰 위험 · ${card.biggestRisk}")
                if (appLinks) appendLine("앱에서 보기 · ideacollector://card/${card.id}")
            }
            if (unused.isNotEmpty()) {
                appendLine("\n오늘 후보(카드 안 된 것)")
                unused.forEach { (summary, meta, url) -> appendLine("$summary\n$meta" + url?.let { " · 원문 열기 · $it" }.orEmpty()) }
            }
            appendLine("\n추적 업데이트")
            if (result.trackingUpdates.isEmpty()) appendLine("추적 중인 아이디어에 오늘 새 소식이 없습니다")
            result.trackingUpdates.forEach {
                appendLine("${titles[it.trackedId] ?: it.trackedId} ${arrow(it.trend)} ${strength(it.strength)} · ${it.summary}")
                if (appLinks) appendLine("앱에서 보기 · ideacollector://track/${it.trackedId}")
            }
            appendLine("\n$costs")
        }
        val body = buildString {
            appendLine("<h1 style=\"${T.h1}\">AI 아이디어 · ${date.monthValue}월 ${date.dayOfMonth}일</h1>")
            appendLine("<p style=\"${T.meta}\">카드 ${result.cards.size}개 · 단계별 건수 · ${escape(funnel)}</p>")
            if (failures.isNotEmpty()) appendLine(T.banner("실패 출처 · ${escape(failures)} · 나머지 출처로 만들었습니다", T.Tone.WARNING))
            batchWait?.let { appendLine("<p style=\"${T.meta}\">$it</p>") }
            report.issues.forEach { appendLine(T.banner(escape(it), T.Tone.INFO)) }
            if (result.cards.isNotEmpty()) {
                appendLine("<h2 style=\"${T.h2}\">목차</h2><ol style=\"margin:0 0 8px;padding-left:20px\">")
                result.cards.forEach { card ->
                    appendLine("<li style=\"margin:0 0 6px\"><a href=\"#${escape(card.id)}\" style=\"${T.link}\">${escape(card.title)}</a> " +
                        pills(card.category, card.badges) + "</li>")
                }
                appendLine("</ol>")
            }
            for ((index, card) in result.cards.withIndex()) {
                val inner = buildString {
                    append("<h3 style=\"${T.h3}\">${index + 1}. ${escape(card.title)}</h3>")
                    append("<p style=\"${T.meta}\">${escape(card.oneLiner)}</p>")
                    append("<p style=\"margin:0 0 12px\">${pills(card.category, card.badges)}</p>")
                    append("<p style=\"${T.p}\"><strong style=\"${T.strong}\">오늘 고른 이유</strong> · ${escape(card.whyToday)}</p>")
                    append("<ul style=\"margin:0 0 8px;padding-left:20px\">")
                    evidence(card.techSignals, card.demandSignals + card.competitors).forEach {
                        val url = it.url.toHttpUrlOrNull()
                        append(if (url == null) "<li>${escape(evidenceNote(it))}</li>" else
                            "<li><a href=\"${escape(url.toString())}\" style=\"${T.link}\">${escape(evidenceNote(it))}</a></li>")
                    }
                    append("</ul>")
                    if (card.supplementRefs.isNotEmpty()) {
                        append("<p style=\"${T.meta}\"><strong>보충 자료(웹 검색)</strong></p>")
                        append("<ul style=\"margin:0 0 8px;padding-left:20px\">")
                        card.supplementRefs.forEach {
                            val url = it.url.toHttpUrlOrNull()
                            val label = escape("${it.title} · ${it.note}")
                            append(if (url == null) "<li>$label</li>" else
                                "<li><a href=\"${escape(url.toString())}\" style=\"${T.link}\">$label</a></li>")
                        }
                        append("</ul>")
                    }
                    append("<p style=\"margin:0\"><strong style=\"${T.strong}\">가장 큰 위험</strong> · ${escape(card.biggestRisk)}</p>")
                    if (appLinks) append("<p style=\"margin:8px 0 0;font-size:12px\"><a href=\"ideacollector://card/${escape(card.id)}\" style=\"${T.link}\">앱에서 보기</a></p>")
                }
                appendLine(T.card(inner, id = escape(card.id)))
            }
            if (unused.isNotEmpty()) {
                appendLine("<h2 style=\"${T.h2}\">오늘 후보(카드 안 된 것)</h2>")
                unused.forEach { (summary, meta, url) ->
                    val href = url?.toHttpUrlOrNull()
                    val original = if (href == null) "" else " · <a href=\"${escape(href.toString())}\" style=\"${T.link}\">원문 열기</a>"
                    appendLine("<p style=\"${T.p}\"><strong style=\"${T.strong}\">${escape(summary)}</strong><br>" +
                        "<span style=\"${T.meta}\">${escape(meta)}$original</span></p>")
                }
            }
            appendLine("<h2 style=\"${T.h2}\">추적 업데이트</h2>")
            if (result.trackingUpdates.isEmpty()) appendLine("<p style=\"${T.meta}\">추적 중인 아이디어에 오늘 새 소식이 없습니다</p>")
            result.trackingUpdates.forEach {
                appendLine("<p style=\"${T.p}\"><strong style=\"${T.strong}\">${escape(titles[it.trackedId] ?: it.trackedId)}</strong> ${arrow(it.trend)} ${strength(it.strength)} · " +
                    escape(it.summary) +
                    (if (appLinks) " · <a href=\"ideacollector://track/${escape(it.trackedId)}\" style=\"${T.link}\">앱에서 보기</a>" else "") + "</p>")
            }
            appendLine("<p style=\"${T.footer}\">${escape(costs)}</p>")
        }
        return Rendered(subject, T.document(escape(subject), body), text)
    }

    private fun evidenceNote(ref: SignalRef): String {
        val note = ref.license?.let { "${ref.note} · 라이선스 $it" } ?: ref.note
        return if (ref.itemId.startsWith("github-issues:"))
            "${GitHubIssuesAdapter.evidenceLabel(ref.url)} · $note" else note
    }

    private fun <T> evidence(tech: List<T>, other: List<T>) = tech.take(1) + other.take(1)
    /** 분류는 중립(회색), 좋은 신호 배지는 정보색 — 경고 노랑은 실패 안내에만 쓴다. */
    private fun pills(category: String, badges: List<String>) =
        (listOfNotNull(names[category]?.takeIf { it.isNotEmpty() }?.let { T.pill(escape(it)) }) +
            badges.map { T.pill(escape(it), T.Tone.HIGHLIGHT) }).joinToString(" ")
    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    private fun usd(cost: Double) = String.format(Locale.ROOT, "$%.2f", cost)
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
            "score" -> "점수 맞추기"
            "classify" -> "분류"
            "select" -> "카드 후보"
            "enrich" -> "댓글·과거 기록 붙이기"
            "generate" -> "작성"
            "validate" -> "검증"
            "track" -> "추적"
            "deliver" -> "저장"
            else -> name
        }
    }
}

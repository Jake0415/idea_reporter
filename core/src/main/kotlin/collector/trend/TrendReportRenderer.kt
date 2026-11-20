package collector.trend

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToLong

class TrendReportRenderer {
    data class Rendered(val subject: String, val html: String, val text: String)

    fun render(report: TrendReport): Rendered {
        val date = LocalDate.parse(report.date)
        // ISO weeks start on Monday. The Thursday determines the month and its ordinal Thursday.
        val thursday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays(3)
        val week = (thursday.dayOfMonth - 1) / 7 + 1
        val visibleCandidates = report.rising.count { it.candidate }
        val hiddenCandidates = report.themes.count { it.candidate } - visibleCandidates
        val overflow = if (hiddenCandidates > 0) " 외 $hiddenCandidates" else ""
        val heading = if (report.insufficientData) "테마 ${report.themes.size} · 데이터 부족"
            else "뜨는 테마 ${report.rising.size} · 분석 후보 ◆$visibleCandidates$overflow"
        val subject = "[기술 트렌드] ${thursday.monthValue}월 ${week}주 — $heading"
        val notice = "데이터 부족 · 증가율과 분석 후보는 데이터가 충분히 쌓이면 표시됩니다."
        val themeHeaders = listOf("테마", "이번 주", "증가", "출처", "수요", "시장")
        val repositoryHeaders = listOf("저장소", "설명", "하루 스타 증가", "언어", "라이선스", "포크/스타", "테마")
        val repositoryRows = report.repositories.map {
            val name = it.name + if (it.suspected) " (의심)" else ""
            val description = if (it.description.length > 80) it.description.take(79) + "…" else it.description
            listOf(name, description, it.starsPerDay.roundToLong().toString(), it.language ?: "—",
                it.license ?: "—", it.forkRatio?.let(TrendAnalyzer::decimal) ?: "—", it.themeName ?: "—")
        }
        val overheated = report.spam.map { "${it.name} · ${it.count}개 · ${it.dates.joinToString(", ")}" } +
            report.themes.filter { it.overheated }.map { "${it.name} · 시장 신호 ${it.marketCount}건" }
        val distributions = listOf("언어 · ${distribution(report.languages)}", "라이선스 · ${distribution(report.licenses)}")
        val status = listOf("기간 ${report.startDate} ~ ${report.date} · 데이터 ${report.dataDays}일",
            "재등장 미매핑 ${report.unmappedRepeatCount}건",
            "수집 실패 출처 · ${report.failedSources.joinToString(", ").ifEmpty { "없음" }}",
            "너무 넓어 제외한 태그 · ${report.broadTags.joinToString(", ").ifEmpty { "없음" }}")
        val tables = listOf(
            Triple("뜨는 테마", themeHeaders + "예시", report.rising.map {
                themeRow(it) + it.examples.take(2).joinToString(", ") { label -> label.take(30) }.ifEmpty { "—" }
            }),
            Triple("지는 테마", themeHeaders, report.falling.map(::themeRow)),
            Triple("빠르게 크는 저장소", repositoryHeaders, repositoryRows)
        )

        val lineBreak = Regex("\\R")
        val text = buildString {
            appendLine(subject)
            if (report.insufficientData) appendLine(notice)
            report.summary.forEach { appendLine(it) }
            for ((title, headers, rows) in tables) {
                appendLine("\n$title")
                if (title == "지는 테마" && rows.isEmpty()) appendLine("해당 없음")
                else {
                    appendLine(headers.joinToString(" | "))
                    rows.forEach { row ->
                        appendLine(row.joinToString(" | ") { it.replace('|', '/').replace(lineBreak, " ") })
                    }
                }
            }
            appendLine("\n언어·라이선스 분포")
            distributions.forEach { appendLine(it) }
            appendLine("\n과열")
            overheated.ifEmpty { listOf("없음") }.forEach { appendLine(it) }
            appendLine("\n데이터 상태")
            status.forEach { appendLine(it) }
        }
        val html = buildString {
            appendLine("<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\"><title>${escape(subject)}</title></head>")
            appendLine("<body style=\"font-family:sans-serif;color:#172238;line-height:1.5\"><h1>${escape(subject)}</h1>")
            if (report.insufficientData) appendLine("<p style=\"background:#fff2cc;padding:12px\">${escape(notice)}</p>")
            if (report.summary.isNotEmpty()) {
                appendLine("<ul style=\"background:#eef2f8;padding:12px 28px\">")
                report.summary.forEach { appendLine("<li>${escape(it)}</li>") }
                appendLine("</ul>")
            }
            for ((title, headers, rows) in tables) {
                appendLine("<h2>${escape(title)}</h2>")
                if (title == "지는 테마" && rows.isEmpty()) {
                    appendLine("<p>해당 없음</p>")
                    continue
                }
                appendLine("<table style=\"border-collapse:collapse;width:100%\">")
                appendLine("<thead>${tableRow(headers, "th")}</thead><tbody>")
                rows.forEach { appendLine(tableRow(it, "td")) }
                appendLine("</tbody></table>")
            }
            appendLine("<h2>언어·라이선스 분포</h2>")
            distributions.forEach { appendLine("<p>${escape(it)}</p>") }
            appendLine("<h2>과열</h2>")
            overheated.ifEmpty { listOf("없음") }.forEach { appendLine("<p>${escape(it)}</p>") }
            appendLine("<footer><h2>데이터 상태</h2>")
            status.forEach { appendLine("<p>${escape(it)}</p>") }
            appendLine("</footer></body></html>")
        }
        return Rendered(subject, html, text)
    }

    private fun themeRow(row: ThemeRow): List<String> {
        val name = (if (row.candidate) "◆ " else "") + row.id + (if (row.overheated) " (과열)" else "")
        return listOf(name, (row.weeklyCounts.lastOrNull() ?: 0).toString(), TrendAnalyzer.growthLabel(row),
            row.sources.size.toString(), row.demandCount.toString(), row.marketCount.toString())
    }

    private fun tableRow(cells: List<String>, tag: String) =
        cells.joinToString("", "<tr>", "</tr>") {
            "<$tag style=\"padding:6px 8px;border-bottom:1px solid #dce3ed;text-align:left\">${escape(it)}</$tag>"
        }

    private fun distribution(counts: Map<String, Int>) =
        counts.entries.joinToString(", ") { "${it.key} ${it.value}개" }.ifEmpty { "—" }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}

package collector.trend

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToLong
import collector.report.MailTheme as T

class TrendReportRenderer {
    data class Rendered(val subject: String, val html: String, val text: String)

    fun render(report: TrendReport): Rendered {
        val date = LocalDate.parse(report.date)
        // ISO weeks start on Monday. The Thursday determines the month and its ordinal Thursday.
        val thursday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays(3)
        val week = (thursday.dayOfMonth - 1) / 7 + 1
        val visibleCandidates = report.rising.count { it.candidate }
        val totalCandidates = report.themes.count { it.candidate }
        val hiddenCandidates = totalCandidates - visibleCandidates
        val overflow = if (hiddenCandidates > 0) " 외 $hiddenCandidates" else ""
        val heading = if (report.insufficientData) "테마 ${report.themes.size} · 데이터 부족"
            else "뜨는 테마 ${report.rising.size} · 분석 후보 ◆$visibleCandidates$overflow"
        val subject = "[기술 트렌드] ${thursday.monthValue}월 ${week}주 — $heading"
        val candidateLegend = "◆ = 사업성 분석을 요청할 만한 테마"
        val notice = "데이터 부족 · 증가율과 분석 후보는 데이터가 충분히 쌓이면 표시됩니다."
        val themeHeaders = listOf("테마", "이번 주", "증가", "출처", "요청·불편", "이미 나온 제품")
        val repositoryHeaders = listOf("저장소", "설명", "하루 스타 증가", "언어", "라이선스", "포크 비율", "테마")
        val repositoryRows = report.repositories.map {
            val name = it.name + if (it.suspected) " (스타 조작 의심)" else ""
            val description = if (it.description.length > 80) it.description.take(79) + "…" else it.description
            listOf(name, description, it.starsPerDay.roundToLong().toString(), it.language ?: "—",
                it.license ?: "—", it.forkRatio?.let(TrendAnalyzer::decimal) ?: "—", it.themeName ?: "—")
        }
        val overheated = report.spam.map { "저장소 몰림 · ${it.name} 저장소 ${it.count}개 · ${it.dates.joinToString(", ")}" } +
            report.themes.filter { it.overheated }.map { "제품 몰림 · ${it.name} · 이미 나온 제품 ${it.marketCount}건" }
        val distributions = listOf("언어 · ${distribution(report.languages)}", "라이선스 · ${distribution(report.licenses)}")
        val status = listOf("기간 ${report.startDate} ~ ${report.date} · 데이터 ${report.dataDays}일",
            "테마에 못 넣은 반복 글 ${report.unmappedRepeatCount}건",
            "수집 실패 출처 · ${report.failedSources.joinToString(", ").ifEmpty { "없음" }}",
            "너무 넓어 제외한 태그 · ${report.broadTags.joinToString(", ").ifEmpty { "없음" }}",
            "너무 드문 태그 ${report.sparseTagCount}개 제외") +
            listOfNotNull(report.backfillDays.takeIf { it > 0 }?.let {
                "과거 채우기 ${it}일(HN·GitHub만, 점수는 채운 시점 기준)"
            })
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
            if (totalCandidates > 0) appendLine(candidateLegend)
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
            appendLine("\n몰림(제품·저장소)")
            overheated.ifEmpty { listOf("없음") }.forEach { appendLine(it) }
            appendLine("\n데이터 상태")
            status.forEach { appendLine(it) }
        }
        val h2 = { title: String -> "<h2 style=\"${T.h2}\">${escape(title)}</h2>" }
        val p = { line: String -> "<p style=\"${T.p}\">${escape(line)}</p>" }
        val body = buildString {
            appendLine("<h1 style=\"${T.h1}\">기술 트렌드 · ${thursday.monthValue}월 ${week}주</h1>")
            appendLine("<p style=\"${T.meta}\">${escape(heading)} · ${escape(report.startDate)} ~ ${escape(report.date)}</p>")
            if (totalCandidates > 0) appendLine("<p style=\"${T.meta}\">$candidateLegend</p>")
            if (report.insufficientData) appendLine(T.banner(escape(notice), T.Tone.WARNING))
            if (report.summary.isNotEmpty()) {
                appendLine("<ul style=\"${T.muted};padding-left:32px\">")
                report.summary.forEach { appendLine("<li style=\"margin:2px 0\">${escape(it)}</li>") }
                appendLine("</ul>")
            }
            for ((title, headers, rows) in tables) {
                appendLine(h2(title))
                if (title == "지는 테마" && rows.isEmpty()) {
                    appendLine("<p style=\"${T.meta}\">해당 없음</p>")
                    continue
                }
                rows.forEach { appendLine(rowBlock(headers, it)) }
            }
            appendLine(h2("언어·라이선스 분포"))
            distributions.forEach { appendLine(p(it)) }
            appendLine(h2("몰림(제품·저장소)"))
            overheated.ifEmpty { listOf("없음") }.forEach { appendLine(p(it)) }
            appendLine("<div style=\"${T.footer}\"><p style=\"margin:0 0 4px;font-weight:600;color:${T.tokens.getValue("text-2")}\">데이터 상태</p>")
            status.forEach { appendLine("<p style=\"margin:0\">${escape(it)}</p>") }
            appendLine("</div>")
        }
        return Rendered(subject, T.document(escape(subject), body), text)
    }

    private fun themeRow(row: ThemeRow): List<String> {
        val name = (if (row.candidate) "◆ " else "") + row.id + (if (row.overheated) " (제품 몰림)" else "")
        val growth = TrendAnalyzer.growthLabel(row) + if (row.matchedSources) " (HN·GH 새 항목 기준)" else ""
        val count = (row.weeklyCounts.lastOrNull() ?: 0).toString() +
            row.comparableCount?.takeIf { row.matchedSources }?.let { " (비교 $it)" }.orEmpty()
        return listOf(name, count, growth,
            row.sources.size.toString(), row.demandCount.toString(), row.marketCount.toString())
    }

    /**
     * 표 한 행 = 한 덩어리: 첫 칸(이름) 굵게 → 짧은 칸은 "머리 값 · …" 한 줄 → 긴 칸(설명·예시)은 아래 줄.
     * 6~7열 표는 폰 메일 폭(360~400px)에서 넘치므로 표(table) 대신 이렇게 쌓는다(2026-11-23).
     */
    private fun rowBlock(headers: List<String>, cells: List<String>): String {
        val long = setOf("설명", "예시")
        val pairs = headers.indices.drop(1).filter { headers[it] !in long }
            .joinToString(" · ") { "${escape(headers[it])} <span style=\"color:${T.tokens.getValue("text")}\">${escape(cells[it])}</span>" }
        val extra = headers.indices.filter { headers[it] in long && cells[it] != "—" }
            .joinToString("") {
                "<div style=\"margin-top:2px;color:${T.tokens.getValue("text-2")}\">" +
                    "<span style=\"font-size:12px;color:${T.tokens.getValue("text-3")}\">${escape(headers[it])}</span> ${escape(cells[it])}</div>"
            }
        return "<div style=\"padding:8px 0;border-bottom:1px solid ${T.BORDER};overflow-wrap:anywhere\">" +
            "<div style=\"${T.strong}\">${escape(cells[0])}</div>" +
            "<div style=\"font-size:12px;line-height:1.5;color:${T.tokens.getValue("text-3")};word-break:keep-all\">$pairs</div>$extra</div>"
    }

    private fun distribution(counts: Map<String, Int>) =
        counts.entries.joinToString(", ") { "${it.key} ${it.value}개" }.ifEmpty { "—" }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}

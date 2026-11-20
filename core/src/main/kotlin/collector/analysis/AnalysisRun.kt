package collector.analysis

import collector.dev.outputJson
import collector.dev.readJson
import collector.dev.writeAtomically
import collector.llm.Effort
import collector.llm.LlmCall
import collector.llm.StageModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.util.Locale

@Serializable
internal data class AnalysisRun(
    val id: String, val createdAt: String, val trendDate: String, val themes: List<String>,
    val model: StageModel, val effort: Effort, val estimatedUsd: Double, val actualUsd: Double,
    val llmCalls: List<LlmCall>, val input: InputSummary, val opportunities: List<JudgedOpportunity>,
    val judgment: String, val issues: List<String>,
    val fit: AnalysisFit? = null,
    val budgetWarnings: List<String> = emptyList(),
    val promptVersions: Map<String, String> = emptyMap(),
    val inputHash: String? = null,
    val judgmentSettingsHash: String? = null
) {
    @Serializable data class InputSummary(
        val evidenceCount: Int, val characters: Int, val reportUrlMismatches: Int = 0
    )
}

@Serializable
internal data class AnalysisFit(
    val evidencePerTheme: Int, val effort: Effort, val themes: List<String>, val step: Int, val maxOutputTokens: Int,
    val evidenceCounts: Map<String, Int> = emptyMap()
) {
    fun description() = "근거 ${evidencePerTheme}건·추론 ${effort.name.lowercase()} · 테마 ${themes.joinToString()}"
    fun display() = "맞춤 실행: ${description()}"
}

internal class AnalysisReports(private val directory: File) {
    fun list(): List<AnalysisRun> = directory.listFiles().orEmpty()
        .filter { it.isFile && it.extension == "json" }
        .mapNotNull { readJson<AnalysisRun>(it) }.sortedByDescending { it.createdAt }

    fun resultFile(id: String): File {
        require(id.matches(Regex("[0-9]{8}-[0-9]{9}-[a-f0-9]{8}"))) { "분석 ID 형식이 올바르지 않습니다." }
        return directory.resolve("$id.json")
    }

    fun save(run: AnalysisRun): File {
        val title = "사업성 분석 · ${run.themes.joinToString()} (${run.trendDate})"
        val opportunities = run.opportunities.mapIndexed { index, entry ->
            "${index + 1}. ${entry.score?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "INVALID"} · " +
                "${entry.validated.name} · ${entry.reason}" + if (entry.provisional) " · 잠정" else ""
        }
        val usage = "LLM ${run.llmCalls.size}회 · 입력 ${run.llmCalls.sumOf { it.inputTokens }} 토큰 " +
            "(캐시 ${run.llmCalls.sumOf { it.cachedInputTokens }}) · 출력 ${run.llmCalls.sumOf { it.outputTokens }} 토큰 · " +
            "추정 ${usd(run.estimatedUsd)} · 실제 ${usd(run.actualUsd)}"
        val fit = run.fit?.takeIf { it.step > 0 }?.display()
        val notes = run.budgetWarnings + run.issues
        val text = listOf(title, "", run.judgment) + listOfNotNull(fit) +
            listOf("", "검토 분야") + opportunities + listOf("", usage) + notes
        val html = buildString {
            appendLine("<!doctype html><html lang=\"ko\"><meta charset=\"utf-8\"><title>${escape(title)}</title>")
            appendLine("<body><h1>${escape(title)}</h1><section><h2>판단</h2><p>${escape(run.judgment)}</p></section>")
            fit?.let { appendLine("<p>${escape(it)}</p>") }
            appendLine("<section><h2>검토 분야</h2><ul>")
            opportunities.forEach { appendLine("<li>${escape(it)}</li>") }
            appendLine("</ul></section><section><h2>사용량·비용</h2><p>${escape(usage)}</p></section>")
            notes.forEach { appendLine("<p>${escape(it)}</p>") }
            appendLine("</body></html>")
        }
        val result = resultFile(run.id)
        val files = linkedMapOf(
            directory.resolve("${run.id}.txt") to text.joinToString("\n", postfix = "\n"),
            directory.resolve("${run.id}.html") to html,
            result to outputJson.encodeToString(run)
        )
        val written = mutableListOf<File>()
        try {
            for ((file, content) in files) {
                file.writeAtomically(content)
                written += file
            }
        } catch (failure: Exception) {
            for (file in written.asReversed()) {
                try {
                    Files.deleteIfExists(file.toPath())
                } catch (cleanup: Exception) {
                    failure.addSuppressed(cleanup)
                }
            }
            throw failure
        }
        return result
    }

    fun detail(id: String, selection: String): String {
        val run = requireNotNull(readJson<AnalysisRun>(resultFile(id))) { "저장된 분석이 없습니다 · $id" }
        val entry = selection.toIntOrNull()?.let { run.opportunities.getOrNull(it - 1) }
            ?: run.opportunities.find { it.id == selection }
        requireNotNull(entry) { "저장된 기회 분야가 없습니다 · $selection" }
        val opportunity = entry.validated
        return buildString {
            run.fit?.takeIf { it.step > 0 }?.let { appendLine(it.display()) }
            appendLine("${opportunity.name} · ${opportunity.problem}")
            appendLine("대상 고객 · ${opportunity.targetCustomer}")
            for ((key, label) in scoreLabels) {
                val score = opportunity.scores[key]
                appendLine("$label · ${score?.score?.toInt() ?: "누락"} · ${score?.reason.orEmpty()}")
            }
            entry.gates.forEach { appendLine(it) }
            appendLine(entry.reason)
            opportunity.refs().map { it.url }.distinct().take(3).forEach { appendLine("근거 · $it") }
            append("무엇이 바뀌면 의뢰 가능한가 · ${opportunity.whatWouldChange}")
        }
    }

    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}

internal fun usd(amount: Double): String = String.format(Locale.ROOT, "USD %.6f", amount)

package collector.trend

import collector.dev.outputJson
import collector.dev.writeAtomically
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.LocalDate

internal fun publishTrend(
    out: File,
    date: LocalDate,
    beforeReplace: (File) -> Unit = {},
    render: (TrendReport) -> TrendReportRenderer.Rendered = TrendReportRenderer()::render
): String {
    val (report, themes) = TrendAnalyzer.analyzeWithThemes(TrendInput.read(out, date), date)
    val rendered = render(report)
    val files = mapOf(
        "$date.html" to rendered.html,
        "$date.txt" to rendered.text,
        "themes.json" to outputJson.encodeToString(themes),
        // Publish the official result only after its companion files.
        "$date.json" to outputJson.encodeToString(report)
    )
    val trend = out.resolve("trend").apply { mkdirs() }
    val staged = linkedMapOf<File, Path>()
    val replaced = mutableListOf<Pair<File, String?>>()
    try {
        for ((name, content) in files) {
            val temporary = Files.createTempFile(trend.toPath(), ".collector-", ".tmp")
            staged[trend.resolve(name)] = temporary
            Files.writeString(temporary, content)
        }
        for ((file, temporary) in staged) {
            val previous = if (file.isFile) file.readText() else null
            beforeReplace(file)
            Files.move(temporary, file.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
            replaced += file to previous
        }
    } catch (failure: Exception) {
        for ((file, previous) in replaced.asReversed()) {
            try {
                if (previous == null) Files.delete(file.toPath())
                else file.writeAtomically(previous)
            } catch (restoreFailure: Exception) {
                failure.addSuppressed(restoreFailure)
            }
        }
        throw failure
    } finally {
        staged.values.forEach { Files.deleteIfExists(it) }
    }
    return rendered.subject
}

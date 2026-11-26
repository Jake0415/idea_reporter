package collector.dev

import collector.model.Feedback
import collector.model.MetricSnapshot
import java.time.Instant
import collector.model.Signal
import collector.model.TrackedIdea
import collector.pipeline.PipelineResult
import collector.pipeline.RunHistory
import collector.pipeline.TrackClassification
import collector.stage.HistoryHit
import collector.stage.TrackedStore
import collector.stage.words
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.ZoneId
import collector.pipeline.requestedCreationDay

internal val outputJson = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

class FileHistorySearch(private val out: File, private val historyDays: Int,
                        override val requestedRunZone: ZoneId = ZoneId.systemDefault()) : RunHistory {
    private val runsByDate by lazy {
        out.listFiles().orEmpty().mapNotNull { file ->
            val date = if (file.extension == "json") runCatching { LocalDate.parse(file.nameWithoutExtension) }.getOrNull() else null
            date?.let { readJson<PipelineResult>(file)?.let { run -> date to run } }
        }.toMap()
    }

    override fun runs(today: LocalDate, days: Int): List<PipelineResult> =
        runsByDate.entries.filter { it.key in today.minusDays(days.toLong())..today.minusDays(1) }
            .sortedByDescending { it.key }.map { it.value }

    override fun stateRuns(today: LocalDate, days: Int): List<PipelineResult> =
        runs(today, days).map { run ->
            readJson<PipelineResult>(stateFile(run.report.runDate)) ?: run
        }

    internal fun stateFile(date: String) = out.resolve("state/$date.json")

    override fun trackClassified(): Map<String, TrackClassification> =
        readJson<Map<String, TrackClassification>>(out.resolve("track-classified.json")).orEmpty()

    private fun archives(directory: String = "runs") =
        out.resolve(directory).listFiles().orEmpty().filter { it.isFile && it.extension == "json" }

    override fun requestedRuns(today: LocalDate, days: Int): List<PipelineResult> =
        archives("requested").asSequence().mapNotNull { readJson<PipelineResult>(it) }.filter { run ->
            val day = runCatching { run.requestedCreationDay(requestedRunZone) }.getOrNull()
            day != null && day in today.minusDays(days.toLong())..today
        }.sortedByDescending { Instant.parse(it.report.startedAt) }.toList()

    override fun cardRun(cardId: String): PipelineResult? =
        runsByDate.values.firstOrNull { run -> run.cards.any { it.id == cardId } }
            ?: archives("requested").asSequence().mapNotNull { readJson<PipelineResult>(it) }
                .firstOrNull { run -> run.cards.any { it.id == cardId } }
            ?: archives().asSequence().mapNotNull { readJson<PipelineResult>(it) }
                .firstOrNull { run -> run.cards.any { it.id == cardId } }

    override fun metrics(now: Instant): List<MetricSnapshot> =
        readJson<List<MetricSnapshot>>(out.resolve("metrics.json")).orEmpty()
            .filter { it.at >= now.minusSeconds(30L * 86400) }

    internal fun metricsContent(snapshots: List<MetricSnapshot>, now: Instant) =
        outputJson.encodeToString((metrics(now) + snapshots).distinct())

    fun archivedCost(start: LocalDate, end: LocalDate, reserved: Boolean = false, skipStartedAt: String? = null): Double =
        (archives() + archives("runs/failed"))
            .sumOf { file ->
                val day = runCatching { LocalDate.parse(file.name.take(10)) }.getOrNull()
                if (day == null || day !in start..end) return@sumOf 0.0
                val report = readJson<PipelineResult>(file)?.report ?: return@sumOf 0.0
                if (report.startedAt == skipStartedAt) return@sumOf 0.0
                val calls = report.llmCalls
                calls.sumOf { if (reserved) maxOf(it.costUsd, it.reservedUsd) else it.costUsd }
            }

    override fun feedback(): List<Feedback> = readJson<List<Feedback>>(out.resolve("feedback.json")).orEmpty()
        .withIndex().sortedWith(compareByDescending<IndexedValue<Feedback>> { it.value.at }.thenByDescending { it.index })
        .map { it.value }.filter { cardRun(it.cardId) != null }

    override fun search(signal: Signal, today: LocalDate): List<HistoryHit> {
        val terms = (words(signal.item.title).take(3) + signal.classification.tags.flatMap(::words)).toSet()
        return runs(today, historyDays).flatMap { run ->
            val date = run.report.runDate
            val tags = run.classified.associate { it.item.key to it.classification.tags }
            run.items.map { item ->
                HistoryHit(date, item.title, item.snippet.take(300), item.key) to (item.tags + tags[item.key].orEmpty())
            } + run.cards.map { HistoryHit(date, it.title, it.oneLiner.take(300), it.id) to it.tags }
        }.map { (hit, tags) ->
            hit to words(hit.title + " " + tags.joinToString(" ")).count { it in terms }
        }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }.distinctBy { it.id }.take(3)
    }
}

class JsonFileTrackedStore(private val file: File) : TrackedStore {
    override fun load(): List<TrackedIdea> = if (file.isFile) outputJson.decodeFromString(file.readText()) else emptyList()
    override fun save(ideas: List<TrackedIdea>) = file.writeAtomically(outputJson.encodeToString(ideas))
}

internal inline fun <reified T> readJson(file: File): T? = try {
    if (file.isFile) outputJson.decodeFromString<T>(file.readText()) else null
} catch (_: SerializationException) {
    null
}

internal fun File.writeAtomically(content: String) {
    absoluteFile.parentFile.mkdirs()
    val temporary = Files.createTempFile(absoluteFile.parentFile.toPath(), ".collector-", ".tmp")
    try {
        Files.write(temporary, content.toByteArray())
        Files.move(temporary, toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

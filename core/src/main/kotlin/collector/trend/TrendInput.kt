package collector.trend

import collector.dev.readJson
import collector.model.Feedback
import collector.model.MetricSnapshot
import collector.model.Signal
import collector.pipeline.PipelineResult
import java.io.File
import java.time.Instant
import java.time.LocalDate

data class TrendInput(
    val runs: Map<LocalDate, PipelineResult>,
    val metrics: List<MetricSnapshot> = emptyList(),
    val feedback: List<Feedback> = emptyList(),
    val themeMap: ThemeMap = ThemeMap(),
    // Earliest known classifications from all executions through the report date.
    val earlierClassifications: List<Signal> = emptyList(),
    val backfillDates: Set<LocalDate> = emptySet()
) {
    companion object {
        private val runName = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}\\.json")

        private fun preferredRun(state: PipelineResult?, full: PipelineResult?, published: PipelineResult?): PipelineResult? {
            if (state == null) return published ?: full
            if (full == null) return state
            return state.copy(
                items = state.items.ifEmpty { full.items },
                dropped = state.dropped.ifEmpty { full.dropped },
                cards = state.cards.ifEmpty { published?.cards.orEmpty().ifEmpty { full.cards } },
                trackingOnlyKeys = state.trackingOnlyKeys + full.trackingOnlyKeys
            )
        }

        fun read(out: File, date: LocalDate = LocalDate.now(), config: TrendConfig = TrendConfig.defaults): TrendInput {
            val state = out.resolve("state")
            val archives = out.resolve("runs").listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(".json") && it.name.getOrNull(10) == '-' }
                .groupBy { it.name.take(10) }
            val backfill = out.resolve("backfill")
            val dates = sequenceOf(out, state, backfill).flatMap { it.listFiles().orEmpty().asSequence() }
                .filter { it.isFile && runName.matches(it.name) }.map { it.nameWithoutExtension }
                .plus(archives.keys)
                .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
                .filter { it <= date }.distinct().sorted()
            val start = date.minusDays(config.days - 1L)
            val runs = linkedMapOf<LocalDate, PipelineResult>()
            val originals = linkedMapOf<String, Signal>()
            val backfillDates = mutableSetOf<LocalDate>()
            fun index(run: PipelineResult) {
                run.classified.filter { it.item.key !in run.trackingOnlyKeys }
                    .forEach { originals.putIfAbsent(it.item.key, it) }
            }
            for (day in dates) {
                val stateRun = readJson<PipelineResult>(state.resolve("$day.json"))
                val published = readJson<PipelineResult>(out.resolve("$day.json"))
                    ?.takeIf { it.report.runDate == day.toString() }
                val fullRuns = listOfNotNull(published) + archives[day.toString()].orEmpty()
                    .mapNotNull { readJson<PipelineResult>(it) }
                    .filter { it.report.runDate == day.toString() }
                val historical = backfill.resolve("$day.json").takeIf { stateRun == null && fullRuns.isEmpty() }
                    ?.let { readJson<PipelineResult>(it) }
                    ?.takeIf { it.report.runDate == day.toString() }
                (fullRuns + listOfNotNull(stateRun, historical))
                    .sortedBy { Instant.parse(it.report.startedAt) }
                    .forEach(::index)
                if (day >= start) {
                    val latest = fullRuns.maxByOrNull { Instant.parse(it.report.startedAt) }
                    val run = preferredRun(stateRun, latest, published) ?: historical?.also { backfillDates += day }
                        ?: continue
                    runs[day] = run
                }
            }
            return TrendInput(runs, readJson<List<MetricSnapshot>>(out.resolve("metrics.json")).orEmpty(),
                readJson<List<Feedback>>(out.resolve("feedback.json")).orEmpty(),
                readJson<ThemeMap>(out.resolve("trend/themes.json")) ?: ThemeMap(), originals.values.toList(), backfillDates)
        }
    }
}

internal fun hasDailyRun(out: File, day: LocalDate): Boolean =
    out.resolve("$day.json").isFile || out.resolve("state/$day.json").isFile ||
        out.resolve("runs").listFiles().orEmpty().any {
            it.isFile && it.name.startsWith("$day-") && it.extension == "json"
        }

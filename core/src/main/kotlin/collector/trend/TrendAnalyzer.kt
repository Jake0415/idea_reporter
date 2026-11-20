package collector.trend

import collector.model.DropReason
import collector.model.Feedback
import collector.model.Item
import collector.model.MetricSnapshot
import collector.model.Signal
import collector.pipeline.PipelineResult
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToLong

object TrendAnalyzer {
    private data class Observation(val date: LocalDate, val signal: Signal, val fresh: Boolean)
    private data class DatedItem(val date: LocalDate, val item: Item)

    fun analyze(input: TrendInput, date: LocalDate, config: TrendConfig = TrendConfig.defaults,
                zone: ZoneId = ZoneId.systemDefault()): TrendReport = analyzeWithThemes(input, date, config, zone).first

    internal fun analyzeWithThemes(input: TrendInput, date: LocalDate, config: TrendConfig = TrendConfig.defaults,
                zone: ZoneId = ZoneId.systemDefault()): Pair<TrendReport, ThemeMap> {
        val start = date.minusDays(config.days - 1L)
        val weekStart = date.minusDays(config.weekDays - 1L)
        val runs = input.runs.filterKeys { it in start..date }.toSortedMap()
        fun week(dateOfRun: LocalDate) = 3 - (ChronoUnit.DAYS.between(dateOfRun, date) / config.weekDays).toInt()
        val runDays = runs.keys.groupingBy(::week).eachCount()
        val priorWeeks = (0..2).filter { it in runDays }
        val backfillDays = runs.keys.count { it in input.backfillDates }
        val comparableSources = setOf("hn", "github")
        val matchSources = runs.keys.any { it in input.backfillDates && week(it) in 0..3 }
        fun priorDailyAverage(count: (Int) -> Int) =
            priorWeeks.map { count(it).toDouble() / runDays.getValue(it) }.average()
        val insufficient = runs.size < config.minimumDataDays || priorWeeks.size < 2
        val first = linkedMapOf<String, Signal>()
        input.earlierClassifications.forEach { first.putIfAbsent(it.item.key, it) }
        val observations = mutableListOf<Observation>()
        val currentItems = mutableListOf<DatedItem>()
        var unmapped = 0
        for ((day, run) in runs) {
            for (signal in run.classified.filter { it.item.key !in run.trackingOnlyKeys }) {
                first.putIfAbsent(signal.item.key, signal)
                if (signal.classification.profile == "none") continue
                observations += Observation(day, signal, true)
                if (day >= weekStart) currentItems += DatedItem(day, signal.item)
            }
            for (drop in run.dropped.filter {
                it.reason == DropReason.ALREADY_SEEN && it.item.key !in run.trackingOnlyKeys
            }.distinctBy { it.item.key }) {
                val original = first[drop.item.key]
                if (original?.classification?.profile == "none") continue
                if (day >= weekStart) currentItems += DatedItem(day, drop.item)
                if (original == null || original.classification.tags.isEmpty()) unmapped++
                else observations += Observation(day, original.copy(item = drop.item), false)
            }
        }
        val weeklyObservations = observations.filter { week(it.date) in 0..3 }
            .sortedWith(compareByDescending<Observation> { it.fresh }.thenByDescending { it.date })
            .distinctBy { week(it.date) to it.signal.item.key }
        val normalizer = TagNormalizer(config, input.themeMap.overrides)
        fun taggedRows(row: Observation): List<Pair<String, Observation>> {
            val classification = row.signal.classification
            return normalizer.themes(classification.tags, classification.topicKey).map { it to row }
        }
        val comparableByTheme = if (matchSources) observations.asSequence()
            .filter { week(it.date) in 0..3 && it.fresh && (it.signal.item.source in comparableSources ||
                it.signal.item.alsoSeenIn.any(comparableSources::contains)) }
            .distinctBy { it.signal.item.key }
            .flatMap(::taggedRows)
            .groupBy({ it.first }, { it.second })
        else emptyMap()
        val tagged = weeklyObservations.flatMap(::taggedRows).groupBy({ it.first }, { it.second })
        val tagDays = observations.asSequence().filter { week(it.date) in 0..3 }.flatMap(::taggedRows)
            .groupBy({ it.first }, { it.second.date }).mapValues { (_, days) -> days.toSet().size }
        val sparseTags = tagged.filter { (tag, rows) ->
            rows.distinctBy { it.signal.item.key }.size < config.minThemeItems &&
                tagDays.getValue(tag) < config.minThemeDays
        }.keys
        val observedItems = weeklyObservations.map { it.signal.item.key }.toSet().size
        val broadTags = tagged.filterValues { rows ->
            rows.map { it.signal.item.key }.toSet().size * 10L >= observedItems * 3L
        }.keys.sorted()
        val themeMap = ThemeMap(input.themeMap.overrides, ThemeMapper.VERSION, broadTags)
        val byTheme = tagged - broadTags.toSet() - sparseTags
        val examplesByTheme = byTheme.mapValues { (_, rows) -> topicExamples(rows.map { it.signal }, config) }
        val names = examplesByTheme.mapValues { (id, examples) ->
            if (examples.isEmpty()) id else "$id · 예: ${examples.take(2).joinToString(", ")}"
        }
        val themesByItem = byTheme.flatMap { (id, rows) ->
            rows.filter { it.date >= weekStart }.map { it.signal.item.key to id }
        }.groupBy({ it.first }, { it.second }).mapValues { (_, ids) -> ids.sorted() }
        val github = currentItems.sortedByDescending { it.date }.map { it.item }
            .filter { it.source == "github" }.distinctBy { it.key }
        val metrics = input.metrics.filter { it.at.atZone(zone).toLocalDate() in weekStart..date }.groupBy { it.itemKey }
        val repositories = github.map { item ->
            val ids = themesByItem[item.key].orEmpty()
            repository(item, ids.firstOrNull(), ids.joinToString(", ").ifEmpty { null },
                metrics[item.key].orEmpty(), date, zone, config)
        }.sortedWith(compareByDescending<RepositoryTrend> { it.starsPerDay }.thenBy { it.itemKey })
        val repositoriesByTheme = repositories.flatMap { repo ->
            themesByItem[repo.itemKey].orEmpty().map { it to repo }
        }.groupBy({ it.first }, { it.second })
        val cards = runs.values.flatMap { it.cards }.associate { card ->
            card.id to (card.techSignals + card.demandSignals + card.competitors).map { it.itemId }.toSet()
        }
        val feedback = input.feedback.filter { it.cardId in cards }
        val priorByTheme = mutableMapOf<String, Double>()
        val themes = byTheme.map { (id, rows) ->
            val weeks = (0..3).map { index -> rows.filter { week(it.date) == index } }
            val counts = weeks.map { it.size }
            val comparableWeeks = if (matchSources) (0..3).map { index ->
                comparableByTheme[id].orEmpty().filter { week(it.date) == index }
            } else weeks
            val prior = priorDailyAverage { comparableWeeks[it].size }
            priorByTheme[id] = prior
            val thisWeek = weeks.last().map { it.signal }
            val comparableWeek = comparableWeeks.last().map { it.signal }
            val comparableDays = comparableWeeks.last().map { it.date }.toSet().size
            val currentDays = runDays[3] ?: 1
            val weeklyPrior = prior * config.weekDays
            val isNew = !insufficient && weeklyPrior == 0.0 && comparableWeek.size >= config.newMinCount &&
                comparableDays >= config.newMinDays
            // Normalize this week to a full week like the prior average, so weeks with missing run days are not undercounted.
            val weeklyCurrent = comparableWeek.size.toDouble() / currentDays * config.weekDays
            val growth = if (!insufficient && weeklyPrior >= 1.0) (weeklyCurrent + 1) / (weeklyPrior + 1) else null
            val sources = thisWeek.flatMap { listOf(it.item.source) + it.item.alsoSeenIn }.distinct().sorted()
            val demand = thisWeek.count { it.classification.profile == "demand-signal" }
            val market = thisWeek.count { it.classification.profile == "market-signal" }
            val comparableMarket = comparableWeek.count { it.classification.profile == "market-signal" }
            val candidateDemand = comparableWeek.count { it.classification.profile == "demand-signal" }
            val priorMarket = priorWeeks.map { index ->
                comparableWeeks[index].count { it.signal.classification.profile == "market-signal" }.toDouble() / runDays.getValue(index)
            }.average()
            val overheated = !insufficient && comparableMarket >= config.marketCount &&
                comparableMarket.toDouble() / currentDays >= config.marketGrowth * priorMarket
            val repos = repositoriesByTheme[id].orEmpty()
            val keys = rows.map { it.signal.item.key }.toSet()
            val reactions = feedback.filter { reaction -> cards.getValue(reaction.cardId).any { it in keys } }
            val evidence = rows.map { it.signal }.filter { it.classification.score != null }
                .groupBy { it.classification.profile }.toSortedMap().mapValues { (_, signals) ->
                    signals.sortedWith(compareByDescending<Signal> { it.classification.score }.thenBy { it.item.key })
                        .distinctBy { it.item.key }.take(config.evidenceLimit).map {
                            TrendEvidence(it.item.key, it.item.title, it.item.url, it.item.source, checkNotNull(it.classification.score))
                        }
                }
            ThemeRow(id = id, name = names.getValue(id), weeklyCounts = counts,
                newCount = rows.filter { it.date >= weekStart && it.fresh }.distinctBy { it.signal.item.key }.size,
                growth = growth, isNew = isNew, sources = sources, demandCount = demand, marketCount = market,
                medianStarsPerDay = median(repos.map { it.starsPerDay }), medianForkRatio = median(repos.mapNotNull { it.forkRatio }),
                languages = distribution(repos.mapNotNull { it.language }), licenses = distribution(repos.mapNotNull { it.license }),
                stars = reactions.count { it.kind == Feedback.Kind.STAR },
                notInterested = reactions.count { it.kind == Feedback.Kind.NOT_INTERESTED },
                evidence = evidence, overheated = overheated, examples = examplesByTheme.getValue(id),
                candidate = CandidateMarker.matches(growth, isNew, candidateDemand, sources.size, overheated,
                    comparableWeek.size, comparableDays, config), matchedSources = matchSources,
                comparableCount = comparableWeek.size.takeIf { matchSources })
        }.sortedBy { it.id }
        val rising = themes.sortedWith(compareByDescending<ThemeRow> { it.isNew }.thenByDescending { it.growth }
            .thenByDescending { it.weeklyCounts.last() }.thenBy { it.id })
            .filter { row ->
                val count = if (row.matchedSources) row.comparableCount ?: 0 else row.weeklyCounts.last()
                // Rising means new or growing; with insufficient data every growth is null, so rank by count only.
                count >= config.risingCount && (insufficient || row.isNew || (row.growth ?: 0.0) > 1.0)
            }
        val risingRows = rising
            .sortedByDescending { it.candidate }.take(config.risingLimit.coerceAtMost(10))
        val falling = themes.filter { row -> priorByTheme.getValue(row.id) * config.weekDays >= config.fallingAverage &&
            row.growth != null && row.growth <= config.fallingGrowth }
            .sortedWith(compareBy<ThemeRow> { it.growth }.thenBy { it.id }).take(config.fallingLimit)
        val spam = spamClusters(runs.filterKeys { it >= weekStart }, config)
        val summary = listOfNotNull(
            rising.firstOrNull()?.let { "가장 뜬 테마 · ${it.name} ${growthLabel(it)}" },
            repositories.firstOrNull()?.let { "가장 빠른 저장소 · ${it.name} 하루 ${it.starsPerDay.roundToLong()}★" },
            spam.firstOrNull()?.let { "과열 경고 · ${it.name} 저장소 ${it.count}개" }
                ?: themes.firstOrNull { it.overheated }?.let { "과열 경고 · ${it.name} 시장 신호 ${it.marketCount}건" }
        )
        return TrendReport(date.toString(), start.toString(), insufficient, runs.size, themes, risingRows, falling,
            repositories.take(config.repositoryLimit), distribution(repositories.mapNotNull { it.language }),
            distribution(repositories.mapNotNull { it.license }), spam, summary, unmapped,
            runs.values.flatMap { run -> run.report.failedSources.map { it.source } }.distinct().sorted(), broadTags,
            backfillDays, sparseTags.size) to themeMap
    }

    private fun topicExamples(signals: List<Signal>, config: TrendConfig): List<String> {
        val aliases = ThemeMapper.groupTopics(signals, config)
        return signals.filter { it.classification.topicLabel.isNotBlank() }
            .groupBy { aliases[it.classification.topicKey] ?: it.classification.topicLabel }
            .values.map { group ->
                val label = group.groupingBy { it.classification.topicLabel }.eachCount().toSortedMap()
                    .maxBy { it.value }.key
                label to group.size
            }.groupingBy { it.first }.fold(0) { count, (_, size) -> count + size }
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(3).map { it.key }
    }

    private fun repository(item: Item, id: String?, themeName: String?, snapshots: List<MetricSnapshot>,
                           date: LocalDate, zone: ZoneId, config: TrendConfig): RepositoryTrend {
        val ordered = snapshots.sortedBy { it.at }
        val elapsed = if (ordered.size >= 2) Duration.between(ordered.first().at, ordered.last().at).toMillis() else 0L
        val age = Duration.between(item.publishedAt, date.atStartOfDay(zone).toInstant()).toMillis() / 86_400_000.0
        val pace = if (elapsed >= 86_400_000) {
            (ordered.last().rawScore.toDouble() - ordered.first().rawScore) / (elapsed / 86_400_000.0)
        } else item.rawScore.toDouble() / age.coerceAtLeast(1.0)
        val forks = item.extra["forks"]?.toIntOrNull()
        val ratio = forks?.takeIf { item.rawScore > 0 }?.toDouble()?.div(item.rawScore)
        val license = item.extra["license"]?.takeIf { it.isNotBlank() }
        return RepositoryTrend(item.key, item.title, item.snippet, item.extra["language"]?.takeIf { it.isNotBlank() },
            license, config.allowedLicenses.any { it.equals(license, ignoreCase = true) }, ratio, id, themeName, pace,
            item.rawScore >= config.suspectedMinStars && ratio != null && ratio < config.suspectedForkRatio)
    }

    private fun spamClusters(runs: Map<LocalDate, PipelineResult>, config: TrendConfig): List<SpamCluster> {
        val rows = runs.flatMap { (day, run) ->
            run.dropped.filter { it.reason == DropReason.SUSPECTED_SPAM && it.item.key !in run.trackingOnlyKeys }
                .map { DatedItem(day, it.item) }
        }
        val parents = IntArray(rows.size) { it }
        fun root(index: Int): Int {
            var current = index
            while (current != parents[current]) {
                parents[current] = parents[parents[current]]
                current = parents[current]
            }
            return current
        }

        val names = mutableMapOf<String, Int>()
        val descriptions = mutableMapOf<String, Int>()
        for ((index, row) in rows.withIndex()) {
            val name = row.item.title.substringAfter('/').lowercase().replace("-", "").replace("_", "")
            val description = row.item.snippet.trim().lowercase()
            if (name.isNotEmpty()) parents[root(index)] = root(names.getOrPut(name) { index })
            if (description.length >= 20) parents[root(index)] = root(descriptions.getOrPut(description) { index })
        }
        return rows.indices.groupBy(::root).values.mapNotNull { members ->
            val cluster = members.map(rows::get)
            val items = cluster.distinctBy { it.item.key }
            if (items.size < config.spamClusterSize) return@mapNotNull null
            val name = items.groupingBy { it.item.title.substringAfter('/') }.eachCount().toSortedMap()
                .maxBy { it.value }.key
            SpamCluster(name, items.size, cluster.map { it.date.toString() }.distinct().sorted())
        }.sortedWith(compareByDescending<SpamCluster> { it.count }.thenBy { it.name })
    }

    private fun median(values: List<Double>): Double? {
        val sorted = values.sorted()
        if (sorted.isEmpty()) return null
        return (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
    }

    private fun distribution(values: List<String>) = values.groupingBy { it }.eachCount().toSortedMap()

    internal fun decimal(value: Double) = String.format(Locale.ROOT, "%.2f", value)
    internal fun growthLabel(row: ThemeRow) = when {
        row.isNew -> "신규"
        row.growth != null -> "×${decimal(row.growth)}"
        else -> "—"
    }
}

package collector.pipeline

import collector.config.GitHubConfig
import collector.config.PipelineConfig
import collector.config.SourceConfig
import collector.llm.CostGuard
import collector.llm.LlmRouter
import collector.model.*
import collector.profile.ProfileLoader
import collector.prompt.PromptResolver
import collector.refine.UrlNormalizer
import collector.source.SourceAdapter
import collector.source.RedditAdapter
import collector.stage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class FullPipeline(
    private val config: PipelineConfig, private val profiles: ProfileLoader.ProfileSet, prompts: PromptResolver,
    router: LlmRouter, private val costs: CostGuard, private val http: OkHttpClient,
    private val history: RunHistory, private val trackedStore: TrackedStore,
    val collectOnly: Boolean = false,
    private val reddit: RedditAdapter? = null
) {
    private val classifier = Classifier(config, profiles, prompts, router)
    private val selector = Selector(config)
    private val generator = IdeaGenerator(config, prompts, router)
    private val updater = TrackingUpdater(prompts, router)

    internal fun tracked(): List<TrackedIdea> {
        if (collectOnly) return emptyList()
        val existing = trackedStore.load().toMutableList()
        val stars = history.feedback().distinctBy { it.cardId }.filter { it.kind == Feedback.Kind.STAR }
        for (star in stars) {
            if (existing.any { it.cardId == star.cardId } ||
                existing.count { it.status != TrackedIdea.Status.STOPPED } >= config.tracking.maxTracked) continue
            val run = history.cardRun(star.cardId) ?: continue
            val card = run.cards.first { it.id == star.cardId }
            val related = run.classified.filter { signal -> card.competitors.any { it.itemId == signal.item.key } }
            existing += TrackedIdea(UUID.randomUUID().toString(), card.id, card.title, card.category,
                TrackingKeywords.extract(card, run.classified), related.map { it.classification.topicLabel }.distinct(),
                TrackedIdea.Status.TRACKING, star.at ?: Instant.parse(run.report.startedAt), null, 0.0, TrackedIdea.Trend.FLAT)
        }
        return existing
    }

    internal suspend fun search(tracked: List<TrackedIdea>, sources: List<SourceConfig>, adapters: Map<String, SourceAdapter>,
                                now: Instant, excluded: List<String>, awaitDailyGitHub: suspend () -> Unit = {}): Pair<List<Item>, List<String>> = coroutineScope {
        val search = TrackingSearch(config.tracking)
        val github = Mutex()
        val jobs = tracked.filter { it.status != TrackedIdea.Status.STOPPED }.flatMap { idea ->
            sources.mapNotNull { source ->
                val adapter = adapters[source.sourceId] ?: return@mapNotNull null
                async {
                    val items = try {
                        suspend fun fetch() = withTimeoutOrNull(45_000) { search.fetch(idea, source, adapter, now, excluded) }
                        if (source is GitHubConfig) {
                            awaitDailyGitHub()
                            github.withLock { fetch() }
                        } else fetch()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (items == null) emptyList<Item>() to "추적 검색 실패 (${source.sourceId}, ${idea.id})"
                    else items to null
                }
            }
        }.awaitAll()
        val items = jobs.flatMap { it.first }.groupBy { UrlNormalizer.normalize(it.url) }.values.map { copies ->
            copies.first().copy(trackHint = copies.flatMap { it.trackHint }.distinct())
        }
        items to jobs.mapNotNull { it.second }
    }

    internal suspend fun run(base: PipelineResult, collected: List<Item>, tracking: List<Item>, tracked: List<TrackedIdea>,
                             now: Instant, today: LocalDate, onStage: (String) -> Unit): PipelineResult {
        val comments = CommentFetcher(http, config.commentsPerItem, reddit)
        val started = System.nanoTime()
        val date = today.toString()
        val stages = base.report.stages.toMutableList()
        val issues = base.report.issues.toMutableList()
        suspend fun <T> stage(name: String, input: Int, count: (T) -> Int, block: suspend () -> T): T {
            val index = stages.indexOfFirst { it.stage == name }
            if (index == -1) onStage(name)
            val start = System.nanoTime()
            return block().also {
                val millis = (System.nanoTime() - start) / 1_000_000
                val record = StageResult(name, input, count(it), millis + (stages.getOrNull(index)?.millis ?: 0))
                if (index == -1) stages += record else stages[index] = record
            }
        }
        val active = tracked.filter { it.status != TrackedIdea.Status.STOPPED }
        val trackingDays = active.maxOfOrNull {
            (Duration.between(it.startedAt, now).toDays() + 1).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        } ?: 0
        val historyDays = maxOf(config.historyDays, config.recentTopicDays, config.recentCardDays, 30)
        val priorRuns = history.runs(today, historyDays)
        val priorState = history.stateRuns(today, historyDays)
        val historicalTrackingRuns = if (trackingDays <= historyDays) priorState else history.stateRuns(today, trackingDays)
        val priorTopics = priorState.flatMap { it.topics }
        val previousUpdates = historicalTrackingRuns.flatMap { it.trackingUpdates }.groupBy { it.trackedId }
        val previousEvidence = buildMap {
            for (run in historicalTrackingRuns.filter { it.trackingUpdates.isNotEmpty() }) {
                val signals = run.classified.associateBy { it.item.key }
                for (update in run.trackingUpdates) for (key in update.itemKeys) {
                    signals[key]?.let { putIfAbsent(update.trackedId to key, it) }
                }
            }
        }
        val cardKeys = base.items.map { it.key }.toSet()
        val input = (base.items + tracking).groupBy { it.key }.values.map { copies ->
            copies.first().copy(trackHint = copies.flatMap { it.trackHint }.distinct())
        }
        val trackingKeys = tracking.mapTo(hashSetOf()) { it.key }
        val retentionCutoff = today.minusDays(30).toString()
        val reuseCutoff = today.minusDays(14).toString()
        val cache = history.trackClassified().filterValues { it.date >= retentionCutoff }.mapValues { (_, cached) ->
            cached.copy(classification = cached.classification.copy(trackedIdeaIds = emptyList()))
        }.toMutableMap()
        val reused = input.filter { it.key in trackingKeys && it.key !in cardKeys }.mapNotNull { item ->
            val cached = cache[item.key]?.takeIf { it.date in reuseCutoff..date } ?: return@mapNotNull null
            val profile = cached.classification.profile
            if (profile != "none" && item.profileHint.isNotEmpty() && profile !in item.profileHint) return@mapNotNull null
            item.key to cached
        }.toMap()

        val classified = stage("classify", input.size, { it: ClassificationResult -> it.signals.count { s -> s.classification.profile != "none" } }) {
            val newInputs = input.filter { it.key !in reused }
            val recent = priorTopics.filter { it.runDate >= today.minusDays(config.recentTopicDays.toLong()).toString() }
            val pending = input.filter { it.key in reused }.groupBy { item ->
                active.filter { it.id !in reused.getValue(item.key).relevance }
            }.filterKeys { it.isNotEmpty() }
            val contexts = comments.contexts(newInputs + pending.values.flatten())
            val result = classifier.classify(newInputs, recent, active, contexts)
            for (signal in result.signals) {
                if (signal.item.key !in trackingKeys) continue
                cache[signal.item.key] = TrackClassification(date, signal.classification.copy(trackedIdeaIds = emptyList()),
                    active.associate { it.id to (it.id in signal.classification.trackedIdeaIds) })
            }
            val dropped = result.dropped.toMutableList()
            var scoreClampWarnings = result.scoreClampWarnings
            for ((unevaluated, items) in pending) {
                val evaluation = classifier.classify(items, recent, unevaluated, contexts)
                scoreClampWarnings += evaluation.scoreClampWarnings
                dropped += evaluation.dropped.filter { it.reason != DropReason.NOT_RELEVANT }
                for (signal in evaluation.signals) {
                    val cached = cache.getValue(signal.item.key)
                    cache[signal.item.key] = cached.copy(relevance = cached.relevance +
                        unevaluated.associate { it.id to (it.id in signal.classification.trackedIdeaIds) })
                }
            }
            val cachedSignals = input.filter { it.key in reused }.map { item ->
                val cached = cache.getValue(item.key)
                val classification = cached.classification.copy(
                    trackedIdeaIds = active.map { it.id }.filter { cached.relevance[it] == true }
                )
                if (classification.profile == "none") dropped += DroppedItem(item, DropReason.NOT_RELEVANT, classification.reason)
                Signal(item, classification, hnContext = contexts[item.key])
            }
            ClassificationResult(result.signals + cachedSignals, dropped, scoreClampWarnings)
        }
        val cardSignals = classified.signals.filter { it.item.key in cardKeys && it.classification.profile != "none" }
        val selected = stage("select", cardSignals.size, { it: SelectionResult -> it.kept.size }) { selector.select(cardSignals) }
        val enrichedSelection = stage("enrich", selected.kept.size, { it: SelectionResult -> it.kept.size }) {
            val kept = Enricher(http, profiles, comments).enrich(selected.kept, selected.groups)
            val groups = kept.associate { representative ->
                val key = representative.item.key
                key to selected.groups[key].orEmpty().map { member ->
                    if (member.hnContext != null) member else member.copy(hnContext = comments.context(member.item))
                }
            }
            selected.copy(kept = kept, groups = groups)
        }
        val enriched = enrichedSelection.kept
        val allCards = priorRuns.flatMap { it.cards }.associateBy { it.id }
        val matches = enriched.associate { it.item.key to history.search(it, today) }
        val feedback = history.feedback().asSequence().distinctBy { it.cardId }.mapNotNull { entry ->
            val card = allCards[entry.cardId] ?: history.cardRun(entry.cardId)?.cards
                ?.firstOrNull { it.id == entry.cardId } ?: return@mapNotNull null
            listOfNotNull(card.title, card.category, if (entry.kind == Feedback.Kind.STAR) "★" else "관심 없음",
                entry.reason, entry.memo).joinToString(" · ")
        }.take(config.feedbackExamples).toList()
        val recentCards = priorRuns.filter { it.report.runDate >= today.minusDays(config.recentCardDays.toLong()).toString() }
            .flatMap { it.cards }.map { it.title }
        suspend fun generate(retryAfterTruncation: Boolean = false) = stage("generate", enriched.size, { it: GeneratedCards -> it.cards.size }) {
            generator.generate(date, enriched, matches, feedback, recentCards, enrichedSelection.groups, retryAfterTruncation)
        }
        var generated = generate()
        val topics = cardSignals.groupBy { it.classification.topicKey }.map { (key, rows) ->
            TopicHistory(key, rows.first().classification.topicLabel, date, rows.map { it.item.key })
        }
        val snapshots = history.metrics(now)
        val badgeCalculator = BadgeCalculator(config.badges)
        val validator = CardValidator(config, generator.categories.map { it.id }.toSet())
        val citations = CitationValidator(enriched, selected.groups)
        val cardRejections = mutableMapOf<String, Int>()
        val validationIssues = mutableListOf<String>()
        val firstAttemptIssues = mutableListOf<String>()
        suspend fun validate(cards: List<IdeaCard>) = stage("validate", cards.size, { it: List<IdeaCard> -> it.size }) {
            validator.validate(cards, citations, onRejected = { card, rejection ->
                validationIssues += "카드 제외 (${card.title}) · ${rejection.detail}"
                val reason = rejection.reason.name
                cardRejections[reason] = cardRejections.getOrDefault(reason, 0) + 1
            }, onCitationsTrimmed = { card, detail ->
                validationIssues += "근거 링크 일부 제거 (${card.title}) · $detail"
            }).map {
                it.copy(badges = badgeCalculator.badges(citations.citedSignals(it), priorTopics + topics, snapshots, now, today))
            }
        }
        var cards = validate(generated.cards)
        if (generated.failure in setOf("JSON 응답 오류", TRUNCATED_RESPONSE) || generated.cards.isNotEmpty() && cards.isEmpty()) {
            firstAttemptIssues += validationIssues.map { "1차 시도: $it" }
            validationIssues.clear()
            cardRejections.clear()
            generated = generate(retryAfterTruncation = generated.failure == TRUNCATED_RESPONSE)
            cards = validate(generated.cards)
        }
        if (cards.isEmpty()) issues += generated.failure ?: "근거 링크·필수 항목·분류 검증을 통과한 카드가 없습니다."
        issues += classified.dropped.filter { it.reason == DropReason.CLASSIFY_FAILED || it.reason == DropReason.BUDGET_EXCEEDED }
            .map { it.detail }.filter(String::isNotBlank).distinct()

        val enrichedById = enriched.associateBy { it.item.key }
        val signals = classified.signals.map { enrichedById[it.item.key] ?: it }
        val strength = StrengthCalculator(config.tracking)
        val byDay = priorState.takeWhile { it.report.runDate >= today.minusDays(13).toString() }
            .associate { LocalDate.parse(it.report.runDate) to it.classified } + (today to signals)
        val trackedAfter = mutableListOf<TrackedIdea>()
        val updates = stage("track", active.size, { it: List<TrackingUpdate> -> it.size }) {
            tracked.mapNotNull { idea ->
                if (idea.status == TrackedIdea.Status.STOPPED) {
                    trackedAfter += idea
                    return@mapNotNull null
                }
                val previous = previousUpdates[idea.id].orEmpty()
                val original = history.cardRun(idea.cardId)?.cards?.firstOrNull { it.id == idea.cardId }
                val priorItems = previous.flatMap { it.itemKeys }.distinct().mapNotNull { key ->
                    previousEvidence[idea.id to key]?.let { key to it.item }
                }.toMap()
                val result = updater.update(idea, original, signals, previous, date, priorItems)
                result.failure?.let { issues += "추적 업데이트 실패 (${idea.title}) · $it" }
                val adjusted = if (idea.lastUpdateAt != null &&
                    !idea.lastUpdateAt.isBefore(now.minusSeconds(86400)) &&
                    history.stateRuns(today.plusDays(1), 1).any { run ->
                        run.trackingUpdates.any { it.trackedId == idea.id } && run.report.startedAt == idea.lastUpdateAt.toString()
                    }) idea.copy(lastUpdateAt = previous.firstOrNull()?.let { update ->
                        historicalTrackingRuns.firstOrNull { update in it.trackingUpdates }?.report?.startedAt?.let(Instant::parse)
                    }) else idea
                val discovered = result.update?.changes.orEmpty().filter { it.kind == "NEW_COMPETITOR" }.mapNotNull { change ->
                    signals.firstOrNull { it.item.key == change.itemKey }?.let { row ->
                        row.classification.topicLabel.ifBlank { row.item.title }
                    }
                }
                val updated = strength.update(adjusted.copy(competitors = (adjusted.competitors + discovered).distinct()),
                    byDay, today, now, result.update != null)
                trackedAfter += updated
                result.update?.copy(strength = updated.strength, trend = updated.trend)
            }
        }
        val dropped = base.dropped + classified.dropped + selected.dropped
        val classifiedCounts = cardSignals.groupingBy { it.classification.profile }.eachCount()
        val selectedCounts = selected.kept.groupingBy { it.classification.profile }.eachCount()
        val profileMetrics = config.profiles.keys.associateWith { profile ->
            ProfileMetrics(classifiedCounts[profile] ?: 0, selected.thresholdPassedByProfile[profile] ?: 0,
                selectedCounts[profile] ?: 0)
        }
        onStage("deliver")
        stages += StageResult("deliver", cards.size, cards.size, 0)
        return base.copy(
            items = input.map { enrichedById[it.key]?.item ?: it }, dropped = dropped, classified = signals,
            cards = cards, trackingUpdates = updates, topics = topics,
            metrics = RunMetrics(collected.distinctBy { it.key }.map { MetricSnapshot(it.key, it.rawScore, now) },
                profileMetrics, classified.scoreClampWarnings, cardRejections),
            tracked = trackedAfter, trackClassified = cache, trackingOnlyKeys = input.map { it.key }.toSet() - cardKeys,
            report = base.report.copy(stages = stages, llmCalls = costs.drainCalls(), cardCount = cards.size,
                trackingUpdateCount = updates.size, dropCounts = dropped.groupingBy { it.reason }.eachCount(),
                issues = issues.distinct() + firstAttemptIssues + validationIssues,
                totalMillis = base.report.totalMillis + (System.nanoTime() - started) / 1_000_000)
        )
    }
}

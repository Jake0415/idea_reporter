package collector.pipeline

import collector.config.GitHubConfig
import collector.dev.writeAtomically
import collector.dev.readJson
import collector.llm.batchPath
import collector.llm.hasSavedBatch
import collector.llm.isPendingBatch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class FullPipeline(
    private val config: PipelineConfig, private val profiles: ProfileLoader.ProfileSet, private val prompts: PromptResolver,
    router: LlmRouter, private val costs: CostGuard, private val http: OkHttpClient,
    private val history: RunHistory, private val trackedStore: TrackedStore,
    val collectOnly: Boolean = false,
    private val reddit: RedditAdapter? = null,
    private val out: File? = null,
    private val snapshotBatchInputs: Boolean = false
) {
    private val classifier = Classifier(config, profiles, prompts, router)
    private val selector = Selector(config)
    private val generator = IdeaGenerator(config, prompts, router)
    private val updater = TrackingUpdater(prompts, router)

    @Serializable
    internal data class BatchCollectionInput(
        val base: PipelineResult, val collected: List<Item>, val tracking: List<Item>, val tracked: List<TrackedIdea>
    )

    @Serializable
    internal data class GenerationInput(
        val collection: BatchCollectionInput,
        val classified: ClassificationResult, val selected: SelectionResult, val enriched: SelectionResult,
        val cache: Map<String, TrackClassification>, val matches: Map<String, List<HistoryHit>>,
        val feedback: List<String>, val recentCards: List<String>,
        val repetitionHistory: List<RecentCard>? = null, val expansionSeeds: List<ExpansionSeed> = emptyList()
    )

    private fun batchInputFile(date: String, stage: String): File? = out?.takeIf { snapshotBatchInputs }?.let {
        batchPath(it, date, stage).resolveSibling("$date-$stage.input")
    }

    internal fun generationInput(date: LocalDate): GenerationInput? {
        if (out == null || !hasSavedBatch(out, date.toString(), "generate")) return null
        return batchInputFile(date.toString(), "generate")?.takeIf { it.isFile }
            ?.let { Json.decodeFromString<GenerationInput>(it.readText()) }
    }

    internal fun classificationInput(date: LocalDate): BatchCollectionInput? {
        if (out == null || !hasSavedBatch(out, date.toString(), "classify")) return null
        return batchInputFile(date.toString(), "classify")?.takeIf { it.isFile }
            ?.let { Json.decodeFromString<BatchCollectionInput>(it.readText()) }
    }

    /** 저장된 추적 목록 그대로(★ 피드백으로 새 추적을 만들지 않음). Batch 이어받기에서 현재 상태를 지킬 때 쓴다. */
    internal fun storedTracked(): List<TrackedIdea> = if (collectOnly) emptyList() else trackedStore.load()

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
                             now: Instant, today: LocalDate, saved: GenerationInput? = null,
                             latestTracked: List<TrackedIdea>? = null, onStage: (String) -> Unit): PipelineResult {
        // latestTracked: Batch 이어받기에서 스냅샷 이후 사용자가 바꾼 추적 상태(★·중지·키워드)를 지키기 위한 현재 목록.
        // 분류·생성은 제출 당시 스냅샷(tracked)을 그대로 써서 요청 지문을 맞추고, 추적 단계와 저장은 현재 목록을 쓴다.
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
        val cache = (saved?.cache ?: history.trackClassified().filterValues { it.date >= retentionCutoff }.mapValues { (_, cached) ->
            cached.copy(classification = cached.classification.copy(trackedIdeaIds = emptyList()))
        }).toMutableMap()
        val reused = input.filter { it.key in trackingKeys && it.key !in cardKeys }.mapNotNull { item ->
            val cached = cache[item.key]?.takeIf { it.date in reuseCutoff..date } ?: return@mapNotNull null
            val profile = cached.classification.profile
            if (profile != "none" && item.profileHint.isNotEmpty() && profile !in item.profileHint) return@mapNotNull null
            item.key to cached
        }.toMap()

        val classified = stage("classify", input.size, { it: ClassificationResult -> it.signals.count { s -> s.classification.profile != "none" } }) {
            if (saved != null) return@stage saved.classified
            val newInputs = input.filter { it.key !in reused }
            val recent = priorTopics.filter { it.runDate >= today.minusDays(config.recentTopicDays.toLong()).toString() }
            val pending = input.filter { it.key in reused }.groupBy { item ->
                active.filter { it.id !in reused.getValue(item.key).relevance }
            }.filterKeys { it.isNotEmpty() }
            val contexts = comments.contexts(newInputs + pending.values.flatten())
            if (config.batch.classify && newInputs.isNotEmpty() && out != null && !hasSavedBatch(out, date, "classify")) {
                batchInputFile(date, "classify")?.writeAtomically(
                    Json.encodeToString(BatchCollectionInput(base, collected, tracking, tracked)))
            }
            val result = classifier.classify(newInputs, recent, active, contexts, date, out)
            if (result.batchPending) return@stage result
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
            result.copy(signals = result.signals + cachedSignals, dropped = dropped, scoreClampWarnings = scoreClampWarnings)
        }
        issues += classified.issues
        val cardSignals = classified.signals.filter { it.item.key in cardKeys && it.classification.profile != "none" }
        val selected = stage("select", cardSignals.size, { it: SelectionResult -> it.kept.size }) { saved?.selected ?: selector.select(cardSignals) }
        if (classified.batchPending) {
            return base.copy(report = base.report.copy(stages = stages, issues = issues.distinct(),
                llmCalls = costs.drainCalls(), classificationBatchWaitMillis = classified.batchWaitMillis,
                totalMillis = base.report.totalMillis + (System.nanoTime() - started) / 1_000_000))
        }
        val enrichedSelection = stage("enrich", selected.kept.size, { it: SelectionResult -> it.kept.size }) {
            if (saved != null) return@stage saved.enriched
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
        val matches = saved?.matches ?: enriched.associate { it.item.key to history.search(it, today) }
        val reactions by lazy { history.feedback().distinctBy { it.cardId } }
        val feedback = saved?.feedback ?: reactions.asSequence().mapNotNull { entry ->
            val card = allCards[entry.cardId] ?: history.cardRun(entry.cardId)?.cards
                ?.firstOrNull { it.id == entry.cardId } ?: return@mapNotNull null
            listOfNotNull(card.title, card.category, if (entry.kind == Feedback.Kind.STAR) "★" else "관심 없음",
                entry.reason, entry.memo).joinToString(" · ")
        }.take(config.feedbackExamples).toList()
        val repetitionHistory = saved?.repetitionHistory ?: (priorRuns
            .filter { it.report.runDate >= today.minusDays(config.recentCardDays.toLong()).toString() }
            .flatMap { it.recentCards() } + history.requestedRecentCards(today, config.recentCardDays))
            .sortedByDescending { it.card.runDate }.distinctBy { it.card.id }
        val recentCards = saved?.recentCards ?: repetitionHistory.map { it.promptLine() }
        val expansionSeeds = saved?.expansionSeeds ?: if (config.expansion.enabled && config.expansion.lenses.values.any { it }) {
            val weights = reactions.mapNotNull { entry ->
                val card = allCards[entry.cardId] ?: history.cardRun(entry.cardId)?.cards?.firstOrNull { it.id == entry.cardId }
                card?.lens?.takeIf { card.kind == "expanded" }?.let { lens ->
                    lens to if (entry.kind == Feedback.Kind.STAR) 1 else -1
                }
            }.groupingBy { it.first }.fold(0) { sum, entry -> sum + entry.second }
            val seedHistory = out?.resolve("expansion-seeds.json")?.let { readJson<List<UsedExpansionSeed>>(it) }.orEmpty()
            val seedSignals = enriched + verifiedMergedEvidence(enriched, enrichedSelection.groups).values.flatten()
            makeExpansionSeeds(today, config.expansion, seedSignals, prompts.resolve("criteria").text,
                seedHistory, weights, prompts.resolve("ideas").text, repetitionHistory)
        } else emptyList()
        val inputFile = batchInputFile(date, "generate")
        if (saved == null && config.batch.generate && inputFile != null && enriched.isNotEmpty() &&
            out != null && !hasSavedBatch(out, date, "generate")) {
            inputFile.writeAtomically(Json.encodeToString(GenerationInput(BatchCollectionInput(base, collected, tracking, tracked),
                classified, selected, enrichedSelection, cache, matches, feedback, recentCards, repetitionHistory, expansionSeeds)))
        }
        suspend fun generate(retryAfterTruncation: Boolean = false, batchAllowed: Boolean = true) =
            stage("generate", enriched.size, { it: GeneratedCards -> it.cards.size }) {
                generator.generate(date, enriched, matches, feedback, recentCards, enrichedSelection.groups,
                    retryAfterTruncation, out, batchAllowed, snapshotBatchInputs, expansionSeeds = expansionSeeds).also { issues += it.issues }
            }
        var generated = generate()
        // 생성 Batch가 아직 대기 중이면(결과 조회 실패 포함) 추적 비용을 쓰기 전에 이어받기로 넘긴다.
        if (out != null && isPendingBatch(batchPath(out, date, "generate"))) {
            return base.copy(report = base.report.copy(stages = stages, issues = (issues + generated.issues).distinct(),
                llmCalls = costs.drainCalls(), classificationBatchWaitMillis = classified.batchWaitMillis,
                totalMillis = base.report.totalMillis + (System.nanoTime() - started) / 1_000_000))
        }
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
        suspend fun validate(generation: GeneratedCards) = stage("validate", generation.cards.size, { it: List<IdeaCard> -> it.size }) {
            validator.validate(generation.cards, citations, repetitionHistory, expansionSeeds,
                requiresExpansionFields = generation.requiresExpansionFields, onRejected = { card, rejection ->
                validationIssues += "카드 제외 (${card.title}) · ${rejection.detail}"
                val reason = rejection.reason.name
                cardRejections[reason] = cardRejections.getOrDefault(reason, 0) + 1
            }, onCitationsTrimmed = { card, detail ->
                validationIssues += "근거 링크 일부 제거 (${card.title}) · $detail"
            }).map {
                it.copy(badges = badgeCalculator.badges(citations.citedSignals(it), priorTopics + topics, snapshots, now, today))
            }
        }
        var cards = validate(generated)
        if (!generated.reusedPaidBatch && (generated.failure in setOf("JSON 응답 오류", TRUNCATED_RESPONSE) || generated.cards.isNotEmpty() && cards.isEmpty() && cardRejections.keys != setOf(CardRejectionReason.REPEAT.name))) {
            firstAttemptIssues += validationIssues.map { "1차 시도: $it" }
            validationIssues.clear()
            cardRejections.clear()
            generated = generate(retryAfterTruncation = generated.failure == TRUNCATED_RESPONSE, batchAllowed = false)
            cards = validate(generated)
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
            (latestTracked ?: tracked).mapNotNull { idea ->
                if (idea.status == TrackedIdea.Status.STOPPED) {
                    trackedAfter += idea
                    return@mapNotNull null
                }
                // 이전 날짜 이어받기: 더 늦은 날짜 실행이 이미 갱신한 추적은 건드리지 않는다.
                if (latestTracked != null && idea.lastUpdateAt?.isAfter(now) == true) {
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
            usedExpansionSeeds = expansionSeeds.filter { seed -> cards.any { it.kind == "expanded" && it.seedId == seed.id } }
                .map { it.usedOn(today) },
            metrics = RunMetrics(collected.distinctBy { it.key }.map { MetricSnapshot(it.key, it.rawScore, now) },
                profileMetrics, classified.scoreClampWarnings, cardRejections),
            tracked = trackedAfter,
            trackClassified = if (latestTracked == null) cache else history.trackClassified().let { current ->
                current + cache.filter { (key, value) -> current[key]?.let { it.date <= value.date } ?: true }
            },
            trackingOnlyKeys = input.map { it.key }.toSet() - cardKeys,
            report = base.report.copy(stages = stages, llmCalls = costs.drainCalls(), cardCount = cards.size,
                trackingUpdateCount = updates.size, dropCounts = dropped.groupingBy { it.reason }.eachCount(),
                issues = issues.distinct() + firstAttemptIssues + validationIssues,
                classificationBatchWaitMillis = classified.batchWaitMillis,
                totalMillis = base.report.totalMillis + (System.nanoTime() - started) / 1_000_000)
        )
    }
}

package collector.runtime

import collector.dev.*
import collector.mail.*
import collector.analysis.AnalysisCommand
import collector.analysis.AnalysisConfig
import collector.backfill.Backfill
import collector.budget.BudgetSettings
import collector.budget.MonthlyBudget
import collector.source.hasPrivateRssQuery
import collector.config.ConfigLoader
import collector.config.PipelineConfig
import collector.config.SourcesFile
import collector.llm.*
import collector.model.RunReport
import collector.model.DropReason
import collector.pipeline.FullPipeline
import collector.pipeline.Pipeline
import collector.pipeline.PipelineResult
import collector.port.SecretProvider
import collector.profile.ProfileLoader
import collector.prompt.FilePromptStore
import collector.prompt.PromptResolver
import collector.report.HtmlRenderer
import collector.source.GitHubAdapter
import collector.source.GitHubIssuesAdapter
import collector.source.HackerNewsAdapter
import collector.source.HuggingFaceAdapter
import collector.source.ProductHuntAdapter
import collector.source.RedditAdapter
import collector.source.RssAdapter
import collector.source.Http
import collector.source.SourceAdapter
import collector.stage.Classifier
import collector.stage.IdeaGenerator
import collector.stage.CardValidator
import collector.stage.CitationValidator
import collector.stage.CommentFetcher
import collector.model.Signal
import collector.model.Item
import collector.model.ClassifiedItem
import collector.refine.UrlNormalizer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.time.Instant
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.Locale

open class RunLockedException : RuntimeException()
class BatchDeferredException : CancellationException()

interface RunLock {
    suspend fun <T> withLock(out: File, block: suspend () -> T): T
}

internal object FileRunLock : RunLock {
    override suspend fun <T> withLock(out: File, block: suspend () -> T): T = withRunLock(out, block)
}

enum class RunStage { RECOVER, COLLECT, CLASSIFY, GENERATE, MAIL }

data class DailyOptions(
    val keepUnseen: Boolean = false,
    val noLlm: Boolean = false,
    val budgetUsd: Double? = null,
    val batchOverride: Boolean? = null,
    val deadline: Duration? = null,
    val onStage: (RunStage) -> Unit = {},
    val sources: SourcesFile? = null,
    val deferBatch: Boolean = false,
    val includeMailCandidates: Boolean = false
)

data class ScheduledOptions(
    val mail: Boolean = true,
    val batchOverride: Boolean? = null,
    val deadline: Duration? = null,
    val onStage: (RunStage) -> Unit = {},
    val deferBatch: Boolean = false,
    val honorSchedule: Boolean = true,
    val runDate: LocalDate? = null,
    val beforeRun: suspend (CollectorRuntime.LocalEditor) -> Unit = {},
    val batchOverrideForRun: (suspend () -> Boolean)? = null,
    val resumeBatch: Boolean = false,
    val onUnpublishedBatch: suspend (LocalDate) -> Unit = {},
    val onMailRetriesExpired: (Int) -> Unit = {},
    val deferMailDelivery: Boolean = false,
    val onLockAcquired: () -> Unit = {},
    val mailEnabled: () -> Boolean = { mail },
    val includeMailCandidates: Boolean = false
)

data class DailySummary(
    val published: Boolean,
    val cards: Int,
    val costUsd: Double,
    val failedSources: Int
)

private const val BATCH_CONTINUATION_PENDING = "Batch continuation pending"

enum class ScheduledMailStatus { SENT, PENDING, FAILED }

data class ScheduledSummary(
    val exitCode: Int, val daily: DailySummary?, val skipped: Boolean = false,
    val mailStatus: ScheduledMailStatus? = null, val mailFailureReason: String? = null,
    val previousMailRetries: Map<MailSendResult, Int> = emptyMap(), val expiredMailRetries: Int = 0
)
data class TrendSummary(val date: LocalDate, val subject: String)
@kotlinx.serialization.Serializable
data class AnalysisEstimate(val estimatedUsd: Double?, val reservationUsd: Double?, val canReserve: Boolean,
    val budgetVersion: String? = null)
data class AnalysisSummary(val successful: Boolean, val requote: Boolean = false, val failure: String? = null)

class CollectorRuntime(
    val paths: CollectorPaths,
    private val secrets: SecretProvider,
    private val clock: Clock,
    private val sourceAdapters: List<SourceAdapter>? = null,
    private val dailyProviders: (() -> List<LlmProvider>)? = null,
    private val mailer: (() -> Mailer?)? = null,
    private val runLock: RunLock = FileRunLock,
    private val batchCheckpointHook: () -> Boolean = { true },
    private val batchRecoverySecrets: SecretProvider = secrets,
    private val providerFactory: (ProviderId, String) -> LlmProvider = { id, key ->
        when (id) {
            ProviderId.ANTHROPIC -> AnthropicProvider(key)
            ProviderId.OPENAI -> OpenAiProvider(key)
        }
    }
) {
    private val out get() = paths.outDir

    fun candidates(date: LocalDate): CandidateView =
        readJson<PipelineResult>(out.resolve("$date.json"))?.let(::candidateView) ?: CandidateView()

    private val requestedResearchTitleChars = 120
    private val requestedResearchUrlChars = 512
    private val requestedResearchSnippetChars = 180
    private val requestedCommentAuthorChars = 80
    private val requestedCommentTextChars = 500

    private fun boundedRequestedComments(comments: List<collector.model.Comment>, limit: Int) =
        comments.take(limit).map {
            it.copy(author = it.author.take(requestedCommentAuthorChars), text = it.text.take(requestedCommentTextChars))
        }

    fun requestedCardsBlocked(): Boolean =
        MonthlyBudget(out, BudgetSettings.load(out) {}).requestedBlocked(LocalDate.now(clock))

    suspend fun releaseRequestedCards() = locked {
        MonthlyBudget(out, BudgetSettings.load(out) {}).releaseRequested(LocalDate.now(clock))
    }

    private fun supportsRequestedResearch(provider: ProviderId): Boolean {
        dailyProviders?.let { supplied ->
            val providers = supplied()
            return try {
                providers.any { it.id == provider && it is WebSearchProvider }
            } finally {
                providers.forEach { (it as? AutoCloseable)?.close() }
            }
        }
        val key = secrets.get("${provider.name}_API_KEY") ?: return provider == ProviderId.ANTHROPIC
        val instance = providerFactory(provider, key)
        return try {
            instance is WebSearchProvider
        } finally {
            (instance as? AutoCloseable)?.close()
        }
    }

    private suspend fun prepareRequestedCards(
        date: LocalDate, keys: List<String>, research: Boolean, approvedDay: LocalDate? = null
    ): RequestedPlan {
        require(keys.size in 1..3 && keys.distinct().size == keys.size)
        val recoveryPending = Backup.recoveryPending(paths)
        val daily = requireNotNull(readJson<PipelineResult>(out.resolve("$date.json")))
        val byKey = candidateView(daily).candidates.associateBy { it.key }
        val selected = keys.map { byKey.getValue(it).signal }.filter { publicRequestedUrl(it.item.url) }
        val defaults = pipelineConfig()
        val config = defaults.copy(ideaCount = keys.size, models = defaults.models.copy(fallback = false),
            supplementSearch = defaults.supplementSearch.copy(enabled = research, maxUses = 3))
        val history = FileHistorySearch(out, config.historyDays)
        val past = history.runs(date, config.historyDays)
        val hits = selected.associate { it.item.key to history.search(it, date) }
        val hitIds = hits.values.flatten().map { it.id }.toSet()
        val available = daily.classified + past.flatMap { it.classified }.filter {
            it.item.key in hitIds || selected.any { seed -> seed.classification.topicKey == it.classification.topicKey }
        }
        val rawEvidence = requestedEvidence(selected, available.filter { publicRequestedUrl(it.item.url) })
        fun bound(signal: Signal) = signal.copy(
            item = signal.item.copy(comments = boundedRequestedComments(signal.item.comments, config.commentsPerItem)),
            enrichedComments = boundedRequestedComments(signal.enrichedComments, config.commentsPerItem)
        )
        val evidence = RequestedEvidence(rawEvidence.selected.map(::bound), rawEvidence.added.map(::bound))
        val merged = requestedMergedMembers(listOf(daily) + past, evidence.signals)
        val recent = past.flatMap { it.cards } + daily.cards +
            out.resolve("requested").listFiles().orEmpty().flatMap { readJson<PipelineResult>(it)?.cards.orEmpty() }
        val feedback = history.feedback().distinctBy { it.cardId }.take(config.feedbackExamples).mapNotNull { entry ->
            history.cardRun(entry.cardId)?.cards?.firstOrNull { it.id == entry.cardId }?.let {
                listOfNotNull(it.title, it.category, entry.kind.name, entry.reason).joinToString(" · ")
            }
        }
        val recentTitles = recent.filter { it.runDate >= date.minusDays(config.recentCardDays.toLong()).toString() }
            .map { it.title }.distinct()
        val budget = BudgetSettings.load(out) {}
        val day = LocalDate.now(clock)
        val ledger = requestedCostLedger(out)
        val monthly = MonthlyBudget(out, budget)
        val dailyRemaining = (budget.requestDailyUsd - ledger.total(day)).coerceAtLeast(0.0)
        val monthlyRemaining = monthly.remaining(day) ?: Double.POSITIVE_INFINITY
        val remaining = minOf(budget.requestCardUsd, dailyRemaining, monthlyRemaining)
        val router = LlmRouter(emptyList(), CostGuard(0.0), strictRequestBudget = true)
        val prompts = PromptResolver(FilePromptStore(paths.promptsDir.toPath()))
        val searchUser = outputJson.encodeToString(selected.map {
            it.classification.topicLabel.ifBlank { it.item.title }
        }.distinct())
        var estimate: Double? = null
        var researchMaxUsd: Double? = null
        val searchModel = collector.llm.ModelCatalog.defaults.cheapestWebSearchModel(config.models.classify.provider)
            ?.takeIf { supportsRequestedResearch(it.provider) }
        if (!recoveryPending && selected.size == keys.size &&
            (if (research) searchModel != null else evidence.sufficient)) {
            val researchEstimateSignals = if (!research) emptyList() else (1..3).map { index ->
                val item = Item("web", UUID.nameUUIDFromBytes(byteArrayOf(index.toByte())).toString(),
                    "\u001f".repeat(requestedResearchTitleChars), "a".repeat(requestedResearchUrlChars), 0,
                    publishedAt = Instant.EPOCH, snippet = "\u001f".repeat(requestedResearchSnippetChars))
                Signal(item, ClassifiedItem(item.key, "market-signal", 10.0, "", "\u001f".repeat(180),
                    List(5) { "\u001f".repeat(30) }, "a".repeat(80), "\u001f".repeat(80)))
            }
            var classificationUsd = 0.0
            if (research) Classifier(config, ProfileLoader.loadDefaults(), prompts, router).classify(
                researchEstimateSignals.map { it.item }, emptyList(), emptyList(),
                quoteOnly = { classificationUsd += router.projectedTokens(it, config.models.classify) })
            val quotedSignals = if (!research) evidence.signals else evidence.signals.map { signal ->
                if (sourceAdapters != null || evidence.selected.none { it.item.key == signal.item.key } || signal.item.source !in setOf("hn", "reddit")) signal else signal.copy(
                    enrichedComments = List(config.commentsPerItem) {
                        collector.model.Comment("\u001f".repeat(requestedCommentAuthorChars), "\u001f".repeat(requestedCommentTextChars))
                    })
            } + researchEstimateSignals
            IdeaGenerator(config, prompts, router).generate(date.toString(), citableRequestedSignals(quotedSignals, merged), hits, feedback,
                recentTitles, merged = merged, batchAllowed = false, quoteOnly = { request ->
                    val generationUsd = router.projectedGeneration(request, config.models.generate, research)
                    val baseUsd = generationUsd + classificationUsd
                    estimate = baseUsd + if (research) router.projectedSupplement(
                        searchUser, config.supplementSearch, checkNotNull(searchModel)) else 0.0
                    if (research) researchMaxUsd = baseUsd + router.projectedSupplement(
                        searchUser, config.supplementSearch, checkNotNull(searchModel), upperEstimate = true)
                })
        }
        val monthBlocked = monthly.requestedBlocked(LocalDate.now(clock)) ||
            approvedDay?.let(monthly::requestedBlocked) == true
        val block = when {
            recoveryPending -> RequestedCardBlock.RECOVERY_PENDING
            selected.size != keys.size -> RequestedCardBlock.PRIVATE_URL
            monthBlocked -> RequestedCardBlock.MONTHLY_BLOCK
            research && searchModel == null -> RequestedCardBlock.RESEARCH_UNSUPPORTED
            monthlyRemaining <= 0.0 -> RequestedCardBlock.MONTHLY_LIMIT
            dailyRemaining <= 0.0 -> RequestedCardBlock.DAILY_LIMIT
            estimate == null -> RequestedCardBlock.INSUFFICIENT_EVIDENCE
            estimate <= remaining -> null
            remaining == monthlyRemaining -> RequestedCardBlock.MONTHLY_LIMIT
            remaining == dailyRemaining -> RequestedCardBlock.DAILY_LIMIT
            else -> RequestedCardBlock.REQUEST_LIMIT
        }
        val budgetBlocks = setOf(RequestedCardBlock.DAILY_LIMIT, RequestedCardBlock.MONTHLY_LIMIT, RequestedCardBlock.REQUEST_LIMIT)
        val plainExceedsBudget = if (research && block in budgetBlocks && evidence.sufficient &&
            dailyRemaining > 0.0 && monthlyRemaining > 0.0) {
            var plainUsd = 0.0
            val plainConfig = config.copy(supplementSearch = config.supplementSearch.copy(enabled = false))
            IdeaGenerator(plainConfig, prompts, router).generate(date.toString(),
                citableRequestedSignals(evidence.signals, merged), hits, feedback, recentTitles,
                merged = merged, batchAllowed = false,
                quoteOnly = { plainUsd = router.projectedGeneration(it, config.models.generate, false) })
            plainUsd > remaining
        } else false
        val quote = RequestedCardEstimate(evidence.added, evidence.sufficient, estimate,
            block == null, remaining, dailyRemaining, budget.requestCardUsd, budget.requestDailyUsd, block, researchMaxUsd,
            searchModel != null, monthBlocked || dailyRemaining <= 0.0 || monthlyRemaining <= 0.0 ||
                plainExceedsBudget || !research && block in budgetBlocks)
        return RequestedPlan(quote, config, prompts, evidence, hits, recent, ledger, monthly, searchUser,
            feedback, recentTitles, merged)
    }

    private data class RequestedPlan(
        val quote: RequestedCardEstimate, val config: PipelineConfig, val prompts: PromptResolver,
        val evidence: RequestedEvidence, val hits: Map<String, List<collector.stage.HistoryHit>>,
        val recent: List<collector.model.IdeaCard>, val ledger: CostLedger, val monthly: MonthlyBudget,
        val searchUser: String, val feedback: List<String>, val recentTitles: List<String>,
        val merged: Map<String, List<Signal>>
    )

    suspend fun estimateRequestedCards(date: LocalDate, keys: List<String>, research: Boolean): RequestedCardEstimate =
        prepareRequestedCards(date, keys, research).quote

    suspend fun runRequestedCards(date: LocalDate, keys: List<String>, research: Boolean, approvedMaxUsd: Double,
        onLockAcquired: () -> Unit = {}, mailEnabled: () -> Boolean = { false }): RequestedCardResult = locked {
        onLockAcquired()
        require(approvedMaxUsd.isFinite() && approvedMaxUsd >= 0)
        val approvedDay = LocalDate.now(clock)
        val plan = prepareRequestedCards(date, keys, research, approvedDay)
        val quote = plan.quote
        check(quote.block != RequestedCardBlock.RECOVERY_PENDING)
        if (!quote.allowed || checkNotNull(quote.estimatedUsd) > approvedMaxUsd)
            return@locked RequestedCardResult(retryAllowed = true, requote = quote)
        val approvedUsd = minOf(quote.maxUsd, approvedMaxUsd)
        val guard = CostGuard(approvedUsd, clock = clock, ledger = plan.ledger,
            budgetPerRun = true, onOversettlement = { ledgerDay, settledUsd ->
                plan.monthly.blockRequested(ledgerDay, approvedUsd, settledUsd)
            }, allowsReservation = { day, amount ->
                !plan.monthly.requestedBlocked(day) &&
                    (day == approvedDay || !plan.monthly.requestedBlocked(approvedDay)) &&
                    amount <= quote.dailyLimitUsd - plan.ledger.total(day) && plan.monthly.allows(day, amount)
            })
        val model = plan.config.models.generate
        val searchModel = plan.config.models.classify
        val providers = dailyProviders?.invoke() ?: (listOf(model) + listOf(searchModel).filter { research })
            .distinctBy { it.provider }.mapNotNull {
                secrets.get("${it.provider.name}_API_KEY")?.let { key -> providerFactory(it.provider, key) }
            }
        try {
            if (providers.none { it.id == model.provider } ||
                research && providers.none { it.id == searchModel.provider && it is WebSearchProvider })
                return@locked RequestedCardResult(failure = "설정한 공급자를 사용할 수 없습니다.", retryAllowed = true)
            val router = LlmRouter(providers, guard, mapOf("generate" to model, "classify" to searchModel),
                fallback = false, preflightBudget = true, batchEnabled = false, strictRequestBudget = true)
            val localSignals = if (research && sourceAdapters == null) {
                val http = Http.client()
                try {
                    val fetcher = CommentFetcher(http, plan.config.commentsPerItem, RedditAdapter(http, secrets))
                    plan.evidence.signals.map { signal ->
                        if (plan.evidence.selected.none { it.item.key == signal.item.key }) signal
                        else signal.copy(enrichedComments = boundedRequestedComments(fetcher.fetch(signal.item), plan.config.commentsPerItem).ifEmpty { signal.enrichedComments })
                    }
                } finally {
                    http.connectionPool.evictAll()
                    http.dispatcher.executorService.shutdown()
                }
            } else plan.evidence.signals
            val researched = if (research) try {
                router.supplementSearch(plan.searchUser, plan.config.supplementSearch).filter { publicRequestedUrl(it.url) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@locked RequestedCardResult(failure = "보충 검색에 실패했습니다.", retryAllowed = guard.calls.isEmpty())
            } else emptyList()
            val knownUrls = localSignals.flatMap { listOf(it.item.url) + it.item.mergedUrls }
                .map(UrlNormalizer::normalize).toSet()
            val researchItems = researched.asSequence().mapNotNull { ref ->
                val url = ref.url.toHttpUrlOrNull() ?: return@mapNotNull null
                if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.toString().length > requestedResearchUrlChars) return@mapNotNull null
                val normalized = UrlNormalizer.normalize(url.toString())
                if (normalized in knownUrls || ref.title.isBlank()) return@mapNotNull null
                Item("web", UUID.nameUUIDFromBytes(normalized.toByteArray()).toString(), ref.title.take(requestedResearchTitleChars),
                    url.toString(), 0, publishedAt = clock.instant(), snippet = ref.note.take(requestedResearchSnippetChars))
            }.distinctBy { it.key }.take(3).toList()
            val webClassification = if (researchItems.isEmpty()) null else
                Classifier(plan.config, ProfileLoader.loadDefaults(), plan.prompts, router)
                    .classify(researchItems, emptyList(), emptyList(), singleAttempt = true)
            if (webClassification?.dropped.orEmpty().any { it.reason in setOf(DropReason.CLASSIFY_FAILED, DropReason.BUDGET_EXCEEDED) })
                return@locked RequestedCardResult(failure = "추가 조사 자료를 분류하지 못했습니다.", retryAllowed = guard.calls.isEmpty())
            val webSignals = webClassification?.signals.orEmpty()
                    .filter { signal ->
                        val row = signal.classification
                        plan.config.profiles[row.profile]?.let { (row.score ?: 0.0) >= it.threshold } == true
                    }
                    .map { signal ->
                        val row = signal.classification
                        signal.copy(classification = row.copy(summary = row.summary.take(180), tags = row.tags.take(5).map { it.take(30) },
                            topicKey = row.topicKey.take(80), topicLabel = row.topicLabel.take(80)))
                    }
            val signals = citableRequestedSignals(localSignals + webSignals, plan.merged)
            if (!RequestedEvidence(signals, emptyList()).sufficient)
                return@locked RequestedCardResult(failure = "추가 조사 후에도 근거가 부족합니다.", retryAllowed = guard.calls.isEmpty())
            val generator = IdeaGenerator(plan.config, plan.prompts, router)
            val generated = generator.generate(date.toString(), signals, plan.hits, plan.feedback, plan.recentTitles,
                merged = plan.merged, batchAllowed = false, singleAttempt = true, researchedRefs = researched)
            val selectedKeys = plan.evidence.selected.map { it.item.key }.toSet()
            val cards = CardValidator(plan.config, generator.categories.map { it.id }.toSet())
                .validate(generated.cards, CitationValidator(signals, plan.merged)).filter { card ->
                    fun refs(value: collector.model.IdeaCard) =
                        (value.techSignals + value.demandSignals + value.competitors).map { it.itemId }.toSet()
                    val cited = refs(card)
                    cited.any { it in selectedKeys } && plan.recent.none { refs(it) == cited || it.title == card.title }
                }.map { it.copy(badges = it.badges + "내가 고른 카드") }
            if (cards.isEmpty()) return@locked RequestedCardResult(failure = generated.failure ?: "검증을 통과한 카드가 없습니다.",
                retryAllowed = guard.calls.isEmpty())
            val report = RunReport(date.toString(), clock.instant().toString(), emptyList(), emptyList(),
                emptyMap(), 0, llmCalls = guard.calls, cardCount = cards.size)
            val record = PipelineResult(signals.map { it.item }, emptyList(), report, classified = signals, cards = cards)
            out.resolve("requested/$date-${UUID.randomUUID()}.json").writeAtomically(outputJson.encodeToString(record))
            val mailFailed = if (mailEnabled()) runCatching {
                val rendered = HtmlRenderer(generator.categories).render(record)
                if (mailEnabled()) checkNotNull(createMailer()).send(MailMessage(rendered.subject, rendered.html, rendered.text))
            }.onFailure { if (it is CancellationException) throw it }.isFailure else false
            RequestedCardResult(cards.map { it.id }, mailFailed = mailFailed)
        } finally {
            providers.forEach { (it as? AutoCloseable)?.close() }
        }
    }
    private fun publicRequestedUrl(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return url.username.isEmpty() && url.password.isEmpty() && (0 until url.querySize).none(url::hasPrivateRssQuery)
    }

    private fun citableRequestedSignals(signals: List<Signal>, merged: Map<String, List<Signal>>) =
        signals.map { signal ->
            val urls = merged[signal.item.key].orEmpty().map { it.item.url }.toSet()
            signal.copy(item = signal.item.copy(mergedUrls = signal.item.mergedUrls.filter { it in urls }))
        }

    private fun requestedMergedMembers(runs: List<PipelineResult>, signals: List<Signal>): Map<String, List<Signal>> {
        val keys = signals.filter { it.item.mergedUrls.isNotEmpty() }.map { it.item.key }.toSet()
        return runs.flatMap { run ->
            val classified = run.classified.associateBy { it.item.key }
            run.dropped.mapNotNull { drop ->
                if (drop.reason != DropReason.SAME_TOPIC || drop.detail !in keys || !publicRequestedUrl(drop.item.url))
                    return@mapNotNull null
                classified[drop.item.key]?.let { drop.detail to it.copy(item = drop.item.copy(mergedUrls = emptyList())) }
            }
        }.groupBy({ it.first }, { it.second })
    }

    private fun pipelineConfig() = PipelineConfig.load(paths.pipelineConfig)
    private fun createMailer() = if (mailer == null) SmtpMailer.from(secrets) else mailer.invoke()

    suspend fun runDaily(options: DailyOptions = DailyOptions()): DailySummary {
        val args = buildList {
            if (options.keepUnseen) add("--keep-unseen")
            if (options.noLlm) add("--no-llm")
            options.budgetUsd?.let { addAll(listOf("--budget", it.toString())) }
        }
        return runDaily(args.toTypedArray(), options, clock.instant(), clock)
    }

    internal suspend fun runDaily(
        args: Array<String>, options: DailyOptions, startedAt: Instant, deadlineClock: Clock
    ): DailySummary {
        out.mkdirs()
        return runLock.withLock(out) {
            var result: PipelineResult? = null
            val published = executeDaily(args, startedAt, summary = { result = it },
                deadlineClock = deadlineClock, options = options)
            summarize(result, published)
        }
    }

    suspend fun runScheduled(options: ScheduledOptions = ScheduledOptions()): ScheduledSummary =
        runScheduled(options, null, clock.instant(), clock)

    internal suspend fun runScheduled(
        options: ScheduledOptions, sources: SourcesFile?, startedAt: Instant, deadlineClock: Clock
    ): ScheduledSummary {
        var skipped = false
        var summary: DailySummary? = null
        val previousMailRetries = mutableMapOf<MailSendResult, Int>()
        var expiredMailRetries = 0
        val scheduledMailer = if (options.mail) createMailer() else null
        val status = collector.dev.runScheduled(
            out, secrets, clock, daily = { start, log ->
                var result: PipelineResult? = null
                var published = false
                try {
                    published = executeDaily(emptyArray(), start, print = {}, summary = { result = it },
                        deadlineClock = deadlineClock, options = DailyOptions(batchOverride = options.batchOverrideForRun?.invoke() ?: options.batchOverride,
                            deadline = options.deadline, onStage = options.onStage, sources = sources, deferBatch = options.deferBatch,
                            includeMailCandidates = options.includeMailCandidates),
                        runDate = options.runDate,
                        onUnpublishedBatch = options.onUnpublishedBatch,
                        beforePublish = { day ->
                            if (options.runDate != null && options.mailEnabled()) {
                                try {
                                    queuePublicationMail(out, MailKind.DAILY, day, clock, options.mailEnabled)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    log("일일 메일 대기 기록 실패 · ${safeFailure(e)}")
                                }
                            }
                        })
                    published
                } finally {
                    val daily = summarize(result, published)
                    summary = daily
                    log("일일 요약 · 카드 ${daily.cards}개 · " +
                        String.format(Locale.ROOT, "추정 USD %.6f", daily.costUsd) +
                        " · 실패 출처 ${daily.failedSources}개")
                }
            }, mailer = scheduledMailer, startedAt = startedAt,
            deadlineClock = deadlineClock, noMail = !options.mail, mailEnabled = options.mailEnabled, deferMailDelivery = options.deferMailDelivery, runLock = runLock, runDate = options.runDate,
            onMail = { options.onStage(RunStage.MAIL) }, propagateLocked = true,
            requireDailyPublication = options.runDate != null,
            onLockAcquired = options.onLockAcquired,
            beforeRun = {
                options.beforeRun(LocalEditor())
                skipped = (options.resumeBatch && options.runDate?.let { !hasBatchToResume(it) } == true) ||
                    (options.honorSchedule &&
                        readJson<collector.data.ScheduleSettings>(out.resolve("settings/schedule.json"))?.enabled == false)
                !skipped
            },
            configuredDeadline = { options.deadline?.toMinutes()?.toInt() ?: pipelineConfig().deadlineMinutes },
            onPreviousMailRetry = { result -> previousMailRetries.merge(result, 1, Int::plus) },
            onMailRetryExpired = { count ->
                expiredMailRetries += count
                options.onMailRetriesExpired(count)
            }
        )
        val mailStatus = if (options.mailEnabled() && options.runDate != null && summary?.published == true) {
            if (scheduledMailer == null) ScheduledMailStatus.FAILED else scheduledMailStatus(options.runDate)
        } else null
        val mailFailureReason = if (mailStatus == ScheduledMailStatus.FAILED) {
            if (scheduledMailer == null) "메일 설정 없음"
            else mailFailureReason(requireNotNull(options.runDate))
        } else null
        return ScheduledSummary(status, summary, skipped, mailStatus, mailFailureReason, previousMailRetries, expiredMailRetries)
    }

    private fun summarize(result: PipelineResult?, published: Boolean) = DailySummary(
        published, result?.cards?.size ?: 0, result?.report?.costUsd ?: 0.0,
        result?.report?.failedSources?.size ?: 0
    )

    internal fun analysisCommand(
        print: (String) -> Unit = ::println,
        sendMail: ((String) -> Unit)? = null,
        beforePaidRun: () -> Unit = {},
        onEstimate: (AnalysisEstimate) -> Unit = {},
        approvedReservation: Double? = null,
        approvalBudgetVersion: String? = null,
        onFailure: (String, Boolean) -> Unit = { _, _ -> },
        onLockAcquired: () -> Unit = {}
    ) = AnalysisCommand(
        out, AnalysisConfig.load(paths.analysisConfig, PipelineConfig.defaults.analysis),
        PromptResolver(FilePromptStore(paths.promptsDir.toPath())), provider = {
            if (dailyProviders == null) {
                providerFactory(ProviderId.OPENAI, secrets.get("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }
                    ?: throw LlmKeyMissingException(ProviderId.OPENAI))
            } else {
                val providers = dailyProviders.invoke()
                val provider = providers.firstOrNull { it.id == ProviderId.OPENAI }
                providers.filter { it !== provider }.filterIsInstance<AutoCloseable>().forEach { it.close() }
                provider ?: throw LlmKeyMissingException(ProviderId.OPENAI)
            }
        }, clock = clock, print = print, sendMail = sendMail ?: { id ->
            if (delivery().send(MailKind.ANALYSIS, id) == MailSendResult.RECORD_FAILED) throw MailRecordFailure()
        }, beforePaidRun = beforePaidRun, runLock = runLock, onEstimate = onEstimate,
        approvedReservation = approvedReservation, approvalBudgetVersion = approvalBudgetVersion, onFailure = onFailure, onLockAcquired = onLockAcquired
    )

    suspend fun estimateAnalysis(themeIds: List<String>): AnalysisEstimate {
        var estimate = AnalysisEstimate(null, null, false)
        check(analysisCommand(print = {}, onEstimate = { estimate = it })
            .run((listOf("--analyze") + themeIds).toTypedArray()))
        return estimate
    }

    suspend fun runAnalysis(themeIds: List<String>, approvedMaxUsd: Double, mail: Boolean = true,
        budgetVersion: String? = null, onLockAcquired: () -> Unit = {},
        mailEnabled: () -> Boolean = { mail }): AnalysisSummary {
        var requote = false
        var failure: String? = null
        val successful = analysisCommand(print = {}, sendMail = { id ->
            if (mailEnabled() && delivery(mailEnabled).send(MailKind.ANALYSIS, id) == MailSendResult.RECORD_FAILED)
                throw MailRecordFailure()
        },
            approvedReservation = approvedMaxUsd, approvalBudgetVersion = budgetVersion, onLockAcquired = onLockAcquired,
            onFailure = { reason, stale -> requote = stale; failure = reason.takeIf(String::isNotBlank) }).run(
            (listOf("--analyze") + themeIds + "--yes").toTypedArray()
        )
        return AnalysisSummary(successful, requote, failure)
    }

    suspend fun publishTrend(date: LocalDate): TrendSummary = locked {
        TrendSummary(date, collector.trend.publishTrend(out, date))
    }

    private fun delivery(mailEnabled: () -> Boolean = { true }) =
        mailDelivery(out, secrets, mailer = createMailer(), clock = clock, mailEnabled = mailEnabled)

    internal suspend fun runMailCommand(args: Array<String>) = locked {
        collector.dev.runMailCommand(args, out, secrets, delivery(), clock)
    }

    suspend fun sendMail(kind: MailKind, key: String, resend: Boolean = false): MailSendResult? =
        locked { sendReportMail(out, delivery(), kind, key, resend) }

    fun previousMailFailureCount(date: LocalDate): Int = mailDelivery(out, secrets, mailer = null, clock = clock)
        .previousFailureCount(date)

    suspend fun mailTest(): MailSendResult = locked { delivery().test() }

    suspend fun testSource(config: collector.config.SourceConfig): List<collector.model.Item> {
        val http = Http.client()
        val adapter = if (sourceAdapters != null) sourceAdapters.first { it.id == config.sourceId } else when (config.sourceId) {
            "hn" -> HackerNewsAdapter(http)
            "github" -> GitHubAdapter(http, secrets)
            "github-issues" -> GitHubIssuesAdapter(http, secrets, repositoryCache = paths.cacheDir.resolve("github-repos.json"),
                debugDirectory = paths.debugDir)
            "hf" -> HuggingFaceAdapter(http)
            "reddit" -> RedditAdapter(http, secrets)
            "ph" -> ProductHuntAdapter(http, secrets)
            "rss" -> RssAdapter(http)
            else -> error("Unknown source")
        }
        return adapter.fetch(config, clock.instant())
    }

    private fun promptTrial(id: String, body: String, items: List<collector.model.Item>): LlmRequest {
        collector.prompt.PromptCatalog.requireEntry(id)
        require(items.size == 5)
        val schema = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"type":"object","properties":{"result":{"type":"string"}},"required":["result"],"additionalProperties":false}"""
        ) as kotlinx.serialization.json.JsonObject
        val input = items.joinToString("\n\n") { "${it.source}: ${it.title}\n${it.snippet}" }
        return LlmRequest("", PromptResolver.validate(body), input, schema, "prompt_trial", 1200)
    }

    private fun trialModel(id: String): StageModel {
        val models = pipelineConfig().models
        val preferred = if (id in setOf("ideas", "criteria", "analysis-opportunity")) models.generate else models.classify
        if (dailyProviders != null || secrets.get("${preferred.provider.name}_API_KEY") != null) return preferred
        val other = ProviderId.entries.firstOrNull { secrets.get("${it.name}_API_KEY") != null } ?: return preferred
        val tier = ModelCatalog.defaults.requirePrice(preferred.provider, preferred.model).tier
        return checkNotNull(ModelCatalog.defaults.defaultModel(other, tier))
    }

    fun estimatePromptTrial(id: String, body: String, items: List<collector.model.Item>): Double =
        LlmRouter(emptyList(), CostGuard(0.0)).projectedTokens(promptTrial(id, body, items), trialModel(id))

    suspend fun runPromptTrial(id: String, body: String, items: List<collector.model.Item>, approvedUsd: Double): LlmResult = locked {
        val request = promptTrial(id, body, items)
        val model = trialModel(id)
        val budget = BudgetSettings.load(out) {}
        val monthly = MonthlyBudget(out, budget)
        val guard = CostGuard(budget.dailyCardUsd, clock = clock,
            ledger = costLedger(out, FileHistorySearch(out, pipelineConfig().historyDays)), allowsReservation = monthly::allows)
        val projection = LlmRouter(emptyList(), guard).projectedTokens(request, model)
        if (projection > dailyCardBudgetRemaining(LocalDate.now(clock)) ||
            !monthly.allows(LocalDate.now(clock), projection)) throw BudgetExceededException()
        require(projection <= approvedUsd)
        val providers = dailyProviders?.invoke() ?: listOf(providerFactory(model.provider,
            secrets.get("${model.provider.name}_API_KEY") ?: throw LlmKeyMissingException(model.provider)))
        try {
            val router = LlmRouter(providers, guard, mapOf("test" to model), fallback = false, preflightBudget = true,
                timeoutMillis = 60_000)
            router.complete("test", request, id, "trial", allowFallback = false)
        } finally {
            providers.forEach { (it as? AutoCloseable)?.close() }
        }
    }

    suspend fun budgetStatus(): List<String> = locked {
        MonthlyBudget(out, BudgetSettings.load(out)).status(LocalDate.now(clock))
    }

    suspend fun setBudget(changes: List<String>): Map<String, JsonElement> = locked {
        val updated = BudgetSettings.update(out, changes)
        if ("analysisRequestUsd" in updated || "analysisMonthlyUsd" in updated) {
            MonthlyBudget(out, BudgetSettings.load(out)).analysisBlockReason(LocalDate.now(clock))
        }
        updated
    }

    fun hasPendingBatch(date: LocalDate): Boolean = batchPaths(date).any(::isPendingBatch)

    fun hasTerminalDailyRecord(date: LocalDate): Boolean {
        if (out.resolve("$date.json").isFile) return true
        return listOf("runs", "runs/failed").any { directory ->
            out.resolve(directory).listFiles().orEmpty().any { file ->
                file.isFile && file.name.startsWith("$date-") && file.extension == "json" &&
                    (directory == "runs/failed" || runCatching {
                        val issues = readJson<PipelineResult>(file)?.report?.issues.orEmpty()
                        issues.none { it in setOf(BATCH_CONTINUATION_PENDING, CLASSIFICATION_BATCH_PENDING, "Batch 결과 대기") }
                    }.getOrDefault(true))
            }
        }
    }

    fun hasBatchToResume(date: LocalDate): Boolean =
        !out.resolve("$date.json").isFile &&
            (hasPendingBatch(date) || batchPaths(date).any(::hasSettledBatchResults))

    fun scheduledMailStatus(date: LocalDate): ScheduledMailStatus {
        val daily = publicationMailStatus(date, MailKind.DAILY)
        val trend = MailKind.TREND.takeIf {
            date.dayOfWeek == java.time.DayOfWeek.SUNDAY && it.file(out, date.toString(), "json").isFile
        }?.let { publicationMailStatus(date, it) }
        return when {
            ScheduledMailStatus.FAILED in listOf(daily, trend) -> ScheduledMailStatus.FAILED
            ScheduledMailStatus.PENDING in listOf(daily, trend) -> ScheduledMailStatus.PENDING
            else -> ScheduledMailStatus.SENT
        }
    }

    private fun mailFailureReason(date: LocalDate): String =
        runCatching { MailDelivery(out, null, clock = clock).failureReason(date.toString()) }
            .getOrDefault("메일 기록 읽기 실패")

    private fun publicationMailStatus(date: LocalDate, kind: MailKind): ScheduledMailStatus =
        runCatching { MailDelivery(out, null, clock = clock).status(kind, date.toString()) }
            .getOrDefault(ScheduledMailStatus.FAILED)

    fun dailyCost(date: LocalDate): Double = collector.dev.FileCostLedger(out.resolve("cost-ledger")).total(date)

    fun dailyCardBudgetRemaining(date: LocalDate): Double {
        val budget = BudgetSettings.load(out) {}
        val spend = costLedger(out, FileHistorySearch(out, pipelineConfig().historyDays)).total(date)
        return (budget.dailyCardUsd - spend).coerceAtLeast(0.0)
    }

    fun dailyBudgetExceeded(date: LocalDate): Boolean {
        val settings = BudgetSettings.load(out) {}
        val monthly = MonthlyBudget(out, settings)
        return dailyCardBudgetRemaining(date) <= 0.0 || monthly.dailySkipReason(date, settings.dailyCardUsd) != null
    }

    suspend fun applyLocalChanges(changes: List<collector.data.InboxRequest>) = locked {
        LocalEditor().applyChanges(changes)
    }

    suspend fun saveLocalConfig(name: String, json: String) = locked {
        LocalEditor().saveConfig(name, json)
    }

    suspend fun editLocal(change: (LocalEditor) -> Unit) = locked { change(LocalEditor()) }

    inner class LocalEditor internal constructor() {
        fun applyChanges(changes: List<collector.data.InboxRequest>) {
            require(changes.all {
                it !is collector.data.InboxRequest.Analysis && it.schemaVersion == collector.data.DataBranch.VERSION &&
                    collector.data.requestIdPattern.matches(it.requestId)
            })
            val editor = collector.data.InboxProcessor(out, paths.promptsDir, clock, paths.pipelineConfig)
            changes.forEach { editor.apply(it, Instant.parse(it.createdAt)) }
        }

        fun saveConfig(name: String, json: String) {
            val target = when (name) {
                "pipeline.local.json" -> paths.pipelineConfig
                "sources.local.json" -> paths.sourcesConfig
                "analysis.local.json" -> paths.analysisConfig
                else -> throw IllegalArgumentException()
            }
            val candidate = File.createTempFile("config-", ".tmp", paths.configDir.apply { mkdirs() })
            try {
                candidate.writeText(json)
                when (target) {
                    paths.pipelineConfig -> PipelineConfig.load(candidate)
                    paths.sourcesConfig -> ConfigLoader.load(candidate)
                    paths.analysisConfig -> AnalysisConfig.load(candidate, PipelineConfig.defaults.analysis, report = {})
                }
                target.writeAtomically(json)
            } finally {
                candidate.delete()
            }
        }
    }

    private fun batchPaths(date: LocalDate) = listOf("classify", "generate").map {
        out.resolve("batch/$date-$it.json")
    }

    private suspend fun <T> withBatchProvider(block: suspend (BatchCapableProvider?) -> T): T {
        val providers = dailyProviders?.invoke() ?: listOfNotNull(batchRecoverySecrets.get("ANTHROPIC_API_KEY")?.let {
            providerFactory(ProviderId.ANTHROPIC, it)
        })
        return try {
            block(providers.firstOrNull { it.id == ProviderId.ANTHROPIC } as? BatchCapableProvider)
        } finally {
            providers.filterIsInstance<AutoCloseable>().forEach { it.close() }
        }
    }

    suspend fun batchInProgress(date: LocalDate): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(60_000) {
            withBatchProvider { provider ->
                provider != null && batchPaths(date).any { collector.llm.batchInProgress(it, provider) }
            }
        } ?: true

    suspend fun maintainPendingBatch(date: LocalDate) = locked {
        val config = pipelineConfig()
        val history = FileHistorySearch(out, config.historyDays)
        val guard = CostGuard(config.dailyBudgetUsd, clock = clock, ledger = costLedger(out, history))
        withBatchProvider { provider ->
            batchPaths(date).filter(::isPendingBatch).forEach { path ->
                kotlinx.coroutines.withTimeoutOrNull(60_000) {
                    runMessageBatch(provider, guard, null, path, config.batch, emptyList(), emptyList(), null,
                        emptyList(), { 60_000 }, pollRemainingMillis = { 5_000 },
                        recoveryRemainingMillis = { 5_000 }, recovering = true, stateHook = batchCheckpointHook)
                }
            }
        }
    }

    suspend fun <T> withRunLock(block: suspend () -> T): T = locked(block)

    private suspend fun <T> locked(block: suspend () -> T): T {
        out.mkdirs()
        return runLock.withLock(out, block)
    }

    private suspend fun executeDaily(
        args: Array<String>,
        runStartedAt: Instant? = null,
        print: (String) -> Unit = ::println,
        summary: (PipelineResult) -> Unit = {},
        deadlineClock: Clock = clock,
        options: DailyOptions,
        runDate: LocalDate? = null,
        beforePublish: (LocalDate) -> Unit = {},
        onUnpublishedBatch: suspend (LocalDate) -> Unit = {}
    ): Boolean {
        val defaults = pipelineConfig().let { config ->
            val enabled = options.batchOverride
            if (enabled == null) config
            else config.copy(batch = config.batch.copy(classify = enabled, generate = enabled))
        }
        var budgetOverride: Double? = null
        var keepUnseen = false
        var noLlm = false
        var index = 0
        while (index < args.size) {
            when (args[index++]) {
                "--keep-unseen" -> keepUnseen = true
                "--no-llm" -> noLlm = true
                "--budget" -> budgetOverride = requireNotNull(args.getOrNull(index++)?.toDoubleOrNull())
                else -> throw IllegalArgumentException("지원하지 않는 실행 옵션입니다.")
            }
        }
        budgetOverride?.let { require(it.isFinite() && it >= 0.0) }
        val settings = BudgetSettings.load(out, print)
        val config = defaults.copy(dailyBudgetUsd = budgetOverride ?: settings.dailyCardUsd)
        val monthlyBudget = MonthlyBudget(out, settings)
        val sources = options.sources ?: ConfigLoader.load(paths.sourcesConfig)
        val profiles = ProfileLoader.loadDefaults()
        val routingIssues = ProfileLoader.validateRouting(sources, profiles)
        if (routingIssues.isNotEmpty()) {
            print("출처 설정 오류:\n- " + routingIssues.joinToString("\n- "))
            throw InvalidSourceRoutingException()
        }
        val prompts = PromptResolver(FilePromptStore(paths.promptsDir.toPath()))
        for (id in profiles.ids) {
            prompts.resolve("profile.$id.match")
            prompts.resolve("profile.$id.analysis")
        }
        val history = FileHistorySearch(out, defaults.historyDays)
        val ledger = if (noLlm) null else costLedger(out, history)
        val startedAt = runStartedAt ?: clock.instant()
        val today = runDate ?: startedAt.atZone(clock.zone).toLocalDate()
        val runClockStart = clock.instant()
        val generationCheckpoint = batchPath(out, today.toString(), "generate")
        val pendingBatches = out.resolve("batch").listFiles().orEmpty().any {
            it.isFile && it.extension == "json" && it.name != "index.json"
        } || out.resolve("batch/quarantine").listFiles().orEmpty().any { it.name.endsWith(".unsettled.json") }
        val resumingBatch = hasSavedBatch(out, today.toString(), "classify") || hasSavedBatch(out, today.toString(), "generate")
        val batchDisabled = options.batchOverride == false
        val pendingToday = isPendingBatch(batchPath(out, today.toString(), "classify")) || isPendingBatch(generationCheckpoint)
        fun budgetSkipReason(): String? {
            if (noLlm) return null
            if (batchDisabled && pendingToday) return "Batch 결과 대기 · Batch를 켜면 이어서 처리"
            if (resumingBatch) return null
            return try {
                monthlyBudget.dailySkipReason(today, config.dailyBudgetUsd)
            } catch (_: IllegalArgumentException) {
                "Cost ledger could not be read"
            } catch (_: java.io.IOException) {
                "Cost ledger could not be read"
            }
        }
        val initialSkip = if (pendingBatches && !batchDisabled) null else budgetSkipReason()
        val budgetClock = if (runDate == null) clock else Clock.fixed(startedAt.atZone(clock.zone).with(today).toInstant(), clock.zone)
        val costs = CostGuard(config.dailyBudgetUsd, clock = budgetClock, ledger = ledger,
            allowsReservation = { day, amount -> monthlyBudget.allows(day, amount) })
        val http = Http.client()
        val providers = mutableListOf<LlmProvider>()
        try {
            if (!noLlm && (initialSkip == null || pendingBatches && !batchDisabled)) {
                if (dailyProviders != null) {
                    providers += dailyProviders.invoke()
                } else {
                    secrets.get("ANTHROPIC_API_KEY")?.let { providers += providerFactory(ProviderId.ANTHROPIC, it) }
                    secrets.get("OPENAI_API_KEY")?.let { providers += providerFactory(ProviderId.OPENAI, it) }
                }
            }
            val models = buildMap {
                val active = providers.map { it.id }
                val classify = defaults.models.classify
                val generate = defaults.models.generate
                if (classify.provider in active || active.isEmpty()) {
                    put("classify", classify)
                    put("track", classify)
                }
                if (generate.provider in active || active.isEmpty()) put("generate", generate)
            }
            val router = LlmRouter(providers, costs, models, defaults.models.fallback, preflightBudget = true,
                batchCheckpointHook = batchCheckpointHook, batchEnabled = !batchDisabled, deferBatch = options.deferBatch)
            router.startRun(startedAt, options.deadline?.toMinutes()?.toInt() ?: config.deadlineMinutes, deadlineClock.instant())
            val recoveryIssues = if (!noLlm && (!batchDisabled || pendingBatches)) {
                if (pendingBatches) {
                    options.onStage(RunStage.RECOVER)
                    currentCoroutineContext().ensureActive()
                }
                val recoveryKey = if (pendingBatches && dailyProviders == null && providers.none { it.id == ProviderId.ANTHROPIC })
                    batchRecoverySecrets.get("ANTHROPIC_API_KEY") else null
                val recoveryProvider = recoveryKey?.let { providerFactory(ProviderId.ANTHROPIC, it) }
                try {
                    val recoveryRouter = if (recoveryProvider == null) router else LlmRouter(listOf(recoveryProvider), costs,
                        batchCheckpointHook = batchCheckpointHook).also {
                            it.startRun(startedAt, options.deadline?.toMinutes()?.toInt() ?: config.deadlineMinutes, deadlineClock.instant())
                        }
                    recoveryRouter.recoverBatches(out, config.batch, config.recentTopicDays) {
                        it.name == "$today-classify.json" || it.name == "$today-generate.json"
                    }
                } finally {
                    (recoveryProvider as? AutoCloseable)?.close()
                }
            } else emptyList()
            if (runDate != null) {
                out.resolve("batch").listFiles().orEmpty().asSequence()
                    .filter { it.name.endsWith("-classify.json") || it.name.endsWith("-generate.json") ||
                        it.name.endsWith("-classify.done") || it.name.endsWith("-generate.done") }
                    .mapNotNull { runCatching { LocalDate.parse(it.name.take(10)) }.getOrNull() }
                    .filter { it != today }.distinct()
                    .filter { !out.resolve("$it.json").isFile && batchPaths(it).any(::hasSettledBatchResults) }
                    .toList().forEach { onUnpublishedBatch(it) }
            }
            val skipReason = budgetSkipReason()
            val tracked = JsonFileTrackedStore(out.resolve("tracked.json"))
            val seen = JsonFileSeenStore(out.resolve("seen.json"))
            val reddit = RedditAdapter(http, secrets)
            val full = FullPipeline(config, profiles, prompts, router, costs, http, history, tracked,
                collectOnly = noLlm, reddit = reddit, out = out, snapshotBatchInputs = runDate != null)
            val adapters = sourceAdapters ?: listOf(HackerNewsAdapter(http), GitHubAdapter(http, secrets), HuggingFaceAdapter(http),
                GitHubIssuesAdapter(http, secrets, repositoryCache = paths.cacheDir.resolve("github-repos.json"),
                    debugDirectory = paths.debugDir), reddit, ProductHuntAdapter(http, secrets), RssAdapter(http))
            val pipeline = Pipeline(adapters, seen, clock = {
                startedAt.atZone(clock.zone).with(today).toInstant()
            }, zone = clock.zone,
                onStage = {
                    when (it) {
                        "collect" -> options.onStage(RunStage.COLLECT)
                        "classify" -> options.onStage(RunStage.CLASSIFY)
                        "generate" -> options.onStage(RunStage.GENERATE)
                    }
                    print("· 단계 \u00b7 ${HtmlRenderer.stageName(it)}") }, full = full,
                paidStageSkipReason = skipReason)
            var result = try {
                val finished = kotlinx.coroutines.withTimeoutOrNull(router.remainingMillis()) { pipeline.run(sources) }
                if (finished == null) {
                    val report = RunReport(today.toString(), startedAt.toString(), emptyList(), emptyList(), emptyMap(),
                        java.time.Duration.between(runClockStart, clock.instant()).toMillis(), llmCalls = costs.drainCalls(),
                        issues = recoveryIssues + "실행 마감")
                    val expired = PipelineResult(emptyList(), emptyList(), report)
                    summary(expired)
                    archiveFile(out, report).writeAtomically(outputJson.encodeToString(expired))
                    print("실행 마감 · 다음 실행에서 이어서")
                    return false
                }
                finished.copy(report = finished.report.copy(
                    issues = (recoveryIssues + finished.report.issues).distinct(),
                    llmCalls = finished.report.llmCalls + costs.drainCalls()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!noLlm) {
                    val day = today
                    val report = RunReport(day.toString(), startedAt.toString(), emptyList(), emptyList(),
                        emptyMap(), java.time.Duration.between(startedAt, clock.instant()).toMillis(),
                        llmCalls = costs.drainCalls(), issues = recoveryIssues + safeFailure(e))
                    val failed = PipelineResult(emptyList(), emptyList(), report)
                    summary(failed)
                    val archived = archiveFailedRun(out, failed)
                    print("실패 실행 보관 · out/runs/failed/${archived.name}")
                    if (history.runs(day.plusDays(1), 1).firstOrNull()?.cards?.isNotEmpty() == true) {
                        print("기존 카드 결과 유지 · out/$day.json")
                    }
                }
                throw e
            }
            summary(result)
            val day = LocalDate.parse(result.report.runDate)
            if (runDate != null && !pendingBatches && result.report.sources.isNotEmpty() &&
                result.report.sources.all { it.error != null } && result.report.costUsd == 0.0) {
                archiveFailedRun(out, result)
                return false
            }
            if (skipReason != null) {
                val archived = archiveFile(out, result.report)
                archived.writeAtomically(outputJson.encodeToString(result))
                print("$skipReason · ${archived.path}")
                return false
            }
            if (noLlm) {
                out.resolve("$day-collect.json").writeAtomically(outputJson.encodeToString(result))
                if (!keepUnseen) seen.markSeen(result.items, day)
                print("수집·정제 ${result.items.size}건 · out/$day-collect.json")
                return false
            }
            if (CLASSIFICATION_BATCH_PENDING in result.report.issues || isPendingBatch(generationCheckpoint)) {
                val pendingResult = if (runDate != null) result.copy(report = result.report.copy(
                    issues = result.report.issues + BATCH_CONTINUATION_PENDING)) else result
                archiveFile(out, result.report).writeAtomically(outputJson.encodeToString(pendingResult))
                router.markAppliedBatchesConsumed()
                result.report.issues.forEach { print("· $it") }
                if (options.deferBatch) throw BatchDeferredException()
                return false
            }
            // 앱 실행: 예산 상한·호출 실패로 분류를 끝내지 못해 카드가 0장이면 "후보 없음"과 구분해 게시하지 않는다.
            val classificationIncomplete = result.metrics.profiles.values.sumOf { it.classified } == 0 &&
                result.report.dropCounts.keys.any { it == DropReason.BUDGET_EXCEEDED || it == DropReason.CLASSIFY_FAILED }
            if (runDate != null && result.cards.isEmpty() && (classificationIncomplete || resumingBatch &&
                (hasSettledBatchResults(generationCheckpoint) || result.metrics.profiles.values.any { it.selected > 0 }))) {
                archiveFailedRun(out, result)
                return false
            }
            val renderer = HtmlRenderer(IdeaGenerator(defaults, prompts, router).categories,
                includeCandidates = options.includeMailCandidates)
            val rendered = publishOrArchiveFailure(out, history, renderer, result, keepUnseen, beforePublish)
            router.markAppliedBatchesConsumed()
            if (router.remainingMillis() == 0L) {
                result = result.copy(report = result.report.copy(issues = result.report.issues + "Publication exceeded the execution deadline"))
                val file = if (rendered == null) archiveFile(out, result.report) else out.resolve("$day.json")
                file.writeAtomically(outputJson.encodeToString(result.copy(metrics = if (rendered == null) result.metrics else result.metrics.copy(snapshots = emptyList()),
                    trackClassified = emptyMap())))
                summary(result)
            }
            print("${rendered?.subject ?: "카드 0개"} · 추적 ${result.trackingUpdates.size}건 · " +
                String.format(Locale.ROOT, "추정 USD %.6f", result.report.costUsd))
            result.metrics.profiles.forEach { (profile, counts) ->
                print("${profiles[profile]?.config?.name ?: profile} · 분류 ${counts.classified}건 · 기준 점수 통과 ${counts.thresholdPassed}건 · 최종 카드 후보 ${counts.selected}건")
            }
            result.report.failedSources.forEach { print("출처 실패 · ${it.source} · ${it.error}") }
            result.report.issues.forEach { print("· $it") }
            if (rendered == null) print("기존 카드 결과 유지 · 새 실행은 out/runs/에 보관")
            else print("결과 저장 · out/$day.json · out/$day.html · out/$day.txt")
            return rendered != null
        } finally {
            providers.filterIsInstance<AutoCloseable>().forEach { it.close() }
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }


    internal suspend fun runBackfill(args: Array<String>, startedAt: Instant, print: (String) -> Unit = ::println) {
        val defaults = pipelineConfig()
        val period = args.getOrNull(1).orEmpty()
        val days = if (period == "4w") 28 else {
            val match = requireNotNull(Regex("([1-9][0-9]*)d").matchEntire(period))
            requireNotNull(match.groupValues[1].toIntOrNull())
        }
        var dryRun = false
        var yes = false
        var force = false
        var budgetOverride: Double? = null
        var date = LocalDate.now(clock)
        var index = 2
        while (index < args.size) {
            when (args[index++]) {
                "--dry-run" -> dryRun = true
                "--yes" -> yes = true
                "--force" -> force = true
                "--budget" -> budgetOverride = requireNotNull(args.getOrNull(index++)?.toDoubleOrNull())
                "--date" -> {
                    val value = args.getOrNull(index++)
                    date = requireNotNull(runCatching { LocalDate.parse(value) }.getOrNull()?.takeIf {
                        value?.length == 10 && it.toString() == value
                    })
                }
                else -> throw IllegalArgumentException("지원하지 않는 백필 옵션입니다.")
            }
        }
        budgetOverride?.let { require(it.isFinite() && it >= 0.0) }
        out.mkdirs()
        runLock.withLock(out) {
            val settings = BudgetSettings.load(out)
            val monthlyBudget = MonthlyBudget(out, settings)
            val sources = ConfigLoader.load(paths.sourcesConfig)
            val execute = yes && !dryRun
            val history = FileHistorySearch(out, defaults.historyDays)
            val costs = CostGuard(budgetOverride ?: settings.backfillRunUsd,
                ledger = if (execute) costLedger(out, history) else null, budgetPerRun = true,
                allowsReservation = monthlyBudget::allows)
            val anthropicKey = secrets.get("ANTHROPIC_API_KEY")
            val openAiKey = secrets.get("OPENAI_API_KEY")
            val model = if (anthropicKey != null || openAiKey == null) defaults.models.classify else
                checkNotNull(ModelCatalog.defaults.defaultModel(ProviderId.OPENAI, ModelTier.FAST))
            val providers = mutableListOf<LlmProvider>()
            val http = Http.client()
            try {
                var runRouter: LlmRouter? = null
                val classifier = if (execute) {
                    anthropicKey?.let { providers += providerFactory(ProviderId.ANTHROPIC, it) }
                    openAiKey?.let { providers += providerFactory(ProviderId.OPENAI, it) }
                    val profiles = ProfileLoader.loadDefaults()
                    val routingIssues = ProfileLoader.validateRouting(sources, profiles)
                    if (routingIssues.isNotEmpty()) {
                        print("출처 설정 오류:\n- " + routingIssues.joinToString("\n- "))
                        throw InvalidSourceRoutingException()
                    }
                    val router = LlmRouter(providers, costs, mapOf("classify" to model), defaults.models.fallback, preflightBudget = true, batchCheckpointHook = batchCheckpointHook)
                    router.startRun(startedAt, defaults.deadlineMinutes)
                    runRouter = router
                    val activeDates = (1..days).map { "${date.minusDays(it.toLong())}-backfill.json" }.toSet()
                    router.recoverBatches(out, defaults.batch, defaults.recentTopicDays) { it.name in activeDates }.forEach(print)
                    Classifier(defaults, profiles, PromptResolver(FilePromptStore(paths.promptsDir.toPath())), router)
                } else null
                val backfill = Backfill(out, sources, HackerNewsAdapter(http), GitHubAdapter(http, secrets), model, costs,
                    classifier, print = print, clock = clock::instant, monthlyBudget = monthlyBudget)
                if (runRouter == null) {
                    backfill.run(date, days, dryRun, yes, force)
                } else if (kotlinx.coroutines.withTimeoutOrNull(runRouter.remainingMillis()) {
                        backfill.run(date, days, dryRun, yes, force)
                    } == null) {
                    print("실행 마감 · 다음 실행에서 이어서")
                }
            } finally {
                providers.filterIsInstance<AutoCloseable>().forEach { it.close() }
                http.dispatcher.executorService.shutdown()
                http.connectionPool.evictAll()
            }
        }
    }


}

internal class InvalidSourceRoutingException : RuntimeException()

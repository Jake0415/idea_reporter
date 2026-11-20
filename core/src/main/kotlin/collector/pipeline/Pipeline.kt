package collector.pipeline

import collector.config.SourcesFile
import collector.model.*
import collector.port.SeenStore
import collector.refine.Deduplicator
import collector.refine.RuleFilter
import collector.refine.Scorer
import collector.refine.SourceCap
import collector.refine.SpamClusterFilter
import collector.source.SourceAdapter
import collector.source.GitHubAdapter
import collector.source.GitHubIssuesAdapter
import collector.source.GitHubCollectionException
import collector.config.GitHubIssuesConfig
import collector.config.RssConfig
import collector.config.SourceConfig
import collector.source.RssAdapter
import collector.source.RssFetchException
import collector.source.MissingSourceSecret
import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal fun filterCollected(
    items: List<Item>, configs: Map<String, SourceConfig>, excludeKeywords: List<String>,
    now: Instant, historicalStart: Instant? = null
): collector.refine.FilterResult {
    val ruled = RuleFilter(excludeKeywords).filter(items, configs, now, historicalStart)
    val despammed = SpamClusterFilter().filter(ruled.kept)
    val capped = SourceCap.cap(despammed.kept, configs)
    return capped.copy(dropped = ruled.dropped + despammed.dropped + capped.dropped)
}

@kotlinx.serialization.Serializable
data class PipelineResult(
    val items: List<Item>,           // 남은 항목, 점수 내림차순
    val dropped: List<DroppedItem>,  // 탈락 항목과 이유
    val report: RunReport,
    val classified: List<Signal> = emptyList(),
    val cards: List<IdeaCard> = emptyList(),
    val trackingUpdates: List<TrackingUpdate> = emptyList(),
    val topics: List<TopicHistory> = emptyList(),
    @kotlinx.serialization.Serializable(with = RunMetricsSerializer::class)
    val metrics: RunMetrics = RunMetrics(),
    val tracked: List<TrackedIdea> = emptyList(),
    val trackingOnlyKeys: Set<String> = emptySet(),
    val trackClassified: Map<String, TrackClassification> = emptyMap()
)

/**
 * 수집·정제 후 선택적으로 카드와 추적 업데이트를 작성한다. 이미 본 글은 결과 저장 후 기록한다.
 */
class Pipeline(
    adapters: List<SourceAdapter>,
    private val seen: SeenStore,
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val sourceTimeout: Duration = 45.seconds,
    private val onStage: (String) -> Unit = {},
    private val full: FullPipeline? = null,
    private val paidStageSkipReason: String? = null
) {
    private val adapterById = adapters.associateBy { it.id }

    suspend fun run(file: SourcesFile): PipelineResult {
        val startedAt = clock()
        val today = LocalDate.ofInstant(startedAt, zone)
        val paid = full?.takeIf { paidStageSkipReason == null }
        val tracked = paid?.tracked().orEmpty()
        val totalStart = System.nanoTime()
        val stages = mutableListOf<StageResult>()
        val dropped = mutableListOf<DroppedItem>()
        val configs = file.sources.filter { it.enabled }

        // ① 수집 — 출처별 병렬, 하나가 실패해도 계속
        onStage("collect")
        var t = System.nanoTime()
        val (fetches, search) = coroutineScope {
            val repositories = configs.filter { it.sourceId == "github" }
                .associateWith { config -> async { fetchOne(config, startedAt) } }
            val regular = configs.map { config ->
                repositories[config] ?: async {
                    if (config.sourceId == "github-issues") repositories.values.awaitAll()
                    fetchOne(config, startedAt)
                }
            }
            val dailyGitHub = configs.indices.filter { configs[it].sourceId in setOf("github", "github-issues") }
                .map(regular::get)
            val tracking = async {
                paid?.search(tracked, configs, adapterById, startedAt, file.excludeKeywords) { dailyGitHub.awaitAll() }
            }
            regular.awaitAll() to tracking.await()
        }
        val normal = fetches.flatMap { it.items }
        val (tracking, trackingIssues) = search ?: (emptyList<Item>() to emptyList())
        val collected = normal + tracking
        stages += StageResult("collect", 0, collected.size, ms(t))

        // ② 규칙 필터
        onStage("filter")
        t = System.nanoTime()
        val configById = configs.associateBy { it.sourceId }
        val filtered = filterCollected(normal, configById, file.excludeKeywords, startedAt)
        dropped += filtered.dropped
        stages += StageResult("filter", normal.size, filtered.kept.size, ms(t))

        // ③ 이전 날짜에 본 글 제외 + 중복 제거
        onStage("dedupe")
        t = System.nanoTime()
        val unseen = seen.filterUnseen(filtered.kept, today)
        val unseenItems = unseen.toHashSet()
        filtered.kept.filter { it !in unseenItems }
            .forEach { dropped += DroppedItem(it, DropReason.ALREADY_SEEN) }
        val deduped = Deduplicator().dedupe(unseen)
        dropped += deduped.dropped
        stages += StageResult("dedupe", filtered.kept.size, deduped.kept.size, ms(t))

        // 점수 통일(출처 안 백분위)
        t = System.nanoTime()
        val scored = Scorer.score(deduped.kept)
            .sortedWith(compareByDescending<Item> { it.score }.thenByDescending { it.alsoSeenIn.size }.thenByDescending { it.rawScore })
        stages += StageResult("score", deduped.kept.size, scored.size, ms(t))

        val report = RunReport(
            runDate = today.toString(),
            startedAt = startedAt.toString(),
            sources = fetches.map { (source) -> source.copy(trackingFetched = tracking.count { it.source == source.source }) },
            stages = stages,
            dropCounts = dropped.groupingBy { it.reason }.eachCount(),
            totalMillis = ms(totalStart),
            issues = trackingIssues + fetches.mapNotNull { it.warning } + listOfNotNull(paidStageSkipReason),
            paidStagesSkipped = paidStageSkipReason != null
        )
        val base = PipelineResult(scored, dropped, report)
        return paid?.takeUnless { it.collectOnly }?.run(base, collected, tracking, tracked, startedAt, today, onStage) ?: base
    }

    private data class Fetch(val source: SourceResult, val items: List<Item> = emptyList(), val warning: String? = null)

    private suspend fun fetchOne(config: collector.config.SourceConfig, now: Instant): Fetch {
        val start = System.nanoTime()
        val adapter = adapterById[config.sourceId]
            ?: return Fetch(SourceResult(config.sourceId, 0, "어댑터 없음", 0))
        fun failure(reason: String?) = Fetch(SourceResult(config.sourceId, 0, reason, ms(start)))
        return try {
            val (fetched, warning) = if (adapter is RssAdapter && config is RssConfig) {
                val result = adapter.fetchWithSummary(config, now, sourceTimeout)
                result.items to result.warning
            } else if (adapter is GitHubAdapter) {
                adapter.fetchWithWarning(config, now, sourceTimeout)
            } else if (adapter is GitHubIssuesAdapter && config is GitHubIssuesConfig) {
                adapter.fetchWithWarning(config, now, config.sourceTimeoutSeconds.seconds)
            } else withTimeout(sourceTimeout) { adapter.fetch(config, now) } to null
            val items = fetched.map { if (it.profileHint.isEmpty()) it.copy(profileHint = config.profile) else it }
            Fetch(SourceResult(config.sourceId, items.size, millis = ms(start)), items, warning)
        } catch (e: TimeoutCancellationException) {
            failure("시간 초과(${sourceTimeout})")
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubCollectionException) {
            failure(e.message)
        } catch (e: RssFetchException) {
            failure(e.message)
        } catch (e: MissingSourceSecret) {
            failure(e.message)
        } catch (e: Exception) {
            failure(if (full == null) e.message ?: "수집 요청 실패" else safeFailure(e))
        }
    }

    private fun safeFailure(e: Exception): String {
        val status = (e as? IOException)?.message?.let { Regex("^HTTP (\\d{3})\\b").find(it)?.groupValues?.get(1) }
        return status?.let { "수집 요청 실패 (HTTP $it)" } ?: "수집 요청 실패"
    }

    private fun ms(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000
}

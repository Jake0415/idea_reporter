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

@kotlinx.serialization.Serializable
data class PipelineResult(
    val items: List<Item>,           // 남은 항목, 점수 내림차순
    val dropped: List<DroppedItem>,  // 탈락 항목과 이유
    val report: RunReport,
    val classified: List<Signal> = emptyList(),
    val cards: List<IdeaCard> = emptyList(),
    val trackingUpdates: List<TrackingUpdate> = emptyList(),
    val topics: List<TopicHistory> = emptyList(),
    val metrics: List<MetricSnapshot> = emptyList(),
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
    private val full: FullPipeline? = null
) {
    private val adapterById = adapters.associateBy { it.id }

    suspend fun run(file: SourcesFile): PipelineResult {
        val startedAt = clock()
        val today = LocalDate.ofInstant(startedAt, zone)
        val tracked = full?.tracked().orEmpty()
        val totalStart = System.nanoTime()
        val stages = mutableListOf<StageResult>()
        val dropped = mutableListOf<DroppedItem>()
        val configs = file.sources.filter { it.enabled }

        // ① 수집 — 출처별 병렬, 하나가 실패해도 계속
        onStage("collect")
        var t = System.nanoTime()
        val (fetches, search) = coroutineScope {
            val regular = configs.map { config -> async { fetchOne(config, startedAt) } }
            val tracking = async { full?.search(tracked, configs, adapterById, startedAt, file.excludeKeywords) }
            regular.awaitAll() to tracking.await()
        }
        val normal = fetches.flatMap { it.second }
        val (tracking, trackingIssues) = search ?: (emptyList<Item>() to emptyList())
        val collected = normal + tracking
        stages += StageResult("collect", 0, collected.size, ms(t))

        // ② 규칙 필터
        onStage("filter")
        t = System.nanoTime()
        val configById = configs.associateBy { it.sourceId }
        val ruled = RuleFilter(file.excludeKeywords).filter(normal, configById, startedAt)
        val despammed = SpamClusterFilter().filter(ruled.kept)
        val filtered = SourceCap.cap(despammed.kept, configById)
        dropped += ruled.dropped + despammed.dropped + filtered.dropped
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
            sources = fetches.map { (source, _) -> source.copy(trackingFetched = tracking.count { it.source == source.source }) },
            stages = stages,
            dropCounts = dropped.groupingBy { it.reason }.eachCount(),
            totalMillis = ms(totalStart),
            issues = trackingIssues
        )
        val base = PipelineResult(scored, dropped, report)
        return full?.takeUnless { it.collectOnly }?.run(base, collected, tracking, tracked, startedAt, today, onStage) ?: base
    }

    private suspend fun fetchOne(config: collector.config.SourceConfig, now: Instant): Pair<SourceResult, List<Item>> {
        val start = System.nanoTime()
        val adapter = adapterById[config.sourceId]
            ?: return SourceResult(config.sourceId, 0, "어댑터 없음", 0) to emptyList()
        return try {
            val items = withTimeout(sourceTimeout) { adapter.fetch(config, now) }
                .map { if (it.profileHint.isEmpty()) it.copy(profileHint = config.profile) else it }
            SourceResult(config.sourceId, items.size, null, ms(start)) to items
        } catch (e: TimeoutCancellationException) {
            SourceResult(config.sourceId, 0, "시간 초과(${sourceTimeout})", ms(start)) to emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SourceResult(config.sourceId, 0, if (full == null) e.message ?: "수집 요청 실패" else safeFailure(e), ms(start)) to emptyList()
        }
    }

    private fun safeFailure(e: Exception): String {
        val status = (e as? IOException)?.message?.let { Regex("^HTTP (\\d{3})\\b").find(it)?.groupValues?.get(1) }
        return status?.let { "수집 요청 실패 (HTTP $it)" } ?: "수집 요청 실패"
    }

    private fun ms(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000
}

package collector.pipeline

import collector.config.SourcesFile
import collector.model.DropReason
import collector.model.DroppedItem
import collector.model.Item
import collector.model.RunReport
import collector.model.SourceResult
import collector.model.StageResult
import collector.port.SeenStore
import collector.refine.Deduplicator
import collector.refine.RuleFilter
import collector.refine.Scorer
import collector.refine.SourceCap
import collector.refine.SpamClusterFilter
import collector.source.SourceAdapter
import kotlinx.coroutines.CancellationException
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

data class PipelineResult(
    val items: List<Item>,           // 남은 항목, 점수 내림차순
    val dropped: List<DroppedItem>,  // 탈락 항목과 이유
    val report: RunReport
)

/**
 * 1단계 파이프라인: ① 수집 → ② 규칙 필터 → ③ 중복 제거(+이미 본 글) → 점수 통일.
 * LLM 단계(④~⑧)는 2단계에서 뒤에 붙인다. 이미 본 글 기록(markSeen)은 발송까지 끝난 뒤 호출하는 쪽이 한다.
 */
class Pipeline(
    adapters: List<SourceAdapter>,
    private val seen: SeenStore,
    private val clock: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val sourceTimeout: Duration = 45.seconds,
    private val onStage: (String) -> Unit = {}       // 진행 상태 알림(SC-01 "수집 중…")
) {
    private val adapterById = adapters.associateBy { it.id }

    suspend fun run(file: SourcesFile): PipelineResult {
        val startedAt = clock()
        val totalStart = System.nanoTime()
        val stages = mutableListOf<StageResult>()
        val dropped = mutableListOf<DroppedItem>()
        val configs = file.sources.filter { it.enabled }

        // ① 수집 — 출처별 병렬, 하나가 실패해도 계속
        onStage("collect")
        var t = System.nanoTime()
        val fetches = coroutineScope {
            configs.map { config -> async { fetchOne(config, startedAt) } }.awaitAll()
        }
        val collected = fetches.flatMap { it.second }
        stages += StageResult("collect", 0, collected.size, ms(t))

        // ② 규칙 필터
        onStage("filter")
        t = System.nanoTime()
        val configById = configs.associateBy { it.sourceId }
        val ruled = RuleFilter(file.excludeKeywords).filter(collected, configById, startedAt)
        val despammed = SpamClusterFilter().filter(ruled.kept)
        val filtered = SourceCap.cap(despammed.kept, configById)
        dropped += ruled.dropped + despammed.dropped + filtered.dropped
        stages += StageResult("filter", collected.size, filtered.kept.size, ms(t))

        // ③ 중복 제거 + 이미 본 글 제외
        onStage("dedupe")
        t = System.nanoTime()
        val deduped = Deduplicator().dedupe(filtered.kept)
        dropped += deduped.dropped
        val unseen = seen.filterUnseen(deduped.kept)
        val unseenKeys = unseen.mapTo(HashSet()) { it.key }
        deduped.kept.filter { it.key !in unseenKeys }
            .forEach { dropped += DroppedItem(it, DropReason.ALREADY_SEEN) }
        stages += StageResult("dedupe", filtered.kept.size, unseen.size, ms(t))

        // 점수 통일(출처 안 백분위)
        t = System.nanoTime()
        val scored = Scorer.score(unseen)
            .sortedWith(compareByDescending<Item> { it.score }.thenByDescending { it.alsoSeenIn.size }.thenByDescending { it.rawScore })
        stages += StageResult("score", unseen.size, scored.size, ms(t))

        val report = RunReport(
            runDate = LocalDate.ofInstant(startedAt, zone).toString(),
            startedAt = startedAt.toString(),
            sources = fetches.map { it.first },
            stages = stages,
            dropCounts = dropped.groupingBy { it.reason }.eachCount(),
            totalMillis = ms(totalStart)
        )
        return PipelineResult(scored, dropped, report)
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
            SourceResult(config.sourceId, 0, e.message ?: e::class.simpleName, ms(start)) to emptyList()
        }
    }

    private fun ms(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000
}

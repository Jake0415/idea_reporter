package collector.model

import collector.llm.LlmCall
import kotlinx.serialization.Serializable

/** 실행 기록. 깔때기(SC-01·메일), 실행 기록(SC-10)에 쓴다. */
@Serializable
data class RunReport(
    val runDate: String,                    // yyyy-MM-dd (실행 키)
    val startedAt: String,                  // ISO-8601
    val sources: List<SourceResult>,
    val stages: List<StageResult>,
    val dropCounts: Map<DropReason, Int>,
    val totalMillis: Long,
    val llmCalls: List<LlmCall> = emptyList(),
    val cardCount: Int = 0,
    val trackingUpdateCount: Int = 0,
    val issues: List<String> = emptyList(),
    val paidStagesSkipped: Boolean = false,
    val classificationBatchWaitMillis: Long = 0
) {
    val costUsd: Double get() = llmCalls.sumOf { it.costUsd }
    val fetchedTotal: Int get() = sources.sumOf { it.fetched + it.trackingFetched }
    val failedSources: List<SourceResult> get() = sources.filter { it.error != null }
}

@Serializable
data class SourceResult(
    val source: String,
    val fetched: Int,
    val error: String? = null,   // 실패·시간 초과 이유. null이면 성공
    val millis: Long,
    val trackingFetched: Int = 0
)

@Serializable
data class StageResult(
    val stage: String,           // collect, filter, dedupe, score ...
    val input: Int,
    val output: Int,
    val millis: Long
)

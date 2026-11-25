package collector.runtime

import collector.model.DropReason
import collector.model.Signal
import collector.model.StageResult
import collector.pipeline.PipelineResult

data class CandidateEntry(
    val signal: Signal,
    val cardIds: List<String> = emptyList(),
    val dropReason: DropReason? = null
) {
    val key: String get() = signal.item.key
    val usedInCard: Boolean get() = cardIds.isNotEmpty()
}

data class CandidateView(
    val candidates: List<CandidateEntry> = emptyList(),
    val belowStandard: List<CandidateEntry> = emptyList(),
    val stages: List<StageResult> = emptyList()
)

private val selectionDrops = setOf(
    DropReason.BELOW_PROFILE_THRESHOLD, DropReason.SAME_TOPIC, DropReason.OVER_QUOTA
)

enum class RequestedCardBlock {
    INSUFFICIENT_EVIDENCE, REQUEST_LIMIT, DAILY_LIMIT, MONTHLY_LIMIT, MONTHLY_BLOCK, PRIVATE_URL, RECOVERY_PENDING, RESEARCH_UNSUPPORTED
}

data class RequestedCardEstimate(
    val added: List<Signal>, val sufficient: Boolean, val estimatedUsd: Double?, val allowed: Boolean,
    val maxUsd: Double, val dailyRemainingUsd: Double,
    val requestLimitUsd: Double, val dailyLimitUsd: Double, val block: RequestedCardBlock?, val researchMaxUsd: Double? = null,
    val researchSupported: Boolean = true, val researchBudgetBlocked: Boolean = false
)

data class RequestedCardResult(
    val cardIds: List<String> = emptyList(), val failure: String? = null, val retryAllowed: Boolean = false,
    val mailFailed: Boolean = false, val requote: RequestedCardEstimate? = null
)

internal data class RequestedEvidence(val selected: List<Signal>, val added: List<Signal>) {
    val signals get() = selected + added
    val sufficient get() = signals.any { it.classification.profile == "tech-signal" } &&
        signals.any { it.classification.profile in setOf("demand-signal", "market-signal") }
}

internal fun requestedEvidence(selected: List<Signal>, available: List<Signal>): RequestedEvidence {
    val keys = selected.map { it.item.key }.toSet()
    val topics = selected.map { it.classification.topicKey }.filter(String::isNotBlank).toSet()
    val tags = selected.flatMap { it.classification.tags }.filter(String::isNotBlank).toSet()
    val related = available.asSequence().filter {
        val row = it.classification
        it.item.key !in keys && row.profile in setOf("tech-signal", "demand-signal", "market-signal") &&
            (row.topicKey in topics || row.tags.any { tag -> tag in tags })
    }.distinctBy { it.item.key }.sortedWith(
        compareByDescending<Signal> { it.classification.profile !in selected.map { seed -> seed.classification.profile } }
            .thenByDescending { it.classification.topicKey in topics }
            .thenByDescending { it.classification.score }
    ).take(4).toList()
    return RequestedEvidence(selected, related)
}

fun candidateView(run: PipelineResult): CandidateView {
    if (run.report.stages.none { it.stage == "select" }) return CandidateView(stages = run.report.stages)
    val drops = run.dropped.filter { it.reason in selectionDrops }.associate { it.item.key to it.reason }
    val cardIds = run.cards.flatMap { card ->
        (card.techSignals + card.demandSignals + card.competitors)
            .map { it.itemId }.distinct().map { it to card.id }
    }.groupBy({ it.first }, { it.second })
    val ranked = run.classified.distinctBy { it.item.key }.sortedByDescending { it.classification.score }
    return CandidateView(
        candidates = ranked.filter {
            it.classification.profile != "none" && it.item.key !in run.trackingOnlyKeys && it.item.key !in drops
        }.map { CandidateEntry(it, cardIds[it.item.key].orEmpty()) },
        belowStandard = ranked.mapNotNull {
            val reason = drops[it.item.key]
            if (reason in selectionDrops) CandidateEntry(it, dropReason = reason) else null
        },
        stages = run.report.stages
    )
}

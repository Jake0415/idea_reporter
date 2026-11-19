package collector.stage

import collector.config.PipelineConfig
import collector.model.*

data class SelectionResult(
    val kept: List<Signal>, val dropped: List<DroppedItem>, val groups: Map<String, List<Signal>>,
    val thresholdPassedByProfile: Map<String, Int> = emptyMap()
)

class Selector(private val config: PipelineConfig) {
    fun select(signals: List<Signal>): SelectionResult {
        val dropped = mutableListOf<DroppedItem>()
        val ranking = compareByDescending<Signal> { it.classification.score }
            .thenByDescending { it.item.score }.thenByDescending { it.item.alsoSeenIn.size }
            .thenByDescending { it.item.rawScore }
        val ranked = signals.filter { signal ->
            val threshold = config.profiles.getValue(signal.classification.profile).threshold
            ((signal.classification.score ?: 0.0) >= threshold).also {
                if (!it) dropped += DroppedItem(signal.item, DropReason.BELOW_PROFILE_THRESHOLD)
            }
        }.sortedWith(ranking)
        val topics = ranked.groupBy { it.classification.topicKey }
        val representatives = topics.mapValues { (_, group) -> group.first() }
        val byProfile = mutableMapOf<String, Int>()
        val byTag = mutableMapOf<String, Int>()
        val kept = representatives.values.filterTo(mutableSetOf()) { signal ->
            val profile = signal.classification.profile
            val tag = signal.classification.tags.firstOrNull()
            val profileCount = byProfile.getOrDefault(profile, 0)
            val tagCount = byTag.getOrDefault(tag, 0)
            if (profileCount >= config.profiles.getValue(profile).maxCandidates ||
                tag != null && tagCount >= config.maxCandidatesPerTag) {
                false
            } else {
                byProfile[profile] = profileCount + 1
                if (tag != null) byTag[tag] = tagCount + 1
                true
            }
        }
        val missingDemand = config.minDemandCandidates - byProfile.getOrDefault("demand-signal", 0)
        val promoted = mutableMapOf<String, Signal>()
        if (missingDemand > 0) {
            val extraDemand = representatives.values.asSequence()
                .filter { it.classification.profile == "demand-signal" && it !in kept }
                .take(missingDemand).toList()
            kept += extraDemand
            ranked.asSequence().filter { signal ->
                signal.classification.profile == "demand-signal" &&
                    representatives.getValue(signal.classification.topicKey).classification.profile != "demand-signal"
            }.distinctBy { it.classification.topicKey }.take(missingDemand - extraDemand.size)
                .associateByTo(promoted) { it.classification.topicKey }
        }
        val groups = mutableMapOf<String, List<Signal>>()
        val overQuota = mutableListOf<DroppedItem>()
        val rankedKept = representatives.mapNotNull { (topic, representative) ->
            val promotion = promoted[topic]
            val first = promotion ?: representative
            val others = topics.getValue(topic) - first
            val duplicates = others.map { it.item }
            groups[first.item.key] = others
            dropped += duplicates.map { DroppedItem(it, DropReason.SAME_TOPIC, first.item.key) }
            val merged = first.copy(item = first.item.copy(
                mergedUrls = (first.item.mergedUrls + duplicates.flatMap { listOf(it.url) + it.mergedUrls })
                    .distinct().filter { it != first.item.url }
            ))
            if (promotion != null || representative in kept) merged else {
                overQuota += DroppedItem(merged.item, DropReason.OVER_QUOTA)
                null
            }
        }
        dropped += overQuota
        return SelectionResult(rankedKept, dropped, groups, ranked.groupingBy { it.classification.profile }.eachCount())
    }
}

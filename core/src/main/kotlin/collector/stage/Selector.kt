package collector.stage

import collector.config.PipelineConfig
import collector.model.*

data class SelectionResult(val kept: List<Signal>, val dropped: List<DroppedItem>, val groups: Map<String, List<Signal>>)

class Selector(private val config: PipelineConfig) {
    fun select(signals: List<Signal>): SelectionResult {
        val dropped = mutableListOf<DroppedItem>()
        val ranked = signals.filter { signal ->
            val threshold = config.profiles.getValue(signal.classification.profile).threshold
            ((signal.classification.score ?: 0.0) >= threshold).also {
                if (!it) dropped += DroppedItem(signal.item, DropReason.BELOW_PROFILE_THRESHOLD)
            }
        }.sortedWith(compareByDescending<Signal> { it.classification.score }
            .thenByDescending { it.item.score }.thenByDescending { it.item.alsoSeenIn.size }
            .thenByDescending { it.item.rawScore })
        val groups = mutableMapOf<String, List<Signal>>()
        val merged = ranked.groupBy { it.classification.topicKey }.values.map { group ->
            val (first) = group
            val duplicates = group.drop(1).map { it.item }
            groups[first.item.key] = group.drop(1)
            dropped += duplicates.map { DroppedItem(it, DropReason.SAME_TOPIC, first.item.key) }
            first.copy(item = first.item.copy(
                mergedUrls = (first.item.mergedUrls + duplicates.flatMap { listOf(it.url) + it.mergedUrls })
                    .distinct().filter { it != first.item.url }
            ))
        }
        val byProfile = mutableMapOf<String, Int>()
        val byTag = mutableMapOf<String, Int>()
        val kept = merged.filter { signal ->
            val profile = signal.classification.profile
            val tag = signal.classification.tags.firstOrNull()
            val profileCount = byProfile.getOrDefault(profile, 0)
            val tagCount = byTag.getOrDefault(tag, 0)
            if (profileCount >= config.profiles.getValue(profile).maxCandidates ||
                tag != null && tagCount >= config.maxCandidatesPerTag) {
                dropped += DroppedItem(signal.item, DropReason.OVER_QUOTA)
                false
            } else {
                byProfile[profile] = profileCount + 1
                if (tag != null) byTag[tag] = tagCount + 1
                true
            }
        }
        return SelectionResult(kept, dropped, groups)
    }
}

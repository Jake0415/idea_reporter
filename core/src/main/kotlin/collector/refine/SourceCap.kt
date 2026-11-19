package collector.refine

import collector.config.SourceConfig
import collector.config.HfConfig
import collector.config.RssConfig
import collector.model.DropReason
import collector.model.DroppedItem
import collector.model.Item

/**
 * ② 출처 상한: 거르기가 끝난 뒤 출처마다 원점수 상위 maxItems건만 남긴다.
 * 어댑터는 넉넉히(fetchPerQuery) 받아 오고, 스팸을 뺀 다음 여기서 자른다.
 */
object SourceCap {
    fun cap(items: List<Item>, configs: Map<String, SourceConfig>): FilterResult {
        val kept = mutableListOf<Item>()
        val dropped = mutableListOf<DroppedItem>()
        for ((source, group) in items.groupBy { it.source }) {
            fun take(sorted: List<Item>, limit: Int, label: String): List<Item> {
                sorted.drop(limit).forEach { dropped += DroppedItem(it, DropReason.OVER_QUOTA, "$label 최대 ${limit}건 초과") }
                return sorted.take(limit)
            }
            val config = configs[source]
            if (config is RssConfig) {
                val limits = config.feeds.associate { it.name to it.maxItems.coerceAtLeast(0) }
                for ((feed, entries) in group.groupBy { it.extra["feed"] }) {
                    kept += take(entries.sortedByDescending { it.publishedAt }, limits[feed] ?: config.maxItems, "피드")
                }
            } else {
                val candidates = if (config is HfConfig) {
                    group.groupBy { it.externalId.substringBefore(':') }.values.flatMap { repos ->
                        take(repos.sortedByDescending { it.rawScore }, config.maxItemsPerKind.coerceAtLeast(0), "종류")
                    }
                } else group
                kept += take(candidates.sortedByDescending { it.rawScore }, config?.maxItems ?: Int.MAX_VALUE, "출처")
            }
        }
        return FilterResult(kept, dropped)
    }
}

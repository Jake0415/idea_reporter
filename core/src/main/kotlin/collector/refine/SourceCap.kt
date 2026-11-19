package collector.refine

import collector.config.SourceConfig
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
            val limit = configs[source]?.maxItems ?: Int.MAX_VALUE
            val sorted = group.sortedByDescending { it.rawScore }
            kept += sorted.take(limit)
            sorted.drop(limit).forEach { dropped += DroppedItem(it, DropReason.OVER_QUOTA, "출처 최대 ${limit}건 초과") }
        }
        return FilterResult(kept, dropped)
    }
}

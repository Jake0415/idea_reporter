package collector.refine

import collector.model.DropReason
import collector.model.DroppedItem
import collector.model.Item

/**
 * ② 스팸 묶음 거르기(LLM 없음). 실제 실행에서 GitHub 검색 결과의 절반 이상이
 * 서로 다른 계정이 같은 이름·같은 설명으로 찍어 낸 저장소(스타 조작 의심)였다.
 * 같은 출처에서 [minOwners]개 이상의 서로 다른 작성자가
 *  - 같은 이름(소유자 제외, 영숫자만 비교)이거나
 *  - 같은 설명(snippet)
 * 을 가진 묶음은 대표 없이 전부 SUSPECTED_SPAM으로 뺀다.
 */
class SpamClusterFilter(private val minOwners: Int = 3) {

    fun filter(items: List<Item>): FilterResult {
        val spamKeys = HashSet<String>()
        for ((_, group) in items.groupBy { it.source }) {
            markClusters(group, spamKeys) { nameKey(it) }
            markClusters(group, spamKeys) { it.snippet.trim().lowercase().takeIf { s -> s.length >= 20 } }
        }
        val kept = items.filter { it.key !in spamKeys }
        val dropped = items.filter { it.key in spamKeys }.map { item ->
            DroppedItem(item, DropReason.SUSPECTED_SPAM, "같은 이름·설명의 저장소가 여러 계정에 있음")
        }
        return FilterResult(kept, dropped)
    }

    private fun markClusters(group: List<Item>, out: MutableSet<String>, keyOf: (Item) -> String?) {
        group.groupBy(keyOf)
            .filterKeys { !it.isNullOrEmpty() }
            .values
            .filter { cluster -> cluster.mapNotNull { it.author?.lowercase() }.toSet().size >= minOwners }
            .forEach { cluster -> cluster.forEach { out += it.key } }
    }

    /** "owner/Repo-Name" → "reponame". 소유자 구분이 없는 출처는 제목 전체. */
    private fun nameKey(item: Item): String =
        item.title.substringAfter('/').lowercase().filter { it.isLetterOrDigit() }
}

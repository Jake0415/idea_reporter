package collector.refine

import collector.model.DropReason
import collector.model.DroppedItem
import collector.model.Item

/**
 * ③ 중복 제거(LLM 없음). 같은 정규화 URL이거나 제목이 매우 비슷하면 하나로 합친다.
 * - 대표: 먼저 들어온 항목 중 같은 출처끼리는 원점수가 높은 쪽
 * - 다른 출처에서 온 중복은 대표의 alsoSeenIn에 출처를 남긴다("여러 곳에서 화제" 근거)
 * 주제(같은 사건) 단위 합치기는 2단계 Classifier의 topicKey로 한다.
 */
class Deduplicator(private val titleThreshold: Double = 0.8) {

    fun dedupe(items: List<Item>): FilterResult {
        val groups = mutableListOf<Group>()
        val dropped = mutableListOf<DroppedItem>()

        for (item in items) {
            val normUrl = UrlNormalizer.normalize(item.url)
            val tokens = titleTokens(item.title)
            val match = groups.firstOrNull { g ->
                g.normUrl == normUrl || (tokens.size >= 3 && jaccard(g.tokens, tokens) >= titleThreshold)
            }
            if (match == null) {
                groups += Group(item, normUrl, tokens)
                continue
            }
            val rep = match.rep
            if (item.source == rep.source && item.rawScore > rep.rawScore) {
                // 같은 출처에서 더 높은 점수가 나오면 대표 교체
                dropped += DroppedItem(rep, DropReason.DUPLICATE, "중복: ${item.key}")
                match.rep = item.copy(alsoSeenIn = rep.alsoSeenIn)
                match.normUrl = normUrl
                match.tokens = tokens
            } else {
                dropped += DroppedItem(item, DropReason.DUPLICATE, "중복: ${rep.key}")
                if (item.source != rep.source && item.source !in rep.alsoSeenIn) {
                    match.rep = rep.copy(alsoSeenIn = rep.alsoSeenIn + item.source)
                }
            }
        }
        return FilterResult(groups.map { it.rep }, dropped)
    }

    private class Group(var rep: Item, var normUrl: String, var tokens: Set<String>)

    companion object {
        private val stopWords = setOf("the", "a", "an", "of", "for", "to", "and", "in", "on", "with", "is", "show", "hn", "ask")

        fun titleTokens(title: String): Set<String> =
            title.lowercase()
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length >= 2 && it !in stopWords }
                .toSet()

        fun jaccard(a: Set<String>, b: Set<String>): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val inter = a.count { it in b }
            return inter.toDouble() / (a.size + b.size - inter)
        }
    }
}

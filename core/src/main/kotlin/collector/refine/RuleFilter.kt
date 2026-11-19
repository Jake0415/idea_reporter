package collector.refine

import collector.config.HnConfig
import collector.config.SourceConfig
import collector.model.DropReason
import collector.model.DroppedItem
import collector.model.Item
import java.time.Instant

data class FilterResult(val kept: List<Item>, val dropped: List<DroppedItem>)

/** ② 규칙 필터(LLM 없음): 수집 기간, 출처별 최소 점수, 제외 키워드 */
class RuleFilter(excludeKeywords: List<String>) {
    private val keywordPatterns = excludeKeywords
        .filter { it.isNotBlank() }
        .map { it to Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(it.trim()) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE) }

    fun filter(items: List<Item>, configs: Map<String, SourceConfig>, now: Instant): FilterResult {
        val kept = mutableListOf<Item>()
        val dropped = mutableListOf<DroppedItem>()
        for (item in items) {
            val config = configs[item.source]
            val override = (config as? HnConfig)?.overrideFor(item.extra["matchedQuery"])
            val window = override?.window() ?: config?.window()
            val minRawScore = override?.minRawScore ?: config?.minRawScore
            val reason = when {
                window != null && item.publishedAt.isBefore(now.minus(window)) ->
                    DropReason.OUT_OF_WINDOW to "게시 ${item.publishedAt}"
                minRawScore != null && item.rawScore < minRawScore ->
                    DropReason.LOW_SOURCE_SCORE to "점수 ${item.rawScore} < $minRawScore"
                else -> keywordPatterns.firstOrNull { (_, re) -> re.containsMatchIn(item.title) || re.containsMatchIn(item.snippet) }
                    ?.let { (word, _) -> DropReason.EXCLUDED_KEYWORD to "키워드: $word" }
            }
            if (reason == null) kept += item else dropped += DroppedItem(item, reason.first, reason.second)
        }
        return FilterResult(kept, dropped)
    }
}

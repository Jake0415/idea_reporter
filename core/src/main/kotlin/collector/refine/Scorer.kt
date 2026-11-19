package collector.refine

import collector.model.Item
import kotlin.math.roundToInt

/**
 * 출처별 지표(HN 점수, GitHub 스타 …)를 출처 안의 백분위로 0~100에 맞춘다.
 * 같은 값은 같은 점수. 출처에 항목이 하나뿐이면 100.
 */
object Scorer {
    fun score(items: List<Item>): List<Item> {
        val bySource = items.groupBy { it.source }
        val scoreOf = HashMap<String, Int>()
        for ((_, group) in bySource) {
            val n = group.size
            for (item in group) {
                val score = if (n == 1) 100 else {
                    val lower = group.count { it.rawScore < item.rawScore }
                    val equal = group.count { it.rawScore == item.rawScore } - 1
                    ((lower + equal / 2.0) / (n - 1) * 100).roundToInt().coerceIn(0, 100)
                }
                scoreOf[item.key] = score
            }
        }
        return items.map { it.copy(score = scoreOf.getValue(it.key)) }
    }
}

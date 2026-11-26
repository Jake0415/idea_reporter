package collector.stage

import collector.model.IdeaCard
import collector.pipeline.PipelineResult
import kotlinx.serialization.Serializable

internal const val RECENT_CARD_WARNING = "최근 카드와 비슷함: "

@Serializable
data class RecentCard(val card: IdeaCard, val techTopics: Set<String>) {
    fun promptLine() = listOf(card.title, card.targetCustomer, card.coreTech, card.pain).joinToString(" · ")
}

internal fun PipelineResult.recentCards(createdOn: String? = null): List<RecentCard> {
    val byUrl = classified.associateBy { it.item.url }
    val byId = classified.associateBy { it.item.key }
    return cards.map { card ->
        val topics = card.techSignals.mapNotNull { ref ->
            (byUrl[ref.url] ?: byId[ref.itemId])?.classification
                ?.takeIf { it.profile == "tech-signal" }?.topicKey?.takeIf(String::isNotBlank)
        }.toSet()
        RecentCard(card.copy(runDate = createdOn ?: card.runDate.ifBlank { report.runDate }), topics)
    }
}

private val word = Regex("[a-z0-9가-힣]+")
private val stopWords = setOf("및", "또는", "위한", "있는", "the", "a", "an", "and", "for", "of", "to",
    "ai", "도구", "서비스", "앱", "플랫폼", "자동")

private fun tokens(text: String) = word.findAll(text.lowercase()).map { it.value }.filterNot { it in stopWords }.toSet()

internal fun cardKey(text: String) = word.findAll(text.lowercase()).joinToString("") { it.value }

private fun similarity(a: String, b: String): Double {
    val left = tokens(a)
    val right = tokens(b)
    val union = (left + right).size
    return if (union == 0) 0.0 else left.intersect(right).size.toDouble() / union
}

internal fun isRepeatedCard(card: IdeaCard, techTopics: Set<String>, recent: RecentCard): Boolean {
    val prior = recent.card
    val titleOverlap = similarity(card.title, prior.title)
    if (titleOverlap >= 0.6) return true
    if (techTopics.none { it.isNotBlank() && it in recent.techTopics }) return false
    if (listOf(prior.targetCustomer, prior.coreTech, prior.pain).any(String::isBlank)) return titleOverlap >= 0.3
    val customer = cardKey(card.targetCustomer)
    val painOverlap = similarity(card.pain, prior.pain)
    return painOverlap >= 0.5 || (customer.isNotEmpty() && customer == cardKey(prior.targetCustomer) &&
        (painOverlap >= 0.3 || similarity(card.coreTech, prior.coreTech) >= 0.3))
}

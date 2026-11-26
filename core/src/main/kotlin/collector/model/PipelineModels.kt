package collector.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class ClassifiedItem(
    @SerialName("itemId") val externalId: String, val profile: String, val score: Double?,
    val reason: String, val summary: String, val tags: List<String>, val topicKey: String,
    val topicLabel: String, val trackedIdeaIds: List<String> = emptyList()
)

@Serializable
data class HnContext(val storyText: String = "", val topComments: List<String> = emptyList())

@Serializable
data class Signal(
    val item: Item, val classification: ClassifiedItem, val body: String = "",
    val hnContext: HnContext? = null, val enrichedComments: List<Comment> = emptyList()
)

@Serializable
data class IdeaCard(
    val id: String = "", val runDate: String = "", val title: String, val oneLiner: String, val whyToday: String,
    val problem: String, val techSignals: List<SignalRef>, val demandSignals: List<SignalRef>,
    val competitors: List<SignalRef>, val history: List<String>, val effort: String, val differentiation: String,
    val biggestRisk: String, val validationPlan: String, val caveats: List<String>, val category: String,
    val badges: List<String> = emptyList(), val tags: List<String>,
    val supplementRefs: List<SupplementRef> = emptyList(),
    val targetCustomer: String = "", val coreTech: String = "", val pain: String = "",
    val kind: String = "daily", val lens: String? = null, val seedId: String? = null,
    val speculation: List<String> = emptyList()
)

@Serializable data class SupplementRef(val url: String, val title: String, val note: String)

@Serializable data class SignalRef(val itemId: String, val url: String, val note: String, val license: String? = null)
@Serializable data class TopicHistory(val topicKey: String, val topicLabel: String, val runDate: String, val itemKeys: List<String>)
@Serializable data class MetricSnapshot(
    val itemKey: String, val rawScore: Int, @Serializable(with = InstantSerializer::class) val at: Instant
)

@Serializable
data class TrackedIdea(
    val id: String, val cardId: String, val title: String, val category: String,
    val keywords: List<String>, val competitors: List<String>, val status: Status,
    @Serializable(with = InstantSerializer::class) val startedAt: Instant,
    @Serializable(with = InstantSerializer::class) val lastUpdateAt: Instant?,
    val strength: Double, val trend: Trend
) {
    enum class Status { TRACKING, QUIET, STOPPED }
    enum class Trend { UP, FLAT, DOWN }
}

@Serializable
data class TrackingUpdate(
    val id: String, val trackedId: String, val runDate: String, val summary: String,
    val changes: List<Change>, val itemKeys: List<String>, val strength: Double, val trend: TrackedIdea.Trend
) {
    @Serializable data class Change(val kind: String, val note: String, val itemKey: String?)
}

@Serializable
data class Feedback(val cardId: String, val kind: Kind, val reason: String? = null, val memo: String? = null,
    @Serializable(with = InstantSerializer::class) val at: Instant? = null) {
    enum class Kind { STAR, NOT_INTERESTED }
}

package collector.pipeline

import collector.model.ClassifiedItem
import collector.model.Feedback
import collector.model.MetricSnapshot
import java.time.Instant
import collector.stage.HistorySearch
import java.time.LocalDate
import java.time.ZoneId
import collector.stage.RecentCard
import collector.stage.recentCards

interface RunHistory : HistorySearch {
    val requestedRunZone: ZoneId get() = ZoneId.systemDefault()
    fun runs(today: LocalDate, days: Int): List<PipelineResult>
    fun requestedRuns(today: LocalDate, days: Int): List<PipelineResult> = emptyList()
    fun stateRuns(today: LocalDate, days: Int): List<PipelineResult> = runs(today, days)
    fun trackClassified(): Map<String, TrackClassification> = emptyMap()
    fun feedback(): List<Feedback>
    fun cardRun(cardId: String): PipelineResult?
    fun metrics(now: Instant): List<MetricSnapshot>
}

@kotlinx.serialization.Serializable
data class TrackClassification(
    val date: String,
    val classification: ClassifiedItem,
    // Older files recorded positive matches only.
    val relevance: Map<String, Boolean> = classification.trackedIdeaIds.associateWith { true }
)

internal fun PipelineResult.requestedCreationDay(zone: ZoneId): LocalDate =
    Instant.parse(report.startedAt).atZone(zone).toLocalDate()

internal fun RunHistory.requestedRecentCards(today: LocalDate, days: Int): List<RecentCard> =
    requestedRuns(today, days).flatMap { it.recentCards(it.requestedCreationDay(requestedRunZone).toString()) }

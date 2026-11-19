package collector.pipeline

import collector.model.ClassifiedItem
import collector.model.Feedback
import collector.model.MetricSnapshot
import java.time.Instant
import collector.stage.HistorySearch
import java.time.LocalDate

interface RunHistory : HistorySearch {
    fun runs(today: LocalDate, days: Int): List<PipelineResult>
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

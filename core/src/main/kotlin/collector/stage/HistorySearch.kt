package collector.stage

import collector.model.Signal
import collector.refine.Deduplicator
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable data class HistoryHit(val date: String, val title: String, val summary: String, val id: String)

interface HistorySearch {
    fun search(signal: Signal, today: LocalDate): List<HistoryHit>
}

internal fun words(text: String) = Deduplicator.titleTokens(text).toList()

package app.ideacollector

import collector.model.ClassifiedItem
import collector.model.Item
import collector.model.Signal
import collector.runtime.CandidateEntry
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class CandidatesScreenTest {
    @Test fun selectionTogglesAndStopsAtThree() {
        val selected = listOf("a", "b", "c", "d").fold(emptyList<String>(), ::toggleCandidate)
        assertEquals(listOf("a", "b", "c"), selected)
        assertEquals(listOf("a", "c"), toggleCandidate(selected, "b"))
        assertTrue(toggleCandidate(listOf("a"), "a").isEmpty())
    }

    @Test fun filtersByProfileAndCitation() {
        val item = Item("hf", "1", "", "https://example.invalid", 1, publishedAt = Instant.EPOCH)
        val entry = CandidateEntry(Signal(item, ClassifiedItem(item.key, "tech-signal", 8.0,
            "", "", emptyList(), "", "")))
        assertTrue(CandidateFilter.ALL.matches(entry))
        assertTrue(CandidateFilter.TECH.matches(entry))
        assertFalse(CandidateFilter.DEMAND.matches(entry))
        assertTrue(CandidateFilter.UNUSED.matches(entry))
        assertFalse(CandidateFilter.UNUSED.matches(entry.copy(cardIds = listOf("card"))))
    }
}

package app.ideacollector

import app.ideacollector.github.inboxPath
import app.ideacollector.work.nextSyncDelay
import collector.data.DataBranch
import collector.data.InboxRequest
import collector.model.Feedback
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.ZonedDateTime

class FoundationTest {
    @Test fun inboxFileUsesTheRequestIdentity() {
        val request = InboxRequest.Reaction(DataBranch.VERSION, "00000000-0000-0000-0000-000000000001",
            "2026-11-23T07:40:00Z", "card", Feedback.Kind.STAR, "test")
        assertEquals("inbox/2026-11-23T074000Z-00000000-0000-0000-0000-000000000001.json", inboxPath(request))
    }

    @Test fun firstPeriodicSyncUsesLocalTime() {
        assertEquals(Duration.ofMinutes(10), nextSyncDelay(ZonedDateTime.parse("2026-11-23T07:30+09:00[Asia/Seoul]")))
        assertEquals(Duration.ofHours(23), nextSyncDelay(ZonedDateTime.parse("2026-11-23T08:40+09:00[Asia/Seoul]")))
    }
}

package app.ideacollector

import app.ideacollector.data.RequestRow
import app.ideacollector.data.Artifact
import app.ideacollector.github.GitHubClient
import app.ideacollector.github.contractJson
import collector.model.IdeaCard
import collector.model.TrackedIdea
import collector.data.InboxRequest
import collector.model.RunReport
import collector.pipeline.PipelineResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ScreenStateTest {
    @Test fun activeFeedbackIsOptimistic() {
        assertTrue(visibleReaction(listOf(row("PENDING")), "card", "STAR"))
    }

    @Test fun rejectedFeedbackRollsBack() {
        assertFalse(visibleReaction(listOf(row("REJECTED")), "card", "STAR"))
    }

    @Test fun statusChipsDistinguishDeliveryAndRejection() {
        assertEquals(R.string.sent, requestLabel("SENT"))
        assertEquals(R.string.rejected, requestLabel("REJECTED"))
    }

    @Test fun searchQuotesUserInput() {
        assertEquals("\"회의\"* AND \"노트\"*", ftsQuery("회의 \"노트\""))
        assertNull(ftsQuery(" -- "))
    }

    @Test fun promptDiffPreservesOrderAndRepeatedLines() {
        assertEquals(listOf("duplicate") to emptyList<String>(),
            lineChanges("head\nduplicate\nduplicate\nend", "head\nduplicate\nend"))
        val (removed, added) = lineChanges("one\ncenter\ntwo", "two\ncenter\none")
        assertFalse(removed.isEmpty())
        assertFalse(added.isEmpty())
        assertEquals(removed.sorted(), added.sorted())
    }

    @Test fun settingsPayloadOmitsUnchangedSections() {
        val scheduleOnly = InboxRequest.SettingsSet(1, "settings", "", schedule = collector.data.ScheduleSettings(false))
        val budgetOnly = scheduleOnly.copy(budget = JsonObject(mapOf("dailyCardUsd" to JsonPrimitive(0.75))), schedule = null)
        fun sections(request: InboxRequest) =
            contractJson.parseToJsonElement(contractJson.encodeToString(request)).jsonObject.keys.intersect(setOf("budget", "schedule"))
        assertEquals(setOf("schedule"), sections(scheduleOnly))
        assertEquals(setOf("budget"), sections(budgetOnly))
    }

    @Test fun rejectedTrackCommandsRestoreServerTrackingAndKeywords() {
        val at = java.time.Instant.parse("2026-11-23T07:40:00Z")
        val track = TrackedIdea("track", "card", "Card", "Category", listOf("server"), emptyList(),
            TrackedIdea.Status.TRACKING, at, null, 0.0, TrackedIdea.Trend.FLAT)
        val tracking = Artifact("sample", "out/tracked.json", "tracking", "", contractJson.encodeToString(listOf(track)))
        val keywords: InboxRequest = InboxRequest.TrackKeywords(1, "keywords", at.toString(), track.id, listOf("draft"))
        val stop: InboxRequest = InboxRequest.TrackStop(1, "stop", at.toString(), track.id)
        fun state(status: String) = ScreenState(listOf(tracking), listOf(keywords, stop).map {
            row(status).copy(requestId = it.requestId, json = contractJson.encodeToString(it), reaction = null)
        })
        assertFalse(state("PENDING").isTracking(track))
        assertEquals(listOf("draft"), state("PENDING").keywords(track))
        assertTrue(state("REJECTED").isTracking(track))
        assertEquals(listOf("server"), state("REJECTED").keywords(track))
    }

    @Test fun archiveCombinesCategorySearchAndHiddenFilter() {
        val card = IdeaCard(id = "card", title = "회의록", oneLiner = "", whyToday = "", problem = "",
            techSignals = emptyList(), demandSignals = emptyList(), competitors = emptyList(),
            history = emptyList(), effort = "", differentiation = "", biggestRisk = "", validationPlan = "",
            caveats = emptyList(), category = "업무", tags = emptyList())
        val daily = PipelineResult(emptyList(), emptyList(),
            RunReport("2026-11-23", "", emptyList(), emptyList(), emptyMap(), 0),
            cards = listOf(card, card.copy(id = "other", category = "교육")))
        val artifact = Artifact("sample", "out/2026-11-23.json", "daily", "", contractJson.encodeToString(daily))
        val hidden = row("PENDING").copy(reaction = "NOT_INTERESTED")
        val data = ScreenState(listOf(artifact), listOf(hidden))
        assertEquals(listOf(card), data.filtered(false, false, "업무", setOf("card")))
        assertEquals(listOf(card), data.filtered(false, true, "업무", setOf("card")))
        val applied = ScreenState(listOf(artifact), listOf(hidden.copy(status = "APPLIED")))
        assertTrue(applied.filtered(false, true, "업무", setOf("card")).isEmpty())
        assertTrue(data.filtered(false, false, "업무", setOf("other")).isEmpty())

        val start: InboxRequest = InboxRequest.TrackStart(1, "start", "", card.id)
        val request = row("PENDING").copy(requestId = "start", json = contractJson.encodeToString(start), reaction = null)
        val star = row("APPLIED").copy(createdAt = "2026-11-23T07:40:00Z")
        assertTrue(ScreenState(listOf(artifact), listOf(star, request)).starred(card))
        assertFalse(ScreenState(listOf(artifact), listOf(star, request.copy(status = "REJECTED"))).starred(card))
    }

    @Test fun dailyActionsUseDailyWorkflowOnMockServer() {
        MockWebServer().use { server ->
            val github = GitHubClient(base = server.url("/"))
            server.enqueue(MockResponse().setBody("""{"workflow_runs":[{"status":"in_progress"}]}"""))
            assertTrue(github.dailyActive("sample/test", "test-token", null))
            assertTrue(server.takeRequest().path.orEmpty().contains("/daily.yml/runs"))
            server.enqueue(MockResponse().setBody("""{"default_branch":"trunk"}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            github.dispatchDaily("sample/test", "test-token")
            assertEquals("/repos/sample/test", server.takeRequest().path)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/repos/sample/test/actions/workflows/daily.yml/dispatches", request.path)
            assertEquals("""{"ref":"trunk"}""", request.body.readUtf8())
        }
    }

    private fun row(status: String) =
        RequestRow("sample", "id", "path", "{}", "", status, cardId = "card", reaction = "STAR")
}

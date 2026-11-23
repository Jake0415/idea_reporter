package app.ideacollector

import android.content.Context
import androidx.room.Room
import app.ideacollector.data.AppDatabase
import app.ideacollector.data.RepositorySync
import app.ideacollector.github.GitHubClient
import app.ideacollector.github.contractJson
import app.ideacollector.github.inboxPath
import app.ideacollector.github.resultPath
import app.ideacollector.github.sha256
import collector.data.DataBranch
import collector.data.DataFile
import collector.data.DataManifest
import collector.data.InboxRequest
import collector.data.InboxResult
import collector.model.Feedback
import collector.model.IdeaCard
import collector.model.RunReport
import collector.model.SignalRef
import collector.pipeline.PipelineResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RepositorySyncTest {
    private val repo = "sample/collector"
    private val commit = "a".repeat(40)
    private val day = "2026-11-23"
    private val time = "${day}T07:40:00Z"
    private val path = "out/$day.json"
    private val first = InboxRequest.Reaction(DataBranch.VERSION, "00000000-0000-0000-0000-000000000001",
        time, "card-1", Feedback.Kind.STAR, "test")
    private val second = first.copy(requestId = "00000000-0000-0000-0000-000000000002",
        kind = Feedback.Kind.NOT_INTERESTED, createdAt = "${day}T07:41:00Z")
    private var now = java.time.Instant.parse(time)
    private val clock = object : java.time.Clock() {
        override fun instant() = now
        override fun getZone() = java.time.ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = java.time.Clock.fixed(now, zone)
    }
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var sync: RepositorySync

    @Before fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        server = MockWebServer()
        sync = RepositorySync(db, GitHubClient(base = server.url("/")), clock)
    }

    @After fun close() {
        db.close()
        server.shutdown()
    }

    @Test fun conditionalManifestDownloadsOnlyChangedFilesAndIndexesCoreDailyJson() = runBlocking {
        val daily = dailyFixture()
        val tracked = "[]"
        manifest(mapOf(path to daily, "out/tracked.json" to tracked), "\"v1\"")
        respond(daily)
        respond(tracked)
        assertEquals(1, sync.sync(repo, null).cards)
        assertEquals("card-1", db.dao().search(repo, "\"Automation\"").single().id)
        assertEquals("card-1", db.dao().search(repo, "\"workflow\"").single().id)
        assertEquals("card-1", db.dao().search(repo, "\"evidence\"").single().id)
        val initial = List(4) { server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!! }
        assertTrue(initial.first().path!!.endsWith("/git/ref/heads/data"))
        assertTrue(initial.drop(1).all { it.requestUrl!!.queryParameter("ref") == commit })
        assertTrue(initial.all { it.getHeader("Authorization") == null })

        val changed = dailyFixture("Updated automation")
        manifest(mapOf(path to changed, "out/tracked.json" to tracked), "\"v2\"")
        respond(changed)
        sync.sync(repo, "test-token")
        server.takeRequest()
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))
        val file = server.takeRequest()
        assertTrue(file.path!!.endsWith("/contents/$path?ref=$commit"))
        assertEquals("Bearer test-token", file.getHeader("Authorization"))
        head()
        server.enqueue(MockResponse().setResponseCode(304))
        assertNull(sync.sync(repo, null).cards)
        server.takeRequest()
        assertEquals("\"v2\"", server.takeRequest().getHeader("If-None-Match"))
        assertEquals(9, server.requestCount)
    }

    @Test fun downloadFailuresKeepProgressAndStopAfterThreeAttempts() = runBlocking {
        val daily = dailyFixture()
        val tracked = "out/tracked.json"
        val feedback = "out/feedback.json"
        val files = linkedMapOf(tracked to "[]", feedback to "[]", path to daily)
        repeat(3) { attempt ->
            manifest(files)
            server.enqueue(MockResponse().setResponseCode(404))
            respond("{}")
            if (attempt == 0) respond(daily)
            sync.sync(repo, null)
            val state = checkNotNull(db.dao().state(repo))
            assertEquals(daily, db.dao().artifacts(repo).single().json)
            assertEquals(if (attempt < 2) "PARTIAL" else "OK", state.status)
            assertEquals(if (attempt < 2) contractJson.encodeToString(listOf(tracked, feedback)) else null,
                state.remainingFiles)
            assertTrue(state.issue?.contains(tracked) == true)
            assertTrue(state.issue?.contains(feedback) == true)
        }
        manifest(files)
        sync.sync(repo, null)
        assertEquals(15, server.requestCount)
        manifest(files, ref = "b".repeat(40))
        respond("[]")
        respond("[]")
        sync.sync(repo, null)
        assertEquals(3, db.dao().artifacts(repo).size)
    }

    @Test fun unauthorizedRequestWaitsForRegistration() = runBlocking {
        sync.enqueue(repo, first)
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(sync.send(repo, "expired"))
        assertEquals("NEEDS_AUTH", db.dao().request(repo, first.requestId)?.status)
    }

    @Test fun retryKeepsInboxPathAndSerializedRequest() = runBlocking {
        sync.enqueue(repo, first)
        sync.enqueue(repo, first)
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(503))
        assertFalse(sync.send(repo, "test-token"))
        assertEquals("PENDING", db.dao().pending(repo).single().status)
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(422))
        respond(contractJson.encodeToString<InboxRequest>(first))
        assertTrue(sync.send(repo, "test-token"))
        assertEquals("SENT", db.dao().request(repo, first.requestId)?.status)
        server.takeRequest()
        val initial = server.takeRequest()
        server.takeRequest()
        val retry = server.takeRequest()
        assertEquals(initial.path, retry.path)
        assertTrue(initial.requestUrl!!.encodedPath.contains(first.requestId))
        val initialBody = initial.body.readUtf8()
        assertEquals(initialBody, retry.body.readUtf8())
        val json = contractJson.parseToJsonElement(initialBody).jsonObject
        val payload = Base64.getDecoder().decode(json.getValue("content").jsonPrimitive.content).decodeToString()
        assertEquals(first, contractJson.decodeFromString<InboxRequest>(payload))
        assertEquals("feedback", contractJson.parseToJsonElement(payload).jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("data", json.getValue("branch").jsonPrimitive.content)
        assertEquals(inboxPath(first), db.dao().request(repo, first.requestId)?.path)
    }

    @Test fun rejectionRestoresPriorReactionAndKeepsReason() = runBlocking {
        sync.enqueue(repo, first)
        sync.enqueue(repo, second)
        assertEquals(Feedback.Kind.NOT_INTERESTED, sync.reaction(repo, first.cardId).first())
        val applied = contractJson.encodeToString(InboxResult(requestId = first.requestId, ok = true))
        val rejected = contractJson.encodeToString(InboxResult(requestId = second.requestId, ok = false, reason = "invalid"))
        manifest(linkedMapOf(resultPath(first.requestId) to applied, resultPath(second.requestId) to rejected))
        respond(applied)
        respond(rejected)
        sync.sync(repo, null)
        assertEquals("APPLIED", db.dao().request(repo, first.requestId)?.status)
        assertEquals("REJECTED", db.dao().request(repo, second.requestId)?.status)
        assertEquals("invalid", db.dao().request(repo, second.requestId)?.reason)
        assertEquals(Feedback.Kind.STAR, sync.reaction(repo, first.cardId).first())
    }

    @Test fun lostDailyDispatchResponseBlocksAnotherPostForTenMinutes() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"workflow_runs":[]}"""))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertTrue(runCatching { sync.dispatchDaily(repo, "test-token") }.isFailure)
        assertEquals(now.toString(), db.dao().dispatch(repo)?.dispatchedAt)
        assertTrue(db.dao().dispatch(repo)?.active == true)
        now = now.plusSeconds(599)
        server.enqueue(MockResponse().setBody("""{"workflow_runs":[]}"""))
        assertFalse(sync.dispatchDaily(repo, "test-token"))
        assertEquals(listOf("GET", "GET", "POST", "GET"), List(4) { server.takeRequest().method })
    }

    @Test fun invalidSettingsAreQuarantinedWithoutDiscardingValidFiles() = runBlocking {
        val budget = "settings/budget.json"
        val schedule = "settings/schedule.json"
        manifest(linkedMapOf(budget to """{"dailyCardUsd":[]}""", schedule to """{"enabled":false}"""))
        respond("""{"dailyCardUsd":[]}""")
        respond("""{"enabled":false}""")
        sync.sync(repo, null)
        assertEquals(listOf(schedule), db.dao().artifacts(repo).map { it.path })
        assertTrue(db.dao().state(repo)?.issue.orEmpty().contains(budget))
    }

    @Test fun undoCancelsUnsentFeedbackAndQueuesUndoAfterDelivery() = runBlocking {
        sync.enqueue(repo, second)
        assertTrue(sync.undoFeedback(repo, second.requestId))
        assertEquals("CANCELLED", db.dao().request(repo, second.requestId)?.status)
        assertTrue(db.dao().pending(repo).isEmpty())

        val sent = second.copy(requestId = "00000000-0000-0000-0000-000000000003")
        sync.enqueue(repo, sent)
        db.dao().uploaded(repo, sent.requestId)
        db.dao().complete(repo, sent.requestId, "SENT", null)
        assertTrue(sync.undoFeedback(repo, sent.requestId))
        val undo = contractJson.decodeFromString<InboxRequest>(db.dao().pending(repo).single().json) as InboxRequest.FeedbackUndo
        assertEquals(sent.createdAt, undo.dismissedAt)
        assertEquals(sent.cardId, undo.cardId)
        assertEquals(null, sync.reaction(repo, sent.cardId).first())
    }

    @Test fun analysisRetryDispatchesWithRequestIdWithoutReuploading() = runBlocking {
        val request = InboxRequest.Analysis(DataBranch.VERSION, first.requestId, time, listOf("theme"),
            collector.data.AnalysisMode.ESTIMATE, 0.0)
        sync.enqueue(repo, request)
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(201))
        defaultBranch()
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertFalse(sync.send(repo, "test-token"))
        assertEquals("DISPATCHING", db.dao().request(repo, request.requestId)?.status)
        server.enqueue(MockResponse().setResponseCode(404))
        assertFalse(sync.send(repo, "test-token"))
        now = now.plusSeconds(600)
        server.enqueue(MockResponse().setResponseCode(404))
        analysisRun("completed", "failure")
        defaultBranch()
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(sync.send(repo, "test-token"))
        val requests = List(9) { server.takeRequest() }
        assertEquals(listOf("GET", "PUT", "GET", "POST", "GET", "GET", "GET", "GET", "POST"), requests.map { it.method })
        assertTrue(requests.last().path!!.endsWith("/actions/workflows/analyze.yml/dispatches"))
        val dispatch = contractJson.parseToJsonElement(requests.last().body.readUtf8()).jsonObject
        assertEquals("trunk", dispatch.getValue("ref").jsonPrimitive.content)
        val inputs = dispatch.getValue("inputs").jsonObject
        assertEquals(setOf("requestId"), inputs.keys)
        assertEquals(request.requestId, inputs.getValue("requestId").jsonPrimitive.content)
    }

    @Test fun explicitlyRejectedDispatchIsPostedAgainAfterReauthentication() = runBlocking {
        val request = InboxRequest.Analysis(DataBranch.VERSION, first.requestId, time, listOf("theme"),
            collector.data.AnalysisMode.ESTIMATE, 0.0)
        sync.enqueue(repo, request)
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(201))
        defaultBranch()
        server.enqueue(MockResponse().setResponseCode(401))
        sync.send(repo, "test-token")
        val rejected = db.dao().request(repo, request.requestId)
        assertEquals("NEEDS_AUTH", rejected?.status)
        // No run was created, so the request must not wait on a run lookup.
        assertEquals(null, rejected?.dispatchedAt)
        db.dao().saveRequest(rejected!!.copy(status = "PENDING", reason = null))
        server.enqueue(MockResponse().setResponseCode(404))
        defaultBranch()
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(sync.send(repo, "test-token"))
        val methods = List(7) { server.takeRequest().method }
        assertEquals(listOf("GET", "PUT", "GET", "POST", "GET", "GET", "POST"), methods)
    }

    @Test fun resultLookupRejectionAfterLostDispatchKeepsDispatchTime() = runBlocking {
        val request = InboxRequest.Analysis(DataBranch.VERSION, first.requestId, time, listOf("theme"),
            collector.data.AnalysisMode.ESTIMATE, 0.0)
        sync.enqueue(repo, request)
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(201))
        defaultBranch()
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
        sync.send(repo, "test-token")
        val dispatchedAt = db.dao().request(repo, request.requestId)?.dispatchedAt
        assertTrue(dispatchedAt != null)
        // The dispatch may have been accepted; a 401 on the *result* lookup must not reopen a blind re-POST.
        server.enqueue(MockResponse().setResponseCode(401))
        sync.send(repo, "test-token")
        val row = db.dao().request(repo, request.requestId)
        assertEquals("NEEDS_AUTH", row?.status)
        assertEquals(dispatchedAt, row?.dispatchedAt)
    }

    @Test fun cancelledAnalysisCanDispatchAgainAfterThirtyMinutes() = runBlocking {
        val request = InboxRequest.Analysis(DataBranch.VERSION, first.requestId, time, listOf("theme"),
            collector.data.AnalysisMode.ESTIMATE, 0.0)
        sync.enqueue(repo, request)
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(201))
        defaultBranch()
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(sync.send(repo, "test-token"))
        now = now.plusSeconds(1800)
        assertTrue(sync.send(repo, "test-token"))
        assertEquals(4, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(404))
        analysisRun("in_progress")
        assertTrue(sync.send(repo, "test-token", checkSent = true))
        assertEquals(6, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(403))
        assertTrue(sync.send(repo, "test-token", checkSent = true))
        assertEquals("분석 요청 상태 확인 필요", db.dao().request(repo, request.requestId)?.reason)
        assertEquals(8, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(404))
        analysisRun("completed", "cancelled")
        defaultBranch()
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(sync.send(repo, "test-token", checkSent = true))
        assertEquals(listOf("GET", "PUT", "GET", "POST", "GET", "GET", "GET", "GET", "GET", "GET", "GET", "POST"),
            List(12) { server.takeRequest().method })
    }

    @Test fun cancelledRequestRetriesWhileAnotherRequestIsActive() = runBlocking {
        for (id in listOf(first.requestId, second.requestId)) {
            sync.enqueue(repo, InboxRequest.Analysis(DataBranch.VERSION, id, time, listOf("theme"),
                collector.data.AnalysisMode.ESTIMATE, 0.0))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(201))
            defaultBranch()
            server.enqueue(MockResponse().setResponseCode(204))
        }
        assertTrue(sync.send(repo, "test-token"))
        repeat(8) { server.takeRequest() }
        now = now.plusSeconds(1800)
        server.enqueue(MockResponse().setResponseCode(404))
        analysisRun("completed", "cancelled", otherActive = true)
        defaultBranch()
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(404))
        analysisRun("completed", "cancelled", otherActive = true)
        assertTrue(sync.send(repo, "test-token", checkSent = true))
        assertEquals(14, server.requestCount)
        val posts = List(6) { server.takeRequest() }.filter { it.method == "POST" }
        assertTrue(posts.single().body.readUtf8().contains(first.requestId))
        assertEquals(now.epochSecond, db.dao().request(repo, first.requestId)?.dispatchedAt)
        assertEquals(now.minusSeconds(1800).epochSecond, db.dao().request(repo, second.requestId)?.dispatchedAt)
    }

    private fun defaultBranch() = respond("""{"default_branch":"trunk"}""")

    private fun analysisRun(status: String, conclusion: String? = null, otherActive: Boolean = false) {
        val run = kotlinx.serialization.json.buildJsonObject {
            put("display_title", kotlinx.serialization.json.JsonPrimitive("Analyze " + first.requestId))
            put("created_at", kotlinx.serialization.json.JsonPrimitive(time))
            put("status", kotlinx.serialization.json.JsonPrimitive(status))
            put("conclusion", kotlinx.serialization.json.JsonPrimitive(conclusion))
        }
        val other = if (otherActive) """,{"display_title":"Analyze ${second.requestId}","created_at":"$time","status":"in_progress"}""" else ""
        respond("{\"workflow_runs\":[" + run + other + "]}")
    }

    @Test fun rateLimitDefersAndRepositoryChangeCancelsPendingRequest() = runBlocking {
        sync.enqueue(repo, first)
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
        assertFalse(sync.send(repo, "test-token"))
        assertEquals(now.epochSecond + 120, db.dao().request(repo, first.requestId)?.nextAttemptAt)
        assertFalse(sync.send(repo, "test-token"))
        assertEquals(1, server.requestCount)
        assertEquals(1, sync.changeRepository(app.ideacollector.data.AppSettings(repository = "other/repo")))
        assertTrue(sync.send(repo, "test-token"))
        assertEquals("CANCELLED", db.dao().request(repo, first.requestId)?.status)
    }

    @Test fun permanentFailureRestoresOptimisticReaction() = runBlocking {
        sync.enqueue(repo, first)
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(301))
        assertTrue(sync.send(repo, "test-token"))
        assertEquals("FAILED", db.dao().request(repo, first.requestId)?.status)
        assertNull(sync.reaction(repo, first.cardId).first())
    }

    @Test fun newerManifestSignalsUpdateOnce() = runBlocking {
        val body = contractJson.encodeToString(DataManifest(schemaVersion = DataBranch.VERSION + 1,
            updatedAt = time, latestDaily = null, latestTrend = null, files = emptyList()))
        head()
        respond(body)
        head()
        respond(body)
        assertTrue(sync.sync(repo, null).updateRequired)
        assertFalse(sync.sync(repo, null).updateRequired)
        assertEquals("APP_UPDATE_REQUIRED", db.dao().state(repo)?.status)
    }

    @Test fun skipsOversizedAndUndecodableArtifacts() = runBlocking {
        val oversized = DataFile("out/feedback.json", 5L * 1024 * 1024 + 1, "unused")
        val invalid = DataFile("out/tracked.json", 1, "{".toByteArray().sha256())
        val data = dailyFixture()
        val daily = DataFile(path, data.toByteArray().size.toLong(), data.toByteArray().sha256())
        head()
        respond(contractJson.encodeToString(DataManifest(updatedAt = time, latestDaily = day,
            latestTrend = null, files = listOf(oversized, invalid, daily))))
        respond("{")
        respond(data)
        assertEquals(1, sync.sync(repo, null).cards)
        assertEquals(4, server.requestCount)
        assertEquals(path, db.dao().artifacts(repo).single().path)
        assertTrue(db.dao().state(repo)?.issue?.contains(invalid.path) == true)
        head()
        respond(contractJson.encodeToString(DataManifest(updatedAt = time,
            latestDaily = day, latestTrend = null, files = listOf(oversized, invalid, daily))))
        sync.sync(repo, null)
        assertEquals(6, server.requestCount)
    }

    @Test fun newerResultIsIsolatedFromOtherArtifacts() = runBlocking {
        val result = InboxResult(schemaVersion = DataBranch.VERSION + 1, requestId = first.requestId, ok = true)
        val body = contractJson.encodeToString(result)
        val daily = dailyFixture()
        val resultPath = resultPath(first.requestId)
        manifest(linkedMapOf(resultPath to body, path to daily))
        respond(body)
        respond(daily)
        assertEquals(1, sync.sync(repo, null).cards)
        assertEquals(path, db.dao().artifacts(repo).single().path)
        assertTrue(db.dao().state(repo)?.issue?.contains(resultPath) == true)
    }

    @Test fun streamedManifestExceedingLimitIsRejected() = runBlocking {
        head()
        server.enqueue(MockResponse().setChunkedBody(" ".repeat(5 * 1024 * 1024 + 1), 64 * 1024))
        assertTrue(runCatching { sync.sync(repo, null) }.exceptionOrNull() is java.io.IOException)
        assertNull(db.dao().state(repo))
    }

    @Test fun existingSnapshotResumesOverBudget() = runBlocking {
        val original = dailyFixture()
        manifest(mapOf(path to original))
        respond(original)
        sync.sync(repo, null)

        val size = 5 * 1024 * 1024
        val large = "0".padEnd(size, ' ')
        val hash = large.toByteArray().sha256()
        val files = (1..10).map { DataFile("out/cost-ledger/$it.json", size.toLong(), hash) }
        val updated = dailyFixture("Updated").padEnd(size, ' ')
        val daily = DataFile(path, updated.toByteArray().size.toLong(), updated.toByteArray().sha256())
        val remote = contractJson.encodeToString(DataManifest(updatedAt = time, latestDaily = day,
            latestTrend = null, files = files + daily))
        head()
        respond(remote)
        repeat(9) { respond(large) }
        sync.sync(repo, null)
        assertEquals("PARTIAL", db.dao().state(repo)?.status)
        assertEquals(10, db.dao().hashes(repo).size)
        assertEquals("card-1", db.dao().search(repo, "Automation").single().id)
        assertEquals(listOf(files.last().path, path),
            contractJson.decodeFromString<List<String>>(checkNotNull(db.dao().state(repo)?.remainingFiles)))

        head()
        respond(remote)
        respond(large)
        respond(updated)
        sync.sync(repo, null)
        assertEquals("OK", db.dao().state(repo)?.status)
        assertEquals(11, db.dao().hashes(repo).size)
        assertEquals("card-1", db.dao().search(repo, "Updated").single().id)
        assertEquals(18, server.requestCount)
    }

    @Test fun newManifestRecomputesThePartialFileList() = runBlocking {
        val original = dailyFixture()
        manifest(mapOf(path to original))
        respond(original)
        sync.sync(repo, null)
        val previous = checkNotNull(db.dao().state(repo))
        db.dao().saveState(previous.copy(status = "PARTIAL", remainingFiles = "[\"out/tracked.json\"]"))
        val updated = dailyFixture("Updated")
        manifest(mapOf(path to updated))
        respond(updated)
        sync.sync(repo, null)
        assertEquals("OK", db.dao().state(repo)?.status)
        assertNull(db.dao().state(repo)?.remainingFiles)
        assertEquals("card-1", db.dao().search(repo, "Updated").single().id)
    }

    @Test fun brokenReplacementPreservesArtifactAndCardIndex() = runBlocking {
        val original = dailyFixture()
        manifest(mapOf(path to original, "out/tracked.json" to "[]"))
        respond(original)
        respond("[]")
        sync.sync(repo, null)
        val invalid = DataFile(path, 1, "{".toByteArray().sha256())
        val oversized = DataFile("out/tracked.json", 5L * 1024 * 1024 + 1, "unused")
        head()
        respond(contractJson.encodeToString(DataManifest(updatedAt = time, latestDaily = day,
            latestTrend = null, files = listOf(invalid, oversized))))
        respond("{")
        sync.sync(repo, null)
        assertEquals(original, db.dao().artifacts(repo).single { it.path == path }.json)
        assertEquals("[]", db.dao().artifacts(repo).single { it.path == oversized.path }.json)
        assertEquals("card-1", db.dao().search(repo, "Automation").single().id)
    }

    @Test fun mismatchedResultPathDoesNotBlockOtherArtifacts() = runBlocking {
        val mismatch = resultPath(second.requestId)
        val result = contractJson.encodeToString(InboxResult(requestId = first.requestId, ok = true))
        val daily = dailyFixture()
        manifest(linkedMapOf(mismatch to result, path to daily))
        respond(result)
        respond(daily)
        assertEquals(1, sync.sync(repo, null).cards)
        assertEquals(path, db.dao().artifacts(repo).single().path)
        assertTrue(db.dao().state(repo)?.issue?.contains(mismatch) == true)
    }

    // Same core PipelineResult/IdeaCard contract used by DataSyncTest's round-trip fixture.
    private fun dailyFixture(title: String = "Automation"): String {
        val card = IdeaCard(id = "card-1", runDate = day, title = title, oneLiner = "workflow",
            whyToday = "", problem = "", techSignals = listOf(SignalRef("item", "https://example.invalid", "evidence")),
            demandSignals = emptyList(), competitors = emptyList(), history = emptyList(), effort = "",
            differentiation = "", biggestRisk = "", validationPlan = "", caveats = emptyList(),
            category = "test", tags = emptyList())
        val run = PipelineResult(emptyList(), emptyList(), RunReport(day, time, emptyList(), emptyList(), emptyMap(), 0),
            cards = listOf(card))
        return contractJson.encodeToString(run)
    }

    private fun manifest(files: Map<String, String>, etag: String = "\"fixture\"", ref: String = commit) {
        head(ref)
        val manifest = DataManifest(updatedAt = time, latestDaily = day, latestTrend = null, files = files.map { (key, value) ->
            val bytes = value.toByteArray()
            DataFile(key, bytes.size.toLong(), bytes.sha256())
        })
        server.enqueue(MockResponse().setHeader("ETag", etag).setBody(contractJson.encodeToString(manifest)))
    }

    private fun head(ref: String = commit) = respond("""{"object":{"type":"commit","sha":"$ref"}}""")

    private fun respond(body: String) {
        server.enqueue(MockResponse().setBody(body))
    }
}

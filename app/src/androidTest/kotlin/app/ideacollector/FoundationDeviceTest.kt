package app.ideacollector

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.ideacollector.data.AppDatabase
import app.ideacollector.data.AppSettings
import app.ideacollector.data.RepositorySync
import app.ideacollector.github.GitHubClient
import app.ideacollector.github.contractJson
import app.ideacollector.secret.KeystoreSecretProvider
import app.ideacollector.secret.GITHUB_TOKEN
import app.ideacollector.secret.SecretStatus
import app.ideacollector.secret.SecretStorage
import app.ideacollector.work.SyncWorker
import app.ideacollector.work.OutboxWorker
import collector.data.DataManifest
import collector.data.InboxRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FoundationDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun keystoreRoundTripAndLostKey() = runBlocking {
        val secrets = KeystoreSecretProvider(context)
        val name = "TEST_KEY"
        try {
            secrets.set(name, "dummy-value-1234")
            assertTrue(secrets.has(name))
            assertEquals("1234", secrets.last4(name))
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry("app.ideacollector.secret.$name")
            assertEquals(SecretStatus.NEEDS_REGISTRATION, secrets.status(name))
            assertNull(secrets.last4(name))
            secrets.set(name, "dummy-value-5678")
            assertEquals("5678", secrets.last4(name))
        } finally {
            secrets.clear(name)
        }
        assertEquals(SecretStatus.MISSING, secrets.status(name))
    }

    @Test fun registrationRecreatesCorruptedDataStore() = runBlocking {
        val directory = java.io.File(context.cacheDir, java.util.UUID.randomUUID().toString())
        check(directory.mkdirs())
        val file = java.io.File(directory, "test.preferences_pb")
        file.writeBytes(byteArrayOf(0))
        val secrets = KeystoreSecretProvider(SecretStorage(file))
        val name = "CORRUPTION_TEST_KEY"
        try {
            assertEquals(SecretStatus.NEEDS_REGISTRATION, secrets.status(name))
            assertTrue(file.exists())
            assertTrue(secrets.set(name, "dummy-value-5678"))
            assertEquals("5678", secrets.last4(name))
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry("app.ideacollector.secret.$name")
            directory.deleteRecursively()
        }
    }

    @Test fun roomPersistsSettingsAcrossOpen() = runBlocking {
        val name = "room-device-test.db"
        try {
            val initial = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                initial.dao().saveSettings(AppSettings(repository = "sample/test", syncHours = 12))
            } finally {
                initial.close()
            }
            val reopened = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                assertEquals(12L, reopened.dao().settings()?.syncHours)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test fun workDriverRunsSyncAndOutboxAgainstMockServer() = runBlocking {
        val app = context.applicationContext as CollectorApp
        val id = java.util.UUID.randomUUID().toString()
        val repo = "sample/device-test-" + id
        val previous = app.settings()
        check(app.secrets.status(GITHUB_TOKEN) == SecretStatus.MISSING) { "The worker test requires a clean test app" }
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val work = WorkManager.getInstance(context)
        val driver = requireNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        MockWebServer().use { server ->
            app.syncOverride = RepositorySync(app.database, GitHubClient(base = server.url("/")))
            val tokenName = GITHUB_TOKEN
            try {
                app.database.dao().saveSettings(AppSettings(repository = repo, notifications = false))
                app.secrets.set(tokenName, "device-test-token")
                val now = java.time.Instant.now().toString()
                val commit = "a".repeat(40)
                server.enqueue(MockResponse().setBody("""{"object":{"type":"commit","sha":"$commit"}}"""))
                server.enqueue(MockResponse().setBody(contractJson.encodeToString(
                    DataManifest(updatedAt = now, latestDaily = null, latestTrend = null, files = emptyList())
                )))
                val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                val sync = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build()
                work.enqueue(sync).result.get(10, TimeUnit.SECONDS)
                driver.setAllConstraintsMet(sync.id)
                assertEquals("/repos/$repo/git/ref/heads/data", server.takeRequest(10, TimeUnit.SECONDS)?.path)
                assertEquals("/repos/$repo/contents/manifest.json?ref=$commit", server.takeRequest(10, TimeUnit.SECONDS)?.path)
                awaitSuccess(work, sync.id)
                assertNotNull(app.database.dao().state(repo))

                app.sync.enqueue(repo, InboxRequest.TrackStart(1, id, now, "card"))
                server.enqueue(MockResponse().setResponseCode(404))
                server.enqueue(MockResponse().setResponseCode(201))
                app.onCreate()
                val deadline = android.os.SystemClock.elapsedRealtime() + 10_000L
                var scheduled = work.getWorkInfosForUniqueWork("outbox").get(2, TimeUnit.SECONDS)
                while (scheduled.isEmpty() && android.os.SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(25)
                    scheduled = work.getWorkInfosForUniqueWork("outbox").get(2, TimeUnit.SECONDS)
                }
                val outbox = scheduled.single()
                app.recoverOutbox()
                assertEquals(outbox.id, work.getWorkInfosForUniqueWork("outbox").get(2, TimeUnit.SECONDS).single().id)
                driver.setAllConstraintsMet(outbox.id)
                assertEquals("GET", server.takeRequest(10, TimeUnit.SECONDS)?.method)
                assertEquals("PUT", server.takeRequest(10, TimeUnit.SECONDS)?.method)
                awaitSuccess(work, outbox.id)
                assertEquals("SENT", app.database.dao().request(repo, id)?.status)

                val analysisId = java.util.UUID.randomUUID().toString()
                app.sync.enqueue(repo, InboxRequest.Analysis(1, analysisId, now, listOf("theme"),
                    collector.data.AnalysisMode.ESTIMATE, 0.0))
                server.enqueue(MockResponse().setResponseCode(404))
                server.enqueue(MockResponse().setResponseCode(201))
                server.enqueue(MockResponse().setResponseCode(204))
                val analysis = OneTimeWorkRequestBuilder<OutboxWorker>().build()
                work.enqueue(analysis).result.get(10, TimeUnit.SECONDS)
                awaitSuccess(work, analysis.id)
                assertEquals("SENT", app.database.dao().request(repo, analysisId)?.status)
                assertEquals("/repos/$repo/contents/out/inbox-results/$analysisId.json?ref=data",
                    server.takeRequest(10, TimeUnit.SECONDS)?.path)
                assertEquals("PUT", server.takeRequest(10, TimeUnit.SECONDS)?.method)
                assertEquals("POST", server.takeRequest(10, TimeUnit.SECONDS)?.method)
                val rerun = OneTimeWorkRequestBuilder<OutboxWorker>().build()
                work.enqueue(rerun).result.get(10, TimeUnit.SECONDS)
                awaitSuccess(work, rerun.id)
                assertFalse(app.database.dao().hasOutbox(repo))
                assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
            } finally {
                work.cancelAllWork().result.get(10, TimeUnit.SECONDS)
                app.database.dao().saveSettings(previous)
                app.secrets.clear(tokenName)
                app.syncOverride = null
            }
        }
    }

    private fun awaitSuccess(work: WorkManager, id: java.util.UUID) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000L
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val state = work.getWorkInfoById(id).get(2, TimeUnit.SECONDS)?.state
            if (state?.isFinished == true) {
                assertEquals(WorkInfo.State.SUCCEEDED, state)
                return
            }
            Thread.sleep(25)
        }
        fail("Work did not finish")
    }
}

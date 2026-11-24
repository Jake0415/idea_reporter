package app.ideacollector

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.inspector.WindowInspector
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.ideacollector.data.LOCAL_REPO
import app.ideacollector.github.contractJson
import app.ideacollector.work.BackupRunWorker
import app.ideacollector.work.DeferredRunWorker
import collector.model.RunReport
import collector.model.TrackedIdea
import collector.pipeline.PipelineResult
import collector.runtime.Backup
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val fixtureDate = "2026-01-24"
    private val app get() = instrumentation.targetContext.applicationContext as CollectorApp

    @Test fun backupScreenExportsPreviewsRejectsAndBlocks() {
        val prefs = app.getSharedPreferences("first-run", 0)
        val previous = prefs.all["finished"] as? Boolean
        val file = java.io.File.createTempFile("r4-backup-", ".json", app.cacheDir)
        val daily = app.paths.outDir.resolve("$fixtureDate.json")
        val previousDaily = daily.takeIf { it.isFile }?.readBytes()
        val tracked = app.paths.outDir.resolve("tracked.json")
        val previousTracking = tracked.takeIf { it.isFile }?.readBytes()
        val budget = app.paths.outDir.resolve("settings/budget.json")
        val previousBudget = budget.takeIf { it.isFile }?.readBytes()
        checkNotNull(budget.parentFile).mkdirs()
        budget.writeText("""{"monthlyTotalUsd":5}""")
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?
                ) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
                    }
                }
            }
        }
        prefs.edit().putBoolean("finished", true).commit()
        try {
            daily.delete()
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    val screens = ScreensViewModel(app.database.dao())
                    activity.setContent {
                        CollectorTheme {
                            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                                CollectorScreens(app, screens, null) {}
                            }
                        }
                    }
                }
                await(Page.SETTINGS.title)
                node(Page.SETTINGS.title).performClick()
                await(Page.OPERATE.title)
                node(Page.OPERATE.title).performClick()
                await(R.string.backup_export)
                node(R.string.backup_export).performScrollTo().assertIsEnabled()
                node(R.string.backup_import).assertIsEnabled()

                node(R.string.backup_export).performClick()
                await(R.string.backup_exported)
                file.inputStream().use(Backup::read)

                val exported = file.readBytes()
                file.writeText(fixtureBackup())
                node(R.string.backup_import).performClick()
                await(R.string.backup_preview)
                val period = app.getString(R.string.backup_period, fixtureDate, fixtureDate)
                compose.onNodeWithText(app.getString(R.string.backup_preview_details, 1, 1, period)).assertIsDisplayed()
                capturePreview(scenario)
                node(R.string.backup_import_confirm).performClick()
                await(R.string.backup_imported)
                org.junit.Assert.assertEquals("""{"monthlyTotalUsd":5}""", budget.readText())
                org.junit.Assert.assertTrue(tracked.readText().contains("Fixture"))
                org.junit.Assert.assertTrue(daily.isFile)
                kotlinx.coroutines.runBlocking {
                    org.junit.Assert.assertTrue(app.database.dao().artifacts(LOCAL_REPO)
                        .any { it.path == "out/tracked.json" && it.json.contains("Fixture") })
                }
                node(R.string.backup_imported).performScrollTo()

                file.writeText("invalid")
                node(R.string.backup_import).performClick()
                await(R.string.backup_invalid)
                node(R.string.backup_invalid).performScrollTo()

                compose.onNodeWithContentDescription(app.getString(R.string.back)).performClick()
                await(Page.OPERATE.title)
                node(Page.OPERATE.title).performClick()
                app.backupState.value = BackupState()
                app.operationRunning.value = true
                await(R.string.backup_busy)
                node(R.string.backup_busy).performScrollTo()
                node(R.string.backup_import).assertIsNotEnabled()
                app.operationRunning.value = false
                file.writeBytes(exported)
                node(R.string.backup_import).performClick()
                await(R.string.backup_preview)
                node(R.string.backup_import_confirm).performClick()
                await(R.string.backup_imported)
            }
        } finally {
            app.operationRunning.value = false
            prefs.edit().also { if (previous == null) it.remove("finished") else it.putBoolean("finished", previous) }.commit()
            if (previousTracking == null) tracked.delete() else tracked.writeBytes(previousTracking)
            if (previousDaily == null) daily.delete() else daily.writeBytes(previousDaily)
            if (previousBudget == null) budget.delete() else budget.writeBytes(previousBudget)
            app.backupState.value = BackupState()
            kotlinx.coroutines.runBlocking { app.sync.refresh() }
            file.delete()
        }
    }

    @Test fun recoveryPendingShowsRetry() {
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use {
            compose.waitUntil(10_000) { app.recovery.value == BackupRecovery.READY }
            Backup.markRecoveryPending(app.paths)
            try {
                app.checkBackupRollback()
                await(R.string.backup_recovery_pending)
                runBlocking {
                    val deferred = TestListenableWorkerBuilder<DeferredRunWorker>(app)
                        .setInputData(workDataOf("date" to fixtureDate, "batch" to true)).build()
                    val backup = TestListenableWorkerBuilder<BackupRunWorker>(app).build()
                    org.junit.Assert.assertEquals(ListenableWorker.Result.retry(), deferred.doWork())
                    org.junit.Assert.assertEquals(ListenableWorker.Result.retry(), backup.doWork())
                }
                app.paths.outDir.canonicalFile.parentFile.resolve(".backup-journal.json").writeText(
                    """{"directory":".backup-987654321","state":"applying","targets":["out/tracked.json"]}"""
                )
                node(R.string.r3_retry).performClick()
                compose.waitUntil(10_000) { app.recovery.value == BackupRecovery.READY }
                await(R.string.backup_recovery_unavailable)
                node(android.R.string.ok).performClick()
                compose.waitUntil(10_000) { !Backup.recoveryUnavailable(app.paths) }
                node(R.string.backup_recovery_unavailable).assertDoesNotExist()
                org.junit.Assert.assertFalse(Backup.recoveryPending(app.paths))
            } finally {
                if (app.recovery.value == BackupRecovery.PENDING) app.retryBackupRecovery()
            }
        }
    }

    private fun node(id: Int) = compose.onNodeWithText(app.getString(id))

    private fun fixtureBackup(): String {
        val date = fixtureDate
        val start = Instant.parse("${date}T00:00:00Z")
        val report = RunReport(date, start.toString(), emptyList(), emptyList(), emptyMap(), 0)
        val card = collector.model.IdeaCard("card", date, "Fixture", "", "", "", emptyList(), emptyList(),
            emptyList(), emptyList(), "", "", "", "", emptyList(), "", tags = emptyList())
        val tracked = TrackedIdea("track", "card", "Fixture", "", emptyList(), emptyList(),
            TrackedIdea.Status.TRACKING, start, null, 0.0, TrackedIdea.Trend.FLAT)
        return buildJsonObject {
            put("schemaVersion", 1)
            put("createdAt", start.toString())
            put("files", buildJsonObject {
                put("out/$date.json", contractJson.encodeToString(PipelineResult(emptyList(), emptyList(), report, cards = listOf(card))))
                put("out/tracked.json", contractJson.encodeToString(listOf(tracked)))
                put("out/settings/budget.json", """{"monthlyTotalUsd":8}""")
            })
        }.toString()
    }

    private fun await(id: Int) {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(app.getString(id)).fetchSemanticsNodes().isNotEmpty() ||
                (id == R.string.backup_imported && app.backupState.value.message != null)
        }
        if (id == R.string.backup_imported) {
            val actual = app.backupState.value.message?.let(app.resources::getResourceEntryName)
            org.junit.Assert.assertEquals("Import result", "backup_imported", actual)
            compose.waitUntil(20_000) {
                compose.onAllNodesWithText(app.getString(id)).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    private fun capturePreview(scenario: ActivityScenario<MainActivity>) {
        if (InstrumentationRegistry.getArguments().getString("r4Capture") != "true") return
        compose.waitForIdle()
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            decor.draw(canvas)
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val dialog = WindowInspector.getGlobalWindowViews().last { it !== decor && it.hasWindowFocus() }
                val position = IntArray(2).also(dialog::getLocationOnScreen)
                canvas.drawColor(0x66000000)
                canvas.save()
                canvas.translate(position[0].toFloat(), position[1].toFloat())
                dialog.draw(canvas)
                canvas.restore()
            }
            val theme = if (activity.resources.configuration.uiMode and 0x30 == 0x20) "dark" else "light"
            val directory = app.filesDir.resolve("r4-r4-screens/$theme").apply { mkdirs() }
            try {
                directory.resolve("preview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bitmap.recycle()
            }
        }
    }
}

package app.ideacollector

import android.app.NotificationManager
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import app.ideacollector.work.AutomaticRunWorker
import app.ideacollector.work.DeferredRunWorker
import androidx.work.testing.WorkManagerTestInitHelper
import app.ideacollector.data.LOCAL_REPO
import app.ideacollector.data.PendingLocalChange
import kotlinx.coroutines.flow.first
import app.ideacollector.github.contractJson
import app.ideacollector.work.CollectorRunService
import app.ideacollector.work.RunScheduler
import app.ideacollector.work.RunNotifications
import app.ideacollector.work.RunAlarmReceiver
import app.ideacollector.work.backupRunDate
import java.io.File
import collector.config.ConfigLoader
import collector.config.HfConfig
import collector.config.SourceConfig
import collector.config.SourcesFile
import collector.data.InboxRequest
import collector.llm.*
import collector.mail.Mailer
import collector.model.Feedback
import collector.model.Item
import collector.model.TrackedIdea
import collector.runtime.CollectorRuntime
import collector.source.SourceAdapter
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DeniedRunWorker(context: android.content.Context, params: androidx.work.WorkerParameters) :
    AutomaticRunWorker(context, params) {
    var exempt = true
    var promotions = 0
    override fun canPromote() = exempt
    override suspend fun promote(info: ForegroundInfo) {
        promotions++
        throw IllegalStateException("Foreground service denied")
    }
    override suspend fun doWork(): Result = Result.failure()
    suspend fun runFor(date: LocalDate, resume: Boolean = false, deferrals: Int = 0, manual: Boolean = false,
        fromBackup: Boolean = false) = execute(date, resume, deferrals, fromBackup, manual)
}

class PhoneRunDeviceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as CollectorApp

    private var onboardingBefore: Boolean? = null

    @org.junit.Before fun allowNotifications() {
        val prefs = app.getSharedPreferences("first-run", 0)
        onboardingBefore = prefs.all["finished"] as? Boolean
        prefs.edit().putBoolean("finished", true).commit()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @org.junit.After fun restoreOnboarding() {
        val edit = app.getSharedPreferences("first-run", 0).edit()
        val previous = onboardingBefore
        if (previous == null) edit.remove("finished") else edit.putBoolean("finished", previous)
        edit.commit()
    }

    @Test fun serviceRunsOneFakePipelineAndRefreshesRoom() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fetches = AtomicInteger()
        val completions = AtomicInteger()
        val day = LocalDate.now()
        val source = object : SourceAdapter {
            override val id = "hf"
            override suspend fun fetch(config: SourceConfig, now: Instant): List<Item> {
                fetches.incrementAndGet()
                started.complete(Unit)
                release.await()
                return listOf(Item(id, "1", "Model for testing", "https://example.invalid/model", 10, publishedAt = now))
            }
        }
        val provider = object : LlmProvider {
            override val id = ProviderId.ANTHROPIC
            override suspend fun listModels(): List<ModelInfo> = emptyList()
            override suspend fun complete(req: LlmRequest): LlmResult {
                completions.incrementAndGet()
                return LlmResult("""{"items":[{"itemId":"hf:1","profile":"none","score":null,
                    "reason":"","summary":"","tags":[],"topicKey":"","topicLabel":""}]}""",
                    id, req.model, 10, 4, 0, 0.0000015)
            }
        }
        app.paths.configDir.mkdirs()
        app.paths.sourcesConfig.writeText(ConfigLoader.json.encodeToString(SourcesFile(listOf(HfConfig()))))
        app.runtimeOverride = CollectorRuntime(app.paths, { null }, Clock.systemDefaultZone(), listOf(source),
            { listOf(provider) }, { Mailer {} })
        val scheduleFile = app.paths.outDir.resolve("settings/schedule.json")
        val oldSchedule = scheduleFile.takeIf { it.isFile }?.readText()
        scheduleFile.parentFile?.mkdirs()
        scheduleFile.writeText("""{"enabled":true}""")
        val notifications = app.getSystemService(NotificationManager::class.java)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            assertTrue(CollectorRunService.startDaily(activity))
            withTimeout(15_000) { started.await() }
            withTimeout(5_000) {
                while (notifications.activeNotifications.none {
                    it.notification.extras.getCharSequence("android.title") == app.getString(R.string.run_collecting)
                }) delay(25)
            }
            assertTrue(CollectorRunService.startDaily(activity, day))
            withTimeout(5_000) {
                while (app.schedule.pendingReservation() != day) delay(25)
            }
            app.submit(listOf(InboxRequest.Reaction(1, UUID.randomUUID().toString(), Instant.now().toString(),
                "queued-card", Feedback.Kind.STAR, "fixture")))
            app.saveLocalConfig("sources.local.json", ConfigLoader.json.encodeToString(SourcesFile(emptyList())))
            assertEquals(2, app.database.dao().localChanges().size)
            delay(300)
            release.complete(Unit)
            withTimeout(30_000) {
                while (notifications.activeNotifications.none {
                    it.id == 32 && it.notification.extras.getCharSequence("android.title") ==
                        app.getString(R.string.run_finished, 0, 0)
                }) delay(30)
            }
            assertNull(app.schedule.pendingReservation())
            assertTrue(app.database.dao().localChanges().isEmpty())
            assertTrue(app.paths.outDir.resolve("feedback.json").readText().contains("queued-card"))
            assertEquals(emptyList<SourceConfig>(), ConfigLoader.load(app.paths.sourcesConfig).sources)
            assertEquals(1, fetches.get())
            assertEquals(1, completions.get())
            assertTrue(app.database.dao().artifacts(LOCAL_REPO).any { it.path == "out/$day.json" })
            assertTrue(notifications.activeNotifications.any {
                it.notification.extras.getCharSequence("android.title") == app.getString(R.string.run_finished, 0, 0)
            })
        } finally {
            release.complete(Unit)
            withTimeoutOrNull(30_000) { while (CollectorRunService.running.value) delay(30) }
            app.runtimeOverride = null
            if (oldSchedule == null) scheduleFile.delete() else scheduleFile.writeText(oldSchedule)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun feedbackAndTrackingAreWrittenDirectly() = runBlocking {
        val instant = Instant.now()
        val track = TrackedIdea("fixture-track", "fixture-card", "fixture", "test",
            listOf("old"), emptyList(), TrackedIdea.Status.TRACKING, instant, null, 0.0, TrackedIdea.Trend.FLAT)
        app.paths.outDir.mkdirs()
        app.paths.outDir.resolve("tracked.json").writeText(contractJson.encodeToString(listOf(track)))
        app.submit(listOf(
            InboxRequest.Reaction(1, UUID.randomUUID().toString(), instant.toString(), "fixture-card", Feedback.Kind.STAR, "fixture"),
            InboxRequest.TrackKeywords(1, UUID.randomUUID().toString(), instant.toString(), track.id, listOf("new"))))
        val artifacts = app.database.dao().artifacts(LOCAL_REPO).associateBy { it.path }
        assertTrue(artifacts.getValue("out/feedback.json").json.contains("fixture-card"))
        assertEquals(listOf("new"), artifacts.getValue("out/tracked.json").decode<List<TrackedIdea>>().single().keywords)
    }

    @Test fun workerChecksRunStateUnderClaimAndSkipsEmptyResume() = runBlocking {
        val date = LocalDate.now()
        val prefs = app.getSharedPreferences("phone-run", android.content.Context.MODE_PRIVATE)
        val key = "success-$date"
        val previous = prefs.getBoolean(key, false)
        val paths = collector.runtime.CollectorPaths(app.cacheDir.resolve("empty-resume/out"), app.cacheDir.resolve("empty-resume/config"))
        app.runtimeOverride = CollectorRuntime(paths, { null }, Clock.systemDefaultZone(), emptyList(), { error("Unexpected LLM initialization") })
        fun worker(batch: Boolean) = TestListenableWorkerBuilder<DeferredRunWorker>(app)
            .setInputData(androidx.work.workDataOf("date" to date.toString(), "batch" to batch)).build()
        try {
            prefs.edit().putBoolean(key, true).commit()
            app.operationRunning.value = true
            val pending = app.schedule.pendingReservation()
            assertEquals(ListenableWorker.Result.success(), worker(false).doWork())
            assertEquals(pending, app.schedule.pendingReservation())
            app.operationRunning.value = false
            assertEquals(ListenableWorker.Result.success(), worker(false).doWork())
            assertEquals(ListenableWorker.Result.success(), worker(true).doWork())
        } finally {
            prefs.edit().putBoolean(key, previous).commit()
            app.operationRunning.value = false
            app.runtimeOverride = null
        }
    }

    @Test fun foregroundDenialStopsBeforeCallingRuntime() = runBlocking {
        val date = LocalDate.now()
        val worker = TestListenableWorkerBuilder<DeniedRunWorker>(app).build()
        val paths = collector.runtime.CollectorPaths(app.cacheDir.resolve("denied-worker/out"), app.cacheDir.resolve("denied-worker/config"))
        app.runtimeOverride = CollectorRuntime(paths, { null }, Clock.systemDefaultZone(), emptyList(), { error("Unexpected LLM initialization") })
        try {
            val prefs = app.getSharedPreferences("phone-run", android.content.Context.MODE_PRIVATE)
            val attempts = prefs.getInt("attempts-$date", 0)
            val report = app.paths.outDir.resolve("$date.json")
            val original = report.takeIf { it.isFile }?.readBytes()
            val recorded = prefs.getBoolean("success-$date", false)
            val reportMoved = report.delete()
            prefs.edit().remove("success-$date").commit()
            try {
                worker.exempt = false
                assertEquals(ListenableWorker.Result.failure(), worker.runFor(date))
                assertEquals(0, worker.promotions)
                worker.exempt = true
                assertEquals(ListenableWorker.Result.failure(), worker.runFor(date))
                assertEquals(1, worker.promotions)
                assertEquals(attempts, prefs.getInt("attempts-$date", 0))
                assertTrue(app.schedule.status().batteryExemptionRequired)
                assertFalse(paths.outDir.exists())
            } finally {
                if (reportMoved && original != null) report.writeBytes(original)
                prefs.edit().putBoolean("success-$date", recorded).commit()
            }
        } finally {
            app.schedule.requireBatteryExemption(false)
            app.runtimeOverride = null
        }
    }

    @Test fun queuedChangesAreAppliedBeforeCollectionAndFailedRowsDoNotBlockThem() = runBlocking {
        val date = LocalDate.now().minusYears(35)
        val paths = collector.runtime.CollectorPaths(app.cacheDir.resolve("pending-start/out"), app.cacheDir.resolve("pending-start/config"))
        paths.configDir.mkdirs()
        paths.sourcesConfig.writeText(ConfigLoader.json.encodeToString(SourcesFile(listOf(HfConfig()))))
        val source = object : SourceAdapter {
            override val id = "hf"
            override suspend fun fetch(config: SourceConfig, now: Instant): List<Item> =
                error("The queued source settings must be applied before collection")
        }
        app.runtimeOverride = CollectorRuntime(paths, { null }, Clock.systemDefaultZone(), listOf(source), { emptyList() })
        val dao = app.database.dao()
        dao.enqueueLocalChange(PendingLocalChange(configName = "invalid-config", json = "{}"))
        dao.enqueueLocalChange(PendingLocalChange(configName = "sources.local.json",
            json = ConfigLoader.json.encodeToString(SourcesFile(emptyList()))))
        try {
            assertEquals(0, app.executeDaily(date, resume = false, automatic = false)?.exitCode)
            assertTrue(dao.localChanges().isEmpty())
            val failed = app.failedLocalChanges.first().single { it.configName == "invalid-config" }
            assertTrue(failed.failed)
            dao.removeLocalChange(failed.id)
        } finally {
            app.getSharedPreferences("phone-run", android.content.Context.MODE_PRIVATE)
                .edit().remove("success-$date").remove("failure-$date").commit()
            app.runtimeOverride = null
        }
    }

    @Test fun deniedServiceAndLateReservationBothEnqueueDeferredWork() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val work = WorkManager.getInstance(app)
        val date = LocalDate.now()
        val name = "phone-conditions-$date"
        val original = app.schedule.options()
        val prefs = app.getSharedPreferences("phone-run", android.content.Context.MODE_PRIVATE)
        val report = app.paths.outDir.resolve("$date.json")
        val previousReport = report.takeIf { it.isFile }?.readBytes()
        val previousSuccess = prefs.getBoolean("success-$date", false)
        val paths = collector.runtime.CollectorPaths(app.cacheDir.resolve("fallback/out"), app.cacheDir.resolve("fallback/config"))
        app.runtimeOverride = CollectorRuntime(paths, { null }, Clock.systemDefaultZone(), emptyList(), { emptyList() })
        report.delete()
        prefs.edit().remove("success-$date").commit()
        app.schedule.save(original.copy(wifiOnly = false, chargingOnly = false))
        try {
            var starts = 0
            app.schedule.dispatch(date) { starts++; false }
            assertEquals(1, starts)
            val workIds = work.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS).map { it.id }
            assertTrue(workIds.isNotEmpty())
            work.cancelUniqueWork(name).result.get(10, TimeUnit.SECONDS)
            withTimeout(10_000) { while (CollectorRunService.running.value) delay(25) }
            prefs.edit().remove("success-$date").commit()

            // Mail and prompt trials hold reservations even without a daily service.
            app.operationRunning.value = true
            app.schedule.dispatch(date) { error("The shared operation must hold this reservation") }
            assertEquals(date, app.schedule.pendingReservation())
            app.finishOperation()
            assertNull(app.schedule.pendingReservation())
            assertTrue(work.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS).any { it.id !in workIds })
        } finally {
            work.cancelUniqueWork(name).result.get(10, TimeUnit.SECONDS)
            withTimeout(10_000) { while (CollectorRunService.running.value) delay(25) }
            prefs.edit().putBoolean("success-$date", previousSuccess).commit()
            if (previousReport != null) report.writeBytes(previousReport)
            app.runtimeOverride = null
            app.schedule.save(original)
        }
    }

    @Test fun foreignDeferralFailsWithoutSchedulingAContinuation() = runBlocking {
        val date = LocalDate.now().minusYears(30)
        val paths = collector.runtime.CollectorPaths(app.cacheDir.resolve("foreign-batch/out"), app.cacheDir.resolve("foreign-batch/config"))
        app.runtimeOverride = CollectorRuntime(paths, { null }, Clock.systemDefaultZone(), emptyList(), { emptyList() })
        try {
            val result = app.executeDaily(date, resume = false, automatic = false) {
                throw collector.runtime.BatchDeferredException()
            }
            assertEquals(1, result?.exitCode)
            assertTrue(WorkManager.getInstance(app).getWorkInfosForUniqueWork("phone-batch-$date")
                .get(10, TimeUnit.SECONDS).isEmpty())
        } finally {
            app.getSharedPreferences("phone-run", android.content.Context.MODE_PRIVATE)
                .edit().remove("failure-$date").commit()
            app.runtimeOverride = null
        }
    }

    @Test fun pendingBatchIsProbedBeforePromotionAndRespectsTheCap() = runBlocking {
        val date = LocalDate.now().minusYears(25)
        val paths = collector.runtime.CollectorPaths(app.cacheDir.resolve("batch-probe/out"), app.cacheDir.resolve("batch-probe/config"))
        val probes = AtomicInteger()
        var ready = false
        val provider = object : BatchCapableProvider {
            override val id = ProviderId.ANTHROPIC
            override suspend fun listModels() = emptyList<ModelInfo>()
            override suspend fun complete(req: LlmRequest): LlmResult = error("Unexpected paid completion")
            override fun prepareBatch(requests: Map<String, LlmRequest>): suspend () -> BatchState =
                { BatchState("fixture-batch", false, false) }
            override suspend fun retrieveBatch(id: String): BatchState {
                probes.incrementAndGet()
                return BatchState(id, ready, false)
            }
            override suspend fun findBatches(since: Instant) = emptyList<BatchState>()
            override suspend fun cancelBatch(id: String): BatchState = error("Unexpected premature cancellation")
            override suspend fun batchResults(id: String, receive: (String, Result<LlmResult>?) -> Unit): Unit =
                error("Unexpected result download")
        }
        val source = object : SourceAdapter {
            override val id = "hf"
            override suspend fun fetch(config: SourceConfig, now: Instant) =
                listOf(Item(id, "1", "Model for testing", "https://example.invalid/model", 10, publishedAt = now))
        }
        paths.configDir.mkdirs()
        paths.sourcesConfig.writeText(ConfigLoader.json.encodeToString(SourcesFile(listOf(HfConfig()))))
        paths.pipelineConfig.writeText("""{"batch":{"classify":true,"generate":true}}""")
        val runtime = CollectorRuntime(paths, { null }, Clock.systemDefaultZone(), listOf(source), { listOf(provider) })
        app.runtimeOverride = runtime
        try {
            try {
                runtime.runScheduled(collector.runtime.ScheduledOptions(mail = false, batchOverride = true,
                    deferBatch = true, runDate = date))
                fail("Expected a durable Batch submission")
            } catch (_: collector.runtime.BatchDeferredException) {
                assertTrue(runtime.hasPendingBatch(date))
            }
            val worker = TestListenableWorkerBuilder<DeniedRunWorker>(app).build()
            worker.exempt = false
            val work = WorkManager.getInstance(app)
            val workName = "phone-batch-$date"
            try {
                worker.exempt = true
                val beforeProbe = probes.get()
                assertEquals(ListenableWorker.Result.success(), worker.runFor(date, resume = true, deferrals = 1, manual = true))
                assertEquals(beforeProbe + 1, probes.get())
                assertEquals(0, worker.promotions)
                ready = true
                worker.exempt = false
                assertEquals(ListenableWorker.Result.success(), worker.runFor(date, resume = true, deferrals = 2, manual = true))
                assertTrue(app.schedule.status().batteryExemptionRequired)
                assertTrue(work.getWorkInfosForUniqueWork(workName).get(10, TimeUnit.SECONDS).any { !it.state.isFinished })
            } finally {
                work.cancelUniqueWork(workName).result.get(10, TimeUnit.SECONDS)
                app.schedule.requireBatteryExemption(false)
            }
            ready = false
            assertEquals(ListenableWorker.Result.failure(), worker.runFor(date, resume = true, deferrals = 6, manual = true))
            assertTrue(probes.get() >= 1)
            assertEquals(0, worker.promotions)
            assertTrue(runtime.hasPendingBatch(date))
        } finally {
            app.getSharedPreferences("phone-run", android.content.Context.MODE_PRIVATE)
                .edit().remove("failure-$date").commit()
            app.runtimeOverride = null
        }
    }

    @Test fun automaticAttemptsAreCappedPerDate() {
        val date = LocalDate.now().minusYears(10)
        val prefs = app.getSharedPreferences("phone-run", android.content.Context.MODE_PRIVATE)
        try {
            assertTrue(app.schedule.beginAttempt(date))
            assertTrue(app.schedule.beginAttempt(date))
            assertFalse(app.schedule.beginAttempt(date))
            assertTrue(app.schedule.beginAttempt(date.plusDays(1)))
        } finally {
            prefs.edit().remove("attempts-$date").remove("attempts-${date.plusDays(1)}").commit()
        }
    }

    @Test fun cancelledBackupFinishesWithoutANotification() = runBlocking {
        val date = LocalDate.now().minusDays(200)
        val prefs = app.getSharedPreferences("phone-run", android.content.Context.MODE_PRIVATE)
        val manager = app.getSystemService(NotificationManager::class.java)
        fun notification() = manager.activeNotifications.singleOrNull { it.id == 32 && it.tag == null }?.postTime
        val before = notification()
        val worker = TestListenableWorkerBuilder<DeniedRunWorker>(app).build()
        app.schedule.cancelByUser(date)
        try {
            assertEquals(ListenableWorker.Result.success(), worker.runFor(date, fromBackup = true))
            assertEquals(0, worker.promotions)
            assertEquals(before, notification())
        } finally {
            prefs.edit().remove("cancelled-$date").commit()
        }
    }

    @Test fun recoveredBatchUsesKeepAndClearsItsDateNotificationAfterPublication() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val work = WorkManager.getInstance(app)
        val date = LocalDate.of(2090, 1, 1)
        val missingDate = date.plusDays(1)
        val manager = app.getSystemService(NotificationManager::class.java)
        val settings = app.paths.outDir.resolve("settings/schedule.json")
        val original = settings.takeIf { it.isFile }?.readBytes()
        val published = app.paths.outDir.resolve("$missingDate.json")
        val prefs = app.getSharedPreferences("phone-run", android.content.Context.MODE_PRIVATE)
        try {
            settings.parentFile?.mkdirs()
            settings.writeText("""{"enabled":true}""")
            app.schedule.recoverBatch(date)
            val first = work.getWorkInfosForUniqueWork("phone-batch-$date").get(10, TimeUnit.SECONDS).single()
            app.schedule.recoverBatch(date)
            val kept = work.getWorkInfosForUniqueWork("phone-batch-$date").get(10, TimeUnit.SECONDS).single()
            assertEquals(first.id, kept.id)
            assertFalse(manager.activeNotifications.any { it.tag == "batch-$date" })

            settings.writeText("""{"enabled":false}""")
            app.schedule.recoverBatch(missingDate)
            withTimeout(5_000) { while (manager.activeNotifications.none { it.tag == "batch-$missingDate" }) delay(25) }
            manager.cancel("batch-$missingDate", 32)
            withTimeout(5_000) { while (manager.activeNotifications.any { it.tag == "batch-$missingDate" }) delay(25) }
            app.schedule.recoverBatch(missingDate)
            assertFalse(manager.activeNotifications.any { it.tag == "batch-$missingDate" })
            RunNotifications.unpublishedBatch(app, missingDate)
            withTimeout(5_000) { while (manager.activeNotifications.none { it.tag == "batch-$missingDate" }) delay(25) }
            published.writeText("{}")
            app.schedule.recoverBatch(missingDate)
            withTimeout(5_000) { while (manager.activeNotifications.any { it.tag == "batch-$missingDate" }) delay(25) }
        } finally {
            work.cancelUniqueWork("phone-batch-$date").result.get(10, TimeUnit.SECONDS)
            published.delete()
            manager.cancel("batch-$missingDate", 32)
            prefs.edit().remove("batch-unpublished-notified-$missingDate").commit()
            if (original == null) settings.delete() else settings.writeBytes(original)
        }
    }

    @Test fun alarmReregistersAndBackupDriverStartsWork() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val work = WorkManager.getInstance(app)
        val previous = app.schedule.options()
        val calendar = ZonedDateTime.now()
        val sourceCalls = AtomicInteger()
        val day = backupRunDate(calendar, calendar.minusHours(2).toLocalTime())
        val request = InboxRequest.SettingsSet(1, UUID.randomUUID().toString(), Instant.now().toString(),
            schedule = collector.data.ScheduleSettings(true))
        val releaseCollection = kotlinx.coroutines.CompletableDeferred<Unit>()
        val fixture = File(app.cacheDir, "backup-worker-${request.requestId}")
        val paths = collector.runtime.CollectorPaths(fixture.resolve("out"), fixture.resolve("config"))
        val source = object : SourceAdapter {
            override val id = "hf"
            override suspend fun fetch(config: SourceConfig, now: Instant): List<Item> {
                sourceCalls.incrementAndGet()
                releaseCollection.await()
                return emptyList()
            }
        }
        paths.configDir.mkdirs()
        paths.sourcesConfig.writeText(ConfigLoader.json.encodeToString(SourcesFile(listOf(HfConfig()))))
        app.runtimeOverride = CollectorRuntime(paths, { null }, Clock.systemDefaultZone(), listOf(source), { emptyList() })
        val prefs = app.getSharedPreferences("phone-run", android.content.Context.MODE_PRIVATE)
        val original = prefs.all
        prefs.edit().clear().commit()
        val report = app.paths.outDir.resolve("$day.json")
        val previousReport = report.takeIf { it.isFile }?.readBytes()
        report.delete()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val exempt = app.schedule.ignoringBatteryOptimizations()
        fun whitelist(action: String) {
            instrumentation.uiAutomation.executeShellCommand("cmd deviceidle whitelist $action${app.packageName}")
                .use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor.dup()).use { it.readBytes() } }
        }
        if (!exempt) whitelist("+")
        app.schedule.save(previous.copy(time = LocalTime.of(7, 0)))
        try {
            assertNotNull(android.app.PendingIntent.getBroadcast(app, 0,
                android.content.Intent(app, RunAlarmReceiver::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_NO_CREATE))
            app.schedule.save(previous.copy(time = LocalTime.of(23, 0)))
            assertEquals(LocalTime.of(23, 0), app.schedule.status(calendar).next?.toLocalTime())
            for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED)) {
                RunAlarmReceiver().handle(app, Intent(action).putExtra("date", day.toString()))
            }
            assertEquals(0, sourceCalls.get())
            app.schedule.save(previous.copy(time = calendar.minusHours(2).toLocalTime()))
            val info = work.getWorkInfosForUniqueWork(RunScheduler.BACKUP).get(10, TimeUnit.SECONDS).single { !it.state.isFinished }
            app.database.dao().enqueueLocalChange(PendingLocalChange(
                json = contractJson.encodeToString<List<InboxRequest>>(listOf(request))))
            val driver = requireNotNull(WorkManagerTestInitHelper.getTestDriver(app))
            driver.setInitialDelayMet(info.id)
            driver.setPeriodDelayMet(info.id)
            withTimeout(15_000) { while (sourceCalls.get() == 0) delay(25) }
            val updated = previous.copy(time = LocalTime.of(18, 30))
            app.schedule.save(updated)
            assertEquals(androidx.work.WorkInfo.State.RUNNING,
                work.getWorkInfoById(info.id).get(10, TimeUnit.SECONDS)?.state)
            releaseCollection.complete(Unit)
            withTimeout(15_000) {
                while (!app.schedule.recorded(day) || app.database.dao().localChanges().isNotEmpty()) delay(25)
            }
            withTimeout(15_000) { while (app.schedule.hasPendingUpdate()) delay(25) }
            assertEquals(LocalTime.of(18, 30), app.schedule.status().next?.toLocalTime())
            assertEquals(1, sourceCalls.get())
            assertTrue(paths.outDir.resolve("$day.json").isFile)
            assertTrue(app.database.dao().localChanges().isEmpty())
            val refreshed = work.getWorkInfosForUniqueWork(RunScheduler.BACKUP)
                .get(10, TimeUnit.SECONDS).single { !it.state.isFinished }
            assertEquals(info.id, refreshed.id)
            assertTrue(refreshed.generation > info.generation)
        } finally {
            releaseCollection.complete(Unit)
            app.runtimeOverride = null
            if (previousReport != null) report.writeBytes(previousReport)
            val restore = prefs.edit().clear()
            for ((key, value) in original) when (value) {
                is Boolean -> restore.putBoolean(key, value)
                is Int -> restore.putInt(key, value)
                is String -> restore.putString(key, value)
            }
            restore.commit()
            instrumentation.runOnMainSync { activity.finish() }
            app.schedule.save(previous)
            if (!exempt) whitelist("-")
        }
    }
}

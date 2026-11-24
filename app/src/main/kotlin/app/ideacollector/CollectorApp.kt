package app.ideacollector

import android.app.Application
import androidx.room.Room
import app.ideacollector.data.*
import app.ideacollector.secret.KeystoreSecretProvider
import app.ideacollector.work.CollectorRunService
import app.ideacollector.work.RunScheduler
import app.ideacollector.work.RunNotifications
import app.ideacollector.github.contractJson
import collector.runtime.RunLockedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import collector.data.InboxRequest
import collector.runtime.CollectorPaths
import collector.runtime.CollectorRuntime
import collector.runtime.BatchDeferredException
import collector.runtime.ScheduledOptions
import collector.runtime.RunStage
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock

class CollectorApp : Application() {
    internal val database by lazy { Room.databaseBuilder(this, AppDatabase::class.java, "collector.db")
        .addMigrations(requestMetadataMigration, partialSyncMigration, snapshotSyncMigration, dailyDispatchMigration,
            connectionStateMigration, phoneRunMigration, localChangeFailureMigration).build() }
    internal val secrets by lazy { KeystoreSecretProvider(this) }
    internal val paths by lazy {
        CollectorPaths(filesDir.resolve("collector/out"), filesDir.resolve("collector/config"), cacheDir.resolve("collector"))
    }
    internal val sync by lazy { RepositorySync(database, paths) }
    internal val schedule by lazy { RunScheduler(this) }
    private val localChangesLock = Mutex()
    internal val pendingLocalChanges get() = database.dao().observePendingLocalChanges()
    internal val failedLocalChanges get() = database.dao().observeFailedLocalChanges()
    internal var runtimeOverride: CollectorRuntime? = null

    internal suspend fun runtime(): CollectorRuntime = withContext(Dispatchers.IO) {
        runtimeOverride ?: run {
            val current = settings()
            val values = listOf("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GITHUB_TOKEN", "SMTP_USERNAME",
                "SMTP_PASSWORD", "MAIL_TO", "PRODUCTHUNT_TOKEN", "REDDIT_CLIENT_ID", "REDDIT_CLIENT_SECRET")
                .associateWith { secrets.get(it) }.toMutableMap()
            if (values["GITHUB_TOKEN"] == null && current.tokenName != "GITHUB_TOKEN") {
                values["GITHUB_TOKEN"] = secrets.get(current.tokenName)
            }
            CollectorRuntime(paths, { values[it] }, Clock.systemDefaultZone())
        }
    }

    internal suspend fun canRunDaily() = runtimeOverride != null ||
        secrets.has("ANTHROPIC_API_KEY") || secrets.has("OPENAI_API_KEY")

    internal suspend fun executeDaily(date: LocalDate, resume: Boolean, automatic: Boolean,
        deferrals: Int = 0, onStage: (RunStage) -> Unit = {}): collector.runtime.ScheduledSummary? = withContext(Dispatchers.IO) {
        val runtime = runtime()
        fun markFailure() {
            val paid = runCatching { runtime.dailyCost(date) > 0 }.getOrDefault(true)
            schedule.failure(date, paid)
        }
        try {
            runtime.runScheduled(ScheduledOptions(mail = true, batchOverrideForRun = { resume || schedule.options().batch },
                deadline = Duration.ofMinutes(60), onStage = onStage, deferBatch = deferrals < 6,
                honorSchedule = automatic, runDate = date, beforeRun = { flushLocalChanges(it) },
                resumeBatch = resume, onUnpublishedBatch = { previous ->
                    schedule.recoverBatch(previous)
                }, onMailRetriesExpired = { count -> RunNotifications.expiredMail(this@CollectorApp, count) })).also {
                if (it.daily?.published == true) RunNotifications.clearUnpublishedBatch(this@CollectorApp, date)
                else if (it.exitCode != 0) RunNotifications.previousMailFailure(this@CollectorApp, it)
                if (!it.skipped) {
                    if (it.exitCode == 0) schedule.record(date) else markFailure()
                }
            }
        } catch (_: BatchDeferredException) {
            if (runtime.hasPendingBatch(date) && deferrals < 6) {
                schedule.continueBatch(date, deferrals + 1, manual = !automatic)
                null
            } else {
                markFailure()
                RunNotifications.result(this@CollectorApp, R.string.run_failed)
                collector.runtime.ScheduledSummary(1, null)
            }
        } catch (e: RunLockedException) {
            throw e
        } catch (e: CancellationException) {
            // A cancelled paid run can have a durable reservation but no published result.
            val paid = runCatching { runtime.dailyCost(date) > 0 }.getOrDefault(true)
            if (paid) schedule.blockPaidRetry(date)
            if (!schedule.cancelled(date)) {
                if (runtime.paths.outDir.resolve("$date.json").isFile) {
                    RunNotifications.clearUnpublishedBatch(this@CollectorApp, date)
                    when (runtime.scheduledMailStatus(date)) {
                        collector.runtime.ScheduledMailStatus.PENDING ->
                            RunNotifications.result(this@CollectorApp, R.string.run_mail_pending)
                        collector.runtime.ScheduledMailStatus.FAILED ->
                            RunNotifications.result(this@CollectorApp, R.string.run_mail_failed)
                        collector.runtime.ScheduledMailStatus.SENT -> Unit
                    }
                } else if (paid) schedule.notifyRetryBlocked(date)
            }
            throw e
        } catch (e: Exception) {
            markFailure()
            throw e
        }
    }

    internal suspend fun finishRun() {
        runCatching { flushLocalChanges(deferScheduleUpdate = true) }
        runCatching { schedule.finishScheduleUpdate() }
        runCatching { sync.refresh() }
        runCatching { schedule.releaseReservation() }
    }

    override fun onCreate() {
        super.onCreate()
        val work = androidx.work.WorkManager.getInstance(this)
        listOf("periodic-sync", "manual-sync", "outbox").forEach { work.cancelUniqueWork(it) }
    }

    internal suspend fun settings() = database.dao().settings() ?: AppSettings()

    internal suspend fun saveLocalConfig(name: String, json: String) {
        saveChange(PendingLocalChange(configName = name, json = json))
    }

    internal suspend fun estimateAnalysis(themes: List<String>) = withContext(Dispatchers.IO) {
        runtime().estimateAnalysis(themes)
    }

    internal fun startAnalysis(themes: List<String>, limit: Double) =
        CollectorRunService.startAnalysis(this, themes, limit)

    internal suspend fun submit(requests: List<InboxRequest>) =
        saveChange(PendingLocalChange(json = contractJson.encodeToString(requests)))

    private suspend fun saveChange(change: PendingLocalChange) = withContext(Dispatchers.IO) {
        localChangesLock.withLock {
            val dao = database.dao()
            if (CollectorRunService.running.value || dao.localChanges().isNotEmpty()) {
                dao.enqueueLocalChange(change)
            } else {
                try {
                    applyChange(change)
                } catch (_: RunLockedException) {
                    dao.enqueueLocalChange(change)
                }
            }
        }
        if (!CollectorRunService.running.value) flushLocalChanges()
        sync.refresh()
    }

    private suspend fun flushLocalChanges(
        editor: CollectorRuntime.LocalEditor? = null, deferScheduleUpdate: Boolean = editor != null
    ) = withContext(Dispatchers.IO) {
        localChangesLock.withLock {
            val dao = database.dao()
            for (change in dao.localChanges()) {
                try {
                    applyChange(change, editor, deferScheduleUpdate)
                    dao.removeLocalChange(change.id)
                } catch (_: RunLockedException) {
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    dao.failLocalChange(change.id)
                }
            }
        }
    }

    internal suspend fun flushLocalChanges() = flushLocalChanges(null)

    private suspend fun applyChange(
        change: PendingLocalChange, editor: CollectorRuntime.LocalEditor? = null, deferScheduleUpdate: Boolean = false
    ) {
        if (change.configName != null) {
            if (editor == null) runtime().saveLocalConfig(change.configName, change.json)
            else editor.saveConfig(change.configName, change.json)
        } else {
            val requests = contractJson.decodeFromString<List<InboxRequest>>(change.json)
            if (editor == null) runtime().applyLocalChanges(requests) else editor.applyChanges(requests)
            if (requests.any { it is InboxRequest.SettingsSet }) {
                if (deferScheduleUpdate) schedule.deferScheduleUpdate()
                else schedule.finishScheduleUpdate(force = true)
            }
        }
    }
}

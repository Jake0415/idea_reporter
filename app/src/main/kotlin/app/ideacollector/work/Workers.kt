package app.ideacollector.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.work.*
import app.ideacollector.CollectorApp
import app.ideacollector.R
import app.ideacollector.github.contractJson
import collector.data.ScheduleSettings
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private fun todayAt(now: ZonedDateTime, time: LocalTime) = now.toLocalDate().atTime(time).atZone(now.zone)
internal fun nextRun(now: ZonedDateTime, time: LocalTime): ZonedDateTime =
    todayAt(now, time).takeIf { it > now } ?: now.toLocalDate().plusDays(1).atTime(time).atZone(now.zone)
internal fun nextBackup(now: ZonedDateTime, time: LocalTime): ZonedDateTime =
    nextRun(now.minusHours(2), time).plusHours(2)
internal fun backupRunDate(now: ZonedDateTime, time: LocalTime): LocalDate {
    val beforeBackup = now.minusHours(2)
    return beforeBackup.toLocalDate().let { if (todayAt(beforeBackup, time) > beforeBackup) it.minusDays(1) else it }
}
internal fun runMissed(now: ZonedDateTime, time: LocalTime, recorded: Boolean) =
    !recorded && now >= todayAt(now, time).plusHours(3)
internal fun validRunDate(now: ZonedDateTime, date: LocalDate) = now.toLocalDate() == date
internal fun validBackupRunDate(now: ZonedDateTime, date: LocalDate, time: LocalTime) =
    validRunDate(now, date) || (time >= LocalTime.of(22, 0) &&
        date == now.toLocalDate().minusDays(1) && date == backupRunDate(now, time))

internal data class RunOptions(
    val time: LocalTime = LocalTime.of(7, 0),
    val wifiOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    val batch: Boolean = false
)
internal fun conditionsMet(options: RunOptions, wifi: Boolean, charging: Boolean) =
    (!options.wifiOnly || wifi) && (!options.chargingOnly || charging)

internal data class RunStatus(
    val next: ZonedDateTime?, val exact: Boolean, val ignoringBatteryOptimizations: Boolean, val missed: Boolean,
    val batteryExemptionRequired: Boolean, val pendingReservation: Boolean
)

internal class RunScheduler(private val app: CollectorApp) {
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val work get() = WorkManager.getInstance(app)
    private val preferences = app.getSharedPreferences("phone-run", Context.MODE_PRIVATE)
    private val reservationRelease = Mutex()
    private val scheduleUpdates = Mutex()

    suspend fun options(): RunOptions = app.settings().let {
        RunOptions(LocalTime.ofSecondOfDay(it.runTimeSeconds), it.wifiOnly, it.chargingOnly, it.batch)
    }

    suspend fun save(options: RunOptions) = withContext(Dispatchers.IO) {
        scheduleUpdates.withLock {
            app.database.dao().saveSettings(app.settings().copy(runTimeSeconds = options.time.toSecondOfDay().toLong(),
                wifiOnly = options.wifiOnly, chargingOnly = options.chargingOnly, batch = options.batch))
            markScheduleUpdatePending()
        }
        finishScheduleUpdate()
    }

    suspend fun deferScheduleUpdate() = scheduleUpdates.withLock { markScheduleUpdatePending() }

    private fun markScheduleUpdatePending() {
        preferences.edit().putBoolean("schedule-update-pending", true).commit()
    }

    fun hasPendingUpdate() = preferences.getBoolean("schedule-update-pending", false)

    suspend fun finishScheduleUpdate(force: Boolean = false) = scheduleUpdates.withLock {
        if (force) markScheduleUpdatePending()
        if (!hasPendingUpdate() || CollectorRunService.running.value) return@withLock
        try {
            app.runtime().withRunLock {
                if (CollectorRunService.running.value) return@withRunLock
                register(update = true, keepRunning = true)
                preferences.edit().remove("schedule-update-pending").commit()
            }
        } catch (_: collector.runtime.RunLockedException) {
            // The running job will apply the saved schedule when it finishes.
        }
    }

    fun enabled(): Boolean {
        val file = app.paths.outDir.resolve("settings/schedule.json")
        return !file.isFile || runCatching { contractJson.decodeFromString<ScheduleSettings>(file.readText()).enabled }
            .getOrDefault(false)
    }

    fun recorded(date: LocalDate) = preferences.getBoolean("success-$date", false) ||
        preferences.getString("last-run", null) == date.toString() || app.paths.outDir.resolve("$date.json").isFile

    fun record(date: LocalDate) {
        preferences.edit().putBoolean("success-$date", true).remove("failure-$date").remove("paid-retry-block-$date")
            .remove("cancelled-$date").remove("system-cancelled-$date").commit()
        RunNotifications.clearUnpublishedBatch(app, date)
    }

    fun failure(date: LocalDate, paid: Boolean) {
        preferences.edit().putString("failure-$date", if (paid) "paid" else "unpaid").commit()
    }

    /** 게시 직후 프로세스가 끝나 지우지 못한 "Batch 결과 미게시" 알림을, 이미 게시된 날짜에 한해 정리한다. */
    fun clearPublishedBatchNotices() {
        val prefix = "batch-unpublished-notified-"
        preferences.all.keys.filter { it.startsWith(prefix) }.forEach { key ->
            val date = runCatching { LocalDate.parse(key.removePrefix(prefix)) }.getOrNull() ?: return@forEach
            if (recorded(date)) RunNotifications.clearUnpublishedBatch(app, date)
        }
    }

    fun markBatchExpired(date: LocalDate) {
        preferences.edit().putBoolean("batch-expired-$date", true).commit()
    }

    fun paidFailure(date: LocalDate) =preferences.getString("failure-$date", null) == "paid"

    fun blockPaidRetry(date: LocalDate) {
        preferences.edit().putBoolean("paid-retry-block-$date", true).commit()
    }

    @Synchronized fun cancelByUser(date: LocalDate) {
        if (recorded(date)) return
        preferences.edit().putBoolean("cancelled-$date", true).remove("system-cancelled-$date").commit()
    }

    fun clearCancellation(date: LocalDate) {
        preferences.edit().remove("cancelled-$date").commit()
    }

    @Synchronized fun systemCancelled(date: LocalDate) {
        if (!cancelled(date)) preferences.edit().putBoolean("system-cancelled-$date", true).commit()
    }

    fun cancelled(date: LocalDate) = preferences.getBoolean("cancelled-$date", false)

    fun mayRetry(date: LocalDate) = !cancelled(date) && !paidFailure(date) && !preferences.getBoolean("paid-retry-block-$date", false)

    suspend fun canRunNew(date: LocalDate): Boolean {
        if (!enabled() || recorded(date) || cancelled(date)) return false
        if (!mayRetry(date)) {
            notifyRetryBlocked(date)
            return false
        }
        val runtime = app.runtime()
        if (runtime.hasBatchToResume(date)) return false
        if (runCatching { runtime.dailyCost(date) <= 0 }.getOrDefault(false)) return true
        failure(date, paid = true)
        notifyRetryBlocked(date)
        return false
    }

    @Synchronized fun beginAttempt(date: LocalDate): Boolean {
        val key = "attempts-$date"
        val attempts = preferences.getInt(key, 0)
        return attempts < 2 && preferences.edit().putInt(key, attempts + 1).commit()
    }

    @Synchronized fun refundAttempt(date: LocalDate) {
        val key = "attempts-$date"
        preferences.edit().putInt(key, (preferences.getInt(key, 0) - 1).coerceAtLeast(0)).commit()
    }

    fun ignoringBatteryOptimizations() =
        app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)

    fun requireBatteryExemption(required: Boolean) {
        preferences.edit().putBoolean("battery-exemption-required", required).apply()
        RunNotifications.batteryExemption(app, required)
    }

    fun clearBatteryExemptionIfGranted() {
        if (ignoringBatteryOptimizations()) requireBatteryExemption(false)
    }

    @Synchronized fun notifyRetryBlocked(date: LocalDate) {
        if (recorded(date) || cancelled(date) || preferences.getBoolean("retry-block-notified-$date", false)) return
        preferences.edit().putBoolean("retry-block-notified-$date", true).commit()
        RunNotifications.result(app, R.string.run_failed)
    }

    suspend fun eligible(date: LocalDate, resume: Boolean, manual: Boolean = false): Boolean {
        if ((!manual && !enabled()) || cancelled(date)) return false
        if (resume) return !recorded(date) && app.runtime().hasBatchToResume(date)
        return validRunDate(ZonedDateTime.now(), date) && canRunNew(date)
    }

    @Synchronized fun pendingReservation(date: LocalDate) {
        preferences.edit().putString("pending-reservation", date.toString()).commit()
    }

    fun pendingReservation(): LocalDate? = preferences.getString("pending-reservation", null)?.let(LocalDate::parse)

    suspend fun holdReservation(date: LocalDate) {
        pendingReservation(date)
        releaseReservation()
    }

    suspend fun releaseReservation() {
        reservationRelease.withLock {
            if (CollectorRunService.running.value) return
            val date = pendingReservation() ?: return
            if (!validRunDate(ZonedDateTime.now(), date)) notifyMissed(date)
            else if (enabled() && !recorded(date) && !cancelled(date)) enqueueDeferred(date, false)
            synchronized(this) {
                if (pendingReservation() == date) preferences.edit().remove("pending-reservation").commit()
            }
        }
    }

    fun notifyMissed(date: LocalDate) {
        if (!enabled() || recorded(date)) return
        preferences.edit().putString("skipped-date", date.toString()).apply()
        RunNotifications.result(app, R.string.run_missed)
    }

    private fun exact() = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    suspend fun status(now: ZonedDateTime = ZonedDateTime.now()): RunStatus {
        val enabled = enabled()
        val time = options().time
        val date = now.toLocalDate()
        return RunStatus(nextRun(now, time).takeIf { enabled }, exact(),
            ignoringBatteryOptimizations(),
            enabled && !recorded(date) && (!mayRetry(date) ||
                preferences.getBoolean("system-cancelled-$date", false) || runMissed(now, time, false)),
            preferences.getBoolean("battery-exemption-required", false), pendingReservation() != null)
    }

    fun requestBatteryExemption() = openBatterySettings(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${app.packageName}")))
    fun openSamsungBatterySettings() = openBatterySettings(Intent().setClassName(
        "com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"))

    private fun openBatterySettings(intent: Intent) {
        try {
            app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: android.content.ActivityNotFoundException) {
            app.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: SecurityException) {
            app.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    suspend fun register(now: ZonedDateTime = ZonedDateTime.now(), update: Boolean = false, keepRunning: Boolean = false) {
        val time = options().time
        val next = nextRun(now, time)
        val alarm = PendingIntent.getBroadcast(app, 0, Intent(app, RunAlarmReceiver::class.java)
            .putExtra("date", next.toLocalDate().toString()), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (!enabled()) {
            alarms.cancel(alarm)
            // 실행 중(서비스·워커 공용 플래그)이면 예비 작업을 지금 취소하지 않고, 실행이 끝난 뒤 finishRun에서 반영한다.
            if (keepRunning) return
            if (CollectorRunService.running.value) {
                markScheduleUpdatePending()
                return
            }
            work.cancelUniqueWork(BACKUP)
            return
        }
        val at = next.toInstant().toEpochMilli()
        try {
            if (exact()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm)
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm)
        }
        val backup = nextBackup(now, time)
        val delay = Duration.between(now, backup).toMillis()
        val policy = when {
            update -> ExistingPeriodicWorkPolicy.UPDATE
            else -> ExistingPeriodicWorkPolicy.KEEP
        }
        val request = PeriodicWorkRequestBuilder<BackupRunWorker>(1, TimeUnit.DAYS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
        if (update) request.setNextScheduleTimeOverride(backup.toInstant().toEpochMilli())
        work.enqueueUniquePeriodicWork(BACKUP, policy, request.build()).await()
    }

    suspend fun dispatch(date: LocalDate, startService: () -> Boolean = {
        exact() && CollectorRunService.startDaily(app, date)
    }) {
        val now = ZonedDateTime.now()
        if (!validRunDate(now, date)) {
            notifyMissed(date)
            return
        }
        if (!enabled() || recorded(date)) return
        if (CollectorRunService.running.value) {
            holdReservation(date)
            return
        }
        val options = options()
        if (!conditionsMet(options) || !startService()) {
            enqueueDeferred(date, false)
        }
    }

    suspend fun enqueueDeferred(date: LocalDate, fromBackup: Boolean) {
        val name = if (fromBackup) "phone-backup-conditions-$date" else "phone-conditions-$date"
        work.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DeferredRunWorker>()
                .setInputData(workDataOf("date" to date.toString(), "backup" to fromBackup))
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
                .setConstraints(constraints(options())).build()).await()
    }

    private fun constraints(options: RunOptions) = Constraints.Builder()
        .setRequiredNetworkType(if (options.wifiOnly) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED)
        .setRequiresCharging(options.chargingOnly).build()

    internal fun conditionsMet(options: RunOptions): Boolean {
        val network = app.getSystemService(ConnectivityManager::class.java)
        val wifi = network.getNetworkCapabilities(network.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        val charging = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } == true
        return conditionsMet(options, wifi, charging)
    }

    suspend fun recoverBatch(date: LocalDate) {
        if (recorded(date)) {
            RunNotifications.clearUnpublishedBatch(app, date)
            return
        }
        // 이어받기 상한(6회)으로 실패 처리된 날이라도, 나중에 결과가 정산돼 재사용할 수 있으면 다시 이어받는다.
        val batchExpired = preferences.getBoolean("batch-expired-$date", false)
        if (cancelled(date) || (preferences.contains("failure-$date") && !batchExpired) ||
            app.runtime().hasTerminalDailyRecord(date)) return
        if (batchExpired && !app.runtime().hasBatchToResume(date)) return
        try {
            if (enabled()) continueBatch(date, 0, manual = false, policy = ExistingWorkPolicy.KEEP)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Confirm whether another worker still owns the continuation before notifying.
        }
        val workers = try {
            work.getWorkInfosForUniqueWorkFlow("phone-batch-$date").first()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        if (!recorded(date) && workers.none { !it.state.isFinished } &&
            !preferences.getBoolean("batch-unpublished-notified-$date", false)) {
            preferences.edit().putBoolean("batch-unpublished-notified-$date", true).commit()
            RunNotifications.unpublishedBatch(app, date)
        }
    }

    suspend fun continueBatch(date: LocalDate, deferrals: Int, manual: Boolean,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) {
        work.enqueueUniqueWork("phone-batch-$date", policy,
            OneTimeWorkRequestBuilder<DeferredRunWorker>()
                .setInputData(workDataOf("date" to date.toString(), "batch" to true, "deferrals" to deferrals, "manual" to manual))
                .setConstraints(constraints(options()))
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
                .setInitialDelay(30, TimeUnit.MINUTES).build()).await()
    }

    companion object { const val BACKUP = "phone-backup" }
}

class RunAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as CollectorApp
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { handle(app, intent) } finally { pending.finish() }
        }
    }
    internal suspend fun handle(app: CollectorApp, intent: Intent) {
        val date = if (intent.action == null) intent.getStringExtra("date")?.let(LocalDate::parse) else null
        try {
            if (date != null) app.schedule.dispatch(date)
        } finally {
            app.schedule.register(update = date == null)
        }
    }
}

abstract class AutomaticRunWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    protected val app get() = applicationContext as CollectorApp

    protected open suspend fun promote(info: ForegroundInfo) = setForeground(info)
    protected open fun canPromote() = Build.VERSION.SDK_INT < 31 || app.schedule.ignoringBatteryOptimizations()

    protected suspend fun execute(date: LocalDate, resume: Boolean = false, deferrals: Int = 0,
        fromBackup: Boolean = false, manual: Boolean = false): Result {
        val scheduler = app.schedule
        if (!CollectorRunService.running.compareAndSet(expect = false, update = true)) return Result.retry()
        var started = false
        var chargedAttempt = false
        return try {
            if (scheduler.cancelled(date)) return Result.success()
            val eligible = if (fromBackup && !resume) {
                if (!validBackupRunDate(ZonedDateTime.now(), date, scheduler.options().time)) {
                    scheduler.notifyMissed(date)
                    return Result.success()
                }
                scheduler.canRunNew(date)
            } else scheduler.eligible(date, resume, manual)
            if (!eligible) return Result.success()
            if (!app.canRunDaily()) {
                RunNotifications.result(app, R.string.run_missing_key)
                return Result.failure()
            }
            suspend fun deferOrFinishBatch(): Result {
                if (deferrals < 6) {
                    scheduler.continueBatch(date, deferrals + 1, manual)
                    return Result.success()
                }
                scheduler.failure(date, paid = true)
                scheduler.markBatchExpired(date)
                RunNotifications.result(app, R.string.run_batch_unapplied)
                app.runtime().maintainPendingBatch(date)
                return Result.failure()
            }

            if (resume && app.runtime().batchInProgress(date)) return deferOrFinishBatch()
            suspend fun batteryExemptionRequired(): Result {
                scheduler.requireBatteryExemption(true)
                return if (resume) deferOrFinishBatch() else Result.failure()
            }
            if (!canPromote()) return batteryExemptionRequired()
            CancelRunWorkerReceiver.active.value = id to date
            val cancel = PendingIntent.getBroadcast(app, 0,
                Intent(app, CancelRunWorkerReceiver::class.java).setData(Uri.parse("phone-run:$id/$date"))
                    .putExtra("date", date.toString()).putExtra("id", id.toString()),
                PendingIntent.FLAG_IMMUTABLE)
            try {
                val progress = RunNotifications.progress(app, app.getString(R.string.run_collecting), cancel)
                promote(ForegroundInfo(33, progress, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
                scheduler.requireBatteryExemption(false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: IllegalStateException) {
                return batteryExemptionRequired()
            } catch (_: SecurityException) {
                return batteryExemptionRequired()
            }
            currentCoroutineContext().ensureActive()
            if (!resume && !scheduler.beginAttempt(date)) {
                RunNotifications.result(app, R.string.run_attempt_limit)
                return Result.failure()
            }
            chargedAttempt = !resume
            started = true
            val summary = app.executeDaily(date, resume, automatic = !manual, deferrals = deferrals) { stage ->
                RunNotifications.stage(app, 33, stage, cancel)
            }
            if (summary == null || summary.skipped) Result.success()
            else if (summary.exitCode == 0 || summary.daily?.published == true) {
                val tracking = runCatching { app.sync.trackingUpdates(date) }.getOrDefault(0)
                RunNotifications.dailyResult(app, summary, tracking)
                Result.success()
            } else if (scheduler.paidFailure(date) || resume) {
                val message = if (resume && deferrals >= 6) R.string.run_batch_unapplied else R.string.run_failed
                if (message == R.string.run_failed && scheduler.paidFailure(date)) scheduler.notifyRetryBlocked(date)
                else RunNotifications.result(app, message)
                Result.failure()
            } else {
                RunNotifications.result(app, R.string.run_failed)
                Result.retry()
            }
        } catch (_: collector.runtime.RunLockedException) {
            if (chargedAttempt) scheduler.refundAttempt(date)
            Result.retry()
        } catch (e: CancellationException) {
            if (started) scheduler.systemCancelled(date)
            throw e
        } catch (_: Exception) {
            RunNotifications.result(app, R.string.run_failed)
            if (scheduler.paidFailure(date) || resume) Result.failure() else Result.retry()
        } finally {
            CancelRunWorkerReceiver.active.value = null
            CollectorRunService.running.value = false
            withContext(NonCancellable) {
                if (started) app.finishRun() else {
                    runCatching { scheduler.finishScheduleUpdate() }
                    runCatching { scheduler.releaseReservation() }
                }
            }
        }
    }
}

class CancelRunWorkerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val date = intent.getStringExtra("date")?.let(LocalDate::parse) ?: return
        val id = intent.getStringExtra("id")?.let(java.util.UUID::fromString) ?: return
        if (active.value != (id to date)) return
        val app = context.applicationContext as CollectorApp
        app.schedule.cancelByUser(date)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WorkManager.getInstance(app).cancelWorkById(id).await()
                app.schedule.register()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        internal val active = kotlinx.coroutines.flow.MutableStateFlow<Pair<java.util.UUID, LocalDate>?>(null)
    }
}

class BackupRunWorker(context: Context, params: WorkerParameters) : AutomaticRunWorker(context, params) {
    override suspend fun doWork(): Result {
        val scheduler = app.schedule
        scheduler.clearBatteryExemptionIfGranted()
        scheduler.register()
        if (CollectorRunService.running.value) return Result.retry()
        val now = ZonedDateTime.now()
        val options = scheduler.options()
        val date = backupRunDate(now, options.time)
        if (scheduler.cancelled(date)) return Result.success()
        if (!validBackupRunDate(now, date, options.time)) {
            scheduler.notifyMissed(date)
            return Result.success()
        }
        if (!scheduler.conditionsMet(options)) {
            scheduler.enqueueDeferred(date, true)
            return Result.success()
        }
        return execute(date, fromBackup = true)
    }
}

class DeferredRunWorker(context: Context, params: WorkerParameters) : AutomaticRunWorker(context, params) {
    override suspend fun doWork(): Result {
        app.schedule.clearBatteryExemptionIfGranted()
        val date = inputData.getString("date")?.let(LocalDate::parse) ?: return Result.failure()
        if (app.schedule.cancelled(date)) return Result.success()
        val resume = inputData.getBoolean("batch", false)
        val backup = inputData.getBoolean("backup", false)
        val now = ZonedDateTime.now()
        val options = app.schedule.options()
        if (!resume && !(if (backup) validBackupRunDate(now, date, options.time) else validRunDate(now, date))) {
            app.schedule.notifyMissed(date)
            return Result.success()
        }
        if (!app.schedule.conditionsMet(options)) return Result.retry()
        return execute(date, resume, inputData.getInt("deferrals", 0), fromBackup = backup,
            manual = inputData.getBoolean("manual", false))
    }
}

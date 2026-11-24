package app.ideacollector.work

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.widget.Toast
import androidx.core.content.ContextCompat
import app.ideacollector.CollectorApp
import app.ideacollector.R
import collector.runtime.RunLockedException
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class CollectorRunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as CollectorApp

    private var foreground = false
    private var latestStartId = 0
    private var runJob: Job? = null
    private var activeDailyDate: LocalDate? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == CANCEL) {
            if (runJob?.isActive == true) {
                activeDailyDate?.let(app.schedule::cancelByUser)
                runJob?.cancel()
            }
            if (!foreground) stopSelf(latestStartId)
            return START_NOT_STICKY
        }
        runCatching { app.schedule.clearBatteryExemptionIfGranted() }
        RunNotifications.channel(this)
        if (!running.compareAndSet(expect = false, update = true)) {
            intent?.getStringExtra("date")?.let(LocalDate::parse)?.let { date ->
                scope.launch { runCatching { app.schedule.holdReservation(date) } }
            }
            alreadyRunning()
            if (!foreground) {
                if (promote(RunNotifications.notification(this, getString(R.string.run_already_running)).build())) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
                stopSelf(latestStartId)
            }
            return START_NOT_STICKY
        }
        val themes = intent?.getStringArrayListExtra("themes")
        val date = intent?.getStringExtra("date")?.let(LocalDate::parse) ?: LocalDate.now()
        val resume = intent?.getBooleanExtra("resume", false) == true
        val automatic = intent?.hasExtra("date") == true
        val initial = if (themes == null) R.string.run_collecting else R.string.run_analyzing
        val cancel = PendingIntent.getService(this, 0, Intent(this, CollectorRunService::class.java).setAction(CANCEL),
            PendingIntent.FLAG_IMMUTABLE)
        if (!promote(RunNotifications.progress(this, getString(initial), cancel))) {
            if (automatic && !resume) app.schedule.pendingReservation(date)
            running.value = false
            scope.launch {
                try {
                    app.schedule.releaseReservation()
                } finally {
                    if (!foreground) stopSelf(latestStartId)
                }
            }
            return START_NOT_STICKY
        }
        foreground = true
        activeDailyDate = date.takeIf { themes == null }
        runJob = scope.launch {
            var chargedAttempt = false
            var successful = false
            var result: String? = null
            try {
                if (automatic && withContext(Dispatchers.IO) { !app.schedule.eligible(date, resume) }) return@launch
                if (!app.canRunDaily()) {
                    result = getString(R.string.run_missing_key)
                    return@launch
                }
                if (themes != null) {
                    val limit = requireNotNull(intent).getDoubleExtra("limit", -1.0)
                    successful = withContext(Dispatchers.IO) { app.runtime().runAnalysis(themes, limit).successful }
                    if (successful) result = getString(R.string.notification_analysis)
                } else {
                    if (automatic && !resume && !app.schedule.beginAttempt(date)) {
                        result = getString(R.string.run_attempt_limit)
                        return@launch
                    }
                    chargedAttempt = automatic && !resume
                    if (!automatic) app.schedule.clearCancellation(date)
                    val summary = app.executeDaily(date, resume, automatic) { stage ->
                        RunNotifications.stage(this@CollectorRunService, PROGRESS, stage, cancel)
                    }
                    if (summary == null || summary.skipped) return@launch
                    successful = summary.exitCode == 0 || summary.daily?.published == true
                    if (successful) {
                        result = RunNotifications.dailyMessage(app, summary,
                            runCatching { app.sync.trackingUpdates(date) }.getOrDefault(0))
                    } else if (app.schedule.paidFailure(date)) {
                        app.schedule.notifyRetryBlocked(date)
                        return@launch
                    }
                }
                if (!successful) result = getString(R.string.run_failed)
            } catch (_: RunLockedException) {
                if (chargedAttempt) app.schedule.refundAttempt(date)
                if (automatic && !resume) app.schedule.pendingReservation(date)
                alreadyRunning()
            } catch (e: CancellationException) {
                activeDailyDate?.let(app.schedule::systemCancelled)
                throw e
            } catch (_: Exception) {
                result = getString(R.string.run_failed)
                successful = false
            } finally {
                activeDailyDate = null
                foreground = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                running.value = false
                stopSelf(latestStartId)
                withContext(NonCancellable) {
                    app.finishRun()
                    result?.let { RunNotifications.result(app, it, successful) }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun promote(notification: android.app.Notification): Boolean = try {
        startForeground(PROGRESS, notification)
        true
    } catch (_: IllegalStateException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun alreadyRunning() {
        Toast.makeText(this, R.string.run_already_running, Toast.LENGTH_SHORT).show()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        scope.cancel()
        stopSelf(latestStartId)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CANCEL = "app.ideacollector.CANCEL_RUN"
        private const val PROGRESS = 31
        internal val running = MutableStateFlow(false)

        internal fun startDaily(context: Context, date: LocalDate? = null, resume: Boolean = false): Boolean {
            val intent = Intent(context, CollectorRunService::class.java).putExtra("resume", resume)
            if (date != null) intent.putExtra("date", date.toString())
            return start(context, intent)
        }

        internal fun startAnalysis(context: Context, themes: List<String>, limit: Double) {
            require(themes.size in 1..3 && limit.isFinite() && limit >= 0)
            check(start(context, Intent(context, CollectorRunService::class.java)
                .putStringArrayListExtra("themes", ArrayList(themes)).putExtra("limit", limit)))
        }

        private fun start(context: Context, intent: Intent): Boolean = try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

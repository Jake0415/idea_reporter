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
        val firstRun = intent?.getBooleanExtra("firstRun", false) == true
        val themes = intent?.getStringArrayListExtra("themes")
        if (!app.operationRunning.compareAndSet(expect = false, update = true)) {
            intent?.getStringExtra("date")?.let(LocalDate::parse)?.let { date ->
                app.appScope.launch { runCatching { app.schedule.holdReservation(date) } }
            }
            if (themes != null) analysisOutcome.value = collector.runtime.AnalysisSummary(false, failure = getString(R.string.run_already_running))
            if (firstRun) dailyOutcome.value = DailyOutcome(false, R.string.run_already_running)
            alreadyRunning()
            if (!foreground) {
                if (promote(RunNotifications.notification(this, getString(R.string.run_already_running)).build())) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
                stopSelf(latestStartId)
            }
            return START_NOT_STICKY
        }
        running.value = true
        val date = intent?.getStringExtra("date")?.let(LocalDate::parse) ?: LocalDate.now()
        var resume = intent?.getBooleanExtra("resume", false) == true
        val automatic = intent?.hasExtra("date") == true && !firstRun
        val initial = if (themes == null) R.string.run_collecting else R.string.run_analyzing
        RunNotifications.currentStage.value = initial
        val cancel = PendingIntent.getService(this, 0, Intent(this, CollectorRunService::class.java).setAction(CANCEL),
            PendingIntent.FLAG_IMMUTABLE)
        if (!promote(RunNotifications.progress(this, getString(initial), cancel))) {
            if (automatic && !resume) app.schedule.pendingReservation(date)
            running.value = false
            if (themes == null) dailyOutcome.value = DailyOutcome(false, R.string.run_failed)
            else analysisOutcome.value = collector.runtime.AnalysisSummary(false, failure = getString(R.string.run_failed))
            app.appScope.launch {
                try {
                    app.finishOperation()
                } finally {
                    if (!foreground) stopSelf(latestStartId)
                }
            }
            return START_NOT_STICKY
        }
        foreground = true
        activeDailyDate = date.takeIf { themes == null }
        runJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var chargedAttempt = false
            var successful = false
            var result: String? = null
            var reason = R.string.run_failed
            var analysisResult: collector.runtime.AnalysisSummary? = null
            try {
                if (firstRun) {
                    val runtime = app.runtime()
                    resume = withContext(Dispatchers.IO) { runtime.hasBatchToResume(date) }
                    val paid = withContext(Dispatchers.IO) { runtime.dailyCost(date) > 0 }
                    if (!resume && (!app.schedule.mayRetryPaid(date) || paid)) {
                        reason = R.string.r3_first_retry_blocked
                        result = getString(R.string.run_failed)
                        return@launch
                    }
                }
                if (automatic && withContext(Dispatchers.IO) { !app.schedule.eligible(date, resume) }) return@launch
                if (!resume && !app.canRunDaily()) {
                    reason = R.string.run_missing_key
                    result = getString(reason)
                    return@launch
                }
                if (themes != null) {
                    val limit = requireNotNull(intent).getDoubleExtra("limit", -1.0)
                    val outcome = withContext(Dispatchers.IO) {
                        val mailAvailable = app.hasMailCredentials()
                        app.runtime().runAnalysis(themes, limit, mailEnabled = { mailAvailable && app.mailEnabled },
                            budgetVersion = intent.getStringExtra("budgetVersion"))
                    }
                    successful = outcome.successful
                    analysisResult = outcome
                    result = when {
                        successful -> getString(R.string.notification_analysis)
                        outcome.requote -> getString(R.string.r3_analysis_requote)
                        else -> getString(R.string.r3_analysis_failure, outcome.failure ?: getString(R.string.run_failed))
                    }
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
                    if (summary == null) {
                        successful = true
                        reason = R.string.r3_batch_pending_first
                        return@launch
                    }
                    if (summary.skipped) return@launch
                    successful = summary.exitCode == 0 || summary.daily?.published == true
                    if (successful) {
                        result = RunNotifications.dailyMessage(app, summary,
                            runCatching { app.sync.trackingUpdates(date) }.getOrDefault(0))
                    } else if (app.schedule.paidFailure(date)) {
                        app.schedule.notifyRetryBlocked(date)
                        return@launch
                    }
                }
                if (!successful && result == null) result = getString(R.string.run_failed)
            } catch (_: RunLockedException) {
                reason = R.string.run_already_running
                if (chargedAttempt) app.schedule.refundAttempt(date)
                if (automatic && !resume) app.schedule.pendingReservation(date)
                alreadyRunning()
            } catch (e: CancellationException) {
                reason = R.string.r3_cancelled
                activeDailyDate?.let(app.schedule::systemCancelled)
                throw e
            } catch (_: Exception) {
                if (themes != null) {
                    analysisResult = collector.runtime.AnalysisSummary(false)
                    result = getString(R.string.r3_analysis_failure, getString(R.string.run_failed))
                } else result = getString(R.string.run_failed)
                successful = false
            } finally {
                activeDailyDate = null
                foreground = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                running.value = false
                stopSelf(latestStartId)
                withContext(NonCancellable) {
                    app.finishOperation()
                    if (themes == null) dailyOutcome.value = DailyOutcome(successful, reason)
                    else analysisOutcome.value = analysisResult ?: collector.runtime.AnalysisSummary(false, failure = getString(reason))
                    result?.let { RunNotifications.result(app, it, successful, "analysis".takeIf { themes != null }) }
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
        runJob?.cancel()
        stopSelf(latestStartId)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CANCEL = "app.ideacollector.CANCEL_RUN"
        private const val PROGRESS = 31
        internal fun cancelActive(app: CollectorApp) {
            val worker = CancelRunWorkerReceiver.active.value
            if (worker != null) {
                app.schedule.cancelByUser(worker.second)
                androidx.work.WorkManager.getInstance(app).cancelWorkById(worker.first)
            } else app.startService(Intent(app, CollectorRunService::class.java).setAction(CANCEL))
        }

        internal val analysisOutcome = MutableStateFlow<collector.runtime.AnalysisSummary?>(null)
        internal val dailyOutcome = MutableStateFlow<DailyOutcome?>(null)
        internal val running = MutableStateFlow(false)

        internal fun startDaily(context: Context, date: LocalDate? = null, resume: Boolean = false, firstRun: Boolean = false): Boolean {
            val intent = Intent(context, CollectorRunService::class.java).putExtra("resume", resume).putExtra("firstRun", firstRun)
            if (date != null) intent.putExtra("date", date.toString())
            return start(context, intent)
        }

        internal fun startAnalysis(context: Context, themes: List<String>, limit: Double, budgetVersion: String?) {
            if (themes.size !in 1..3 || !limit.isFinite() || limit < 0) {
                analysisOutcome.value = collector.runtime.AnalysisSummary(false, failure = context.getString(R.string.run_failed))
                return
            }
            analysisOutcome.value = null
            if (!start(context, Intent(context, CollectorRunService::class.java)
                .putStringArrayListExtra("themes", ArrayList(themes)).putExtra("limit", limit).putExtra("budgetVersion", budgetVersion))) {
                analysisOutcome.value = collector.runtime.AnalysisSummary(false, failure = context.getString(R.string.run_failed))
            }
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

internal data class DailyOutcome(val successful: Boolean, val reason: Int)

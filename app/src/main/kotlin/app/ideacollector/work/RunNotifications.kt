package app.ideacollector.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.ideacollector.MainActivity
import app.ideacollector.R
import collector.runtime.RunStage

internal object RunNotifications {
    val currentStage = kotlinx.coroutines.flow.MutableStateFlow(R.string.run_collecting)
    val previousFailures = kotlinx.coroutines.flow.MutableStateFlow(0)
    fun restorePreviousFailures(context: Context) {
        previousFailures.value = context.getSharedPreferences("run-mail-results", 0).getInt("previous-failures", 0)
    }

    fun dailyMessage(context: android.content.Context, summary: collector.runtime.ScheduledSummary,
        tracking: Int): String {
        val message = when (summary.mailStatus) {
            collector.runtime.ScheduledMailStatus.PENDING -> context.getString(R.string.run_mail_pending)
            collector.runtime.ScheduledMailStatus.FAILED -> "${context.getString(R.string.run_mail_failed)} · ${summary.mailFailureReason}"
            else -> context.getString(R.string.run_finished, summary.daily?.cards ?: 0, tracking)
        }
        val previous = previousMailFailures(context, summary)
        return if (previous == null) message else "$message · $previous"
    }

    fun previousMailFailure(context: Context, summary: collector.runtime.ScheduledSummary) {
        val message = previousMailFailures(context, summary) ?: return
        channel(context)
        manager(context).notify(36, notification(context, message, "runs").build())
    }

    private fun previousMailFailures(context: Context, summary: collector.runtime.ScheduledSummary): String? {
        val failures = summary.previousMailRetries.filterKeys {
            it != collector.mail.MailSendResult.SENT && it != collector.mail.MailSendResult.SKIPPED_DUPLICATE
        }.values.sum()

        return failures.takeIf { it > 0 }?.let { context.getString(R.string.run_previous_mail_failed, it) }
    }

    fun updatePreviousFailures(context: Context, count: Int) {
        context.getSharedPreferences("run-mail-results", 0).edit().putInt("previous-failures", count).apply()
        previousFailures.value = count
    }

    fun dailyResult(context: android.content.Context, summary: collector.runtime.ScheduledSummary, tracking: Int) {
        result(context, dailyMessage(context, summary, tracking), true)
    }

    private const val CHANNEL = "collector-run"
    private const val RESULT = 32

    fun backupRecovery(context: Context, pending: Boolean) {
        if (pending) {
            channel(context)
            manager(context).notify(37, notification(context, context.getString(R.string.backup_recovery_pending)).build())
        } else manager(context).cancel(37)
    }
    private const val BATTERY_EXEMPTION = 34

    fun channel(context: Context) {
        manager(context).createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.run_channel),
            NotificationManager.IMPORTANCE_LOW))
    }

    fun notification(context: Context, title: String, destination: String = "today"): NotificationCompat.Builder {
        val intent = Intent(context, MainActivity::class.java).putExtra("runDestination", destination)
        return NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(title).setOnlyAlertOnce(true).setAutoCancel(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(PendingIntent.getActivity(context, destination.hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    }

    fun progress(context: Context, title: String, cancel: PendingIntent): Notification {
        channel(context)
        return notification(context, title).setOngoing(true)
            .addAction(0, context.getString(R.string.run_cancel), cancel).build()
    }

    fun stage(context: Context, id: Int, stage: RunStage, cancel: PendingIntent) {
        val label = when (stage) {
            RunStage.RECOVER, RunStage.COLLECT -> R.string.run_collecting
            RunStage.CLASSIFY -> R.string.run_classifying
            RunStage.GENERATE -> R.string.run_writing
            RunStage.MAIL -> R.string.run_mailing
        }
        currentStage.value = label
        manager(context).notify(id, progress(context, context.getString(label), cancel))
    }

    fun result(context: Context, message: Int) =
        result(context, context.getString(message), false)

    fun result(context: Context, message: String, successful: Boolean, destination: String? = null) {
        channel(context)
        manager(context).notify(RESULT, notification(context, message, destination ?: if (successful) "today" else "runs").build())
    }

    fun unpublishedBatch(context: Context, date: java.time.LocalDate) {
        channel(context)
        manager(context).notify("batch-$date", RESULT,
            notification(context, context.getString(R.string.run_batch_date_unapplied, date.toString()), "runs").build())
    }

    fun expiredMail(context: Context, count: Int) {
        channel(context)
        manager(context).notify(35, notification(context,
            context.getString(R.string.run_mail_expired, count), "runs").build())
    }

    fun clearUnpublishedBatch(context: Context, date: java.time.LocalDate) {
        manager(context).cancel("batch-$date", RESULT)
    }

    fun batteryExemption(context: Context, required: Boolean) {
        if (required) {
            channel(context)
            manager(context).notify(BATTERY_EXEMPTION,
                notification(context, context.getString(R.string.run_battery_exemption), "runs").build())
        } else {
            manager(context).cancel(BATTERY_EXEMPTION)
        }
    }

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)
}

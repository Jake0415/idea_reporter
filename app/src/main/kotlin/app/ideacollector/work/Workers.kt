package app.ideacollector.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.ideacollector.CollectorApp
import app.ideacollector.github.validRepository
import app.ideacollector.github.UnsupportedSchema
import app.ideacollector.github.GitHubFailure
import java.io.IOException
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

internal fun nextSyncDelay(now: ZonedDateTime): Duration {
    val time = LocalTime.of(7, 40)
    val today = now.toLocalDate().atTime(time).atZone(now.zone)
    val next = if (today < now) now.toLocalDate().plusDays(1).atTime(time).atZone(now.zone) else today
    return Duration.between(now, next)
}

internal class WorkSchedule(context: Context) {
    private val manager = WorkManager.getInstance(context)
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun periodic(hours: Long, change: Boolean = false) {
        val work = PeriodicWorkRequestBuilder<SyncWorker>(hours, TimeUnit.HOURS)
            .setConstraints(network)
            .setInitialDelay(nextSyncDelay(ZonedDateTime.now()))
            .build()
        manager.enqueueUniquePeriodicWork("periodic-sync",
            if (change) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP, work)
    }

    fun sync() {
        val work = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network)
            .setInputData(workDataOf("checkSent" to false)).build()
        manager.enqueueUniqueWork("manual-sync", ExistingWorkPolicy.KEEP, work)
    }

    fun recoverOutbox() = enqueueOutbox(ExistingWorkPolicy.KEEP)

    fun outbox() = enqueueOutbox(ExistingWorkPolicy.APPEND_OR_REPLACE)

    private fun enqueueOutbox(policy: ExistingWorkPolicy) {
        val work = OneTimeWorkRequestBuilder<OutboxWorker>().setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        manager.enqueueUniqueWork("outbox", policy, work)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as CollectorApp
        val settings = app.settings()
        if (!validRepository(settings.repository)) return Result.success()
        app.recoverOutbox()
        return try {
            val news = app.sync.sync(settings.repository, app.secrets.get(settings.tokenName))
            if (settings.notifications) app.notifications.publish(news)
            if (inputData.getBoolean("checkSent", true)) {
                app.sync.send(settings.repository, checkSent = true) { app.secrets.get(app.settings().tokenName) }
            }
            Result.success()
        } catch (_: UnsupportedSchema) {
            val news = app.sync.updateRequired(settings.repository)
            if (settings.notifications) app.notifications.publish(news)
            Result.success()
        } catch (e: IOException) {
            app.sync.recordSyncFailure(settings.repository, (e as? GitHubFailure)?.status == 401)
            Result.retry()
        } catch (_: IllegalArgumentException) {
            app.sync.recordSyncFailure(settings.repository)
            Result.retry()
        }
    }
}

class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as CollectorApp
        val repo = app.settings().repository
        if (!validRepository(repo)) return Result.success()
        return try {
            if (app.sync.send(repo) { app.secrets.get(app.settings().tokenName) }) Result.success() else Result.retry()
        } catch (_: UnsupportedSchema) {
            val news = app.sync.updateRequired(repo)
            if (app.settings().notifications) app.notifications.publish(news)
            Result.success()
        }
    }
}

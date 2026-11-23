package app.ideacollector

import android.app.Application
import androidx.room.Room
import app.ideacollector.data.AppDatabase
import app.ideacollector.data.AppSettings
import app.ideacollector.data.RepositorySync
import app.ideacollector.github.GitHubClient
import app.ideacollector.secret.KeystoreSecretProvider
import app.ideacollector.work.SyncNotifications
import app.ideacollector.work.WorkSchedule
import collector.data.InboxRequest
import app.ideacollector.data.partialSyncMigration
import app.ideacollector.data.snapshotSyncMigration
import app.ideacollector.data.requestMetadataMigration
import app.ideacollector.secret.GITHUB_TOKEN
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CollectorApp : Application() {
    internal val database by lazy { Room.databaseBuilder(this, AppDatabase::class.java, "collector.db").addMigrations(requestMetadataMigration, partialSyncMigration, snapshotSyncMigration).build() }
    internal val secrets by lazy { KeystoreSecretProvider(this) { name ->
        if (name == GITHUB_TOKEN) {
            sync.restoreAuth()
            if (database.dao().hasOutbox(settings().repository)) schedule.outbox()
        }
    } }
    internal val schedule by lazy { WorkSchedule(this) }
    internal val notifications by lazy { SyncNotifications(this) }
    internal var syncOverride: RepositorySync? = null
    private val defaultSync by lazy { RepositorySync(database, GitHubClient()) }
    internal val sync get() = syncOverride ?: defaultSync

    override fun onCreate() {
        super.onCreate()
        notifications.createChannel()
        CoroutineScope(Dispatchers.IO).launch { recoverOutbox() }
    }

    internal suspend fun settings() = database.dao().settings() ?: AppSettings()

    internal suspend fun recoverOutbox() {
        val repo = settings().repository
        if (database.dao().hasOutbox(repo)) schedule.recoverOutbox()
    }

    internal suspend fun saveSettings(settings: AppSettings): Int {
        require(settings.syncHours in 1..168)
        val cancelled = sync.changeRepository(settings)
        schedule.periodic(settings.syncHours, change = true)
        schedule.outbox()
        return cancelled
    }

    internal suspend fun submit(request: InboxRequest) {
        val repo = settings().repository
        require(app.ideacollector.github.validRepository(repo))
        sync.enqueue(repo, request)
        schedule.outbox()
    }
}

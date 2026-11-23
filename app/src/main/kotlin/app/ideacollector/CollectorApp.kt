package app.ideacollector

import android.app.Application
import androidx.room.Room
import app.ideacollector.data.AppDatabase
import app.ideacollector.data.dailyDispatchMigration
import app.ideacollector.data.connectionStateMigration
import app.ideacollector.data.AppSettings
import app.ideacollector.data.RepositorySync
import app.ideacollector.github.GitHubClient
import app.ideacollector.github.validRepository
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CollectorApp : Application() {
    internal val database by lazy { Room.databaseBuilder(this, AppDatabase::class.java, "collector.db")
        .addMigrations(requestMetadataMigration, partialSyncMigration, snapshotSyncMigration, dailyDispatchMigration,
            connectionStateMigration).build() }
    internal val secrets by lazy { KeystoreSecretProvider(this) { name ->
        val settings = settings()
        if (name == settings.tokenName) {
            sync.restoreAuth()
            val repo = settings.repository
            if (validRepository(repo) && database.dao().hasOutbox(repo)) schedule.outbox()
        }
    } }
    internal val schedule by lazy { WorkSchedule(this) }
    internal val notifications by lazy { SyncNotifications(this) }
    internal var syncOverride: RepositorySync? = null
    private val defaultSync by lazy { RepositorySync(database, GitHubClient()) }
    private val connectionSave = Mutex()
    internal val sync get() = syncOverride ?: defaultSync

    override fun onCreate() {
        super.onCreate()
        notifications.createChannel()
        CoroutineScope(Dispatchers.IO).launch { recoverOutbox() }
    }

    internal suspend fun settings() = database.dao().settings() ?: AppSettings()

    internal suspend fun saveConnection(repository: String, newToken: String?) = connectionSave.withLock {
        val previous = settings()
        if (newToken == null) {
            require(repository == previous.repository && secrets.has(previous.tokenName))
            return@withLock
        }
        val name = "$GITHUB_TOKEN-" + java.util.UUID.randomUUID()
        withContext(NonCancellable) {
            check(secrets.set(name, newToken))
            try {
                sync.changeRepository(previous.copy(repository = repository, tokenName = name))
            } catch (e: Exception) {
                secrets.clear(name)
                throw e
            }
            secrets.clear(previous.tokenName)
            sync.restoreAuth()
        }
    }

    internal suspend fun recoverOutbox() {
        val repo = settings().repository
        if (validRepository(repo) && database.dao().hasOutbox(repo)) schedule.recoverOutbox()
    }

    internal suspend fun saveSettings(settings: AppSettings): Int {
        require(settings.syncHours in 1..168)
        val cancelled = sync.changeRepository(settings)
        schedule.periodic(settings.syncHours, change = true)
        schedule.outbox()
        return cancelled
    }

    internal suspend fun submit(request: InboxRequest) = submit(listOf(request))

    internal suspend fun submit(requests: List<InboxRequest>) {
        val repo = settings().repository
        require(validRepository(repo))
        sync.enqueue(repo, requests)
        schedule.outbox()
    }
}

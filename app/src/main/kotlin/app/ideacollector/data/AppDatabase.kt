package app.ideacollector.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

// Retain the core JSON verbatim so future screens can deserialize its full model.
@Entity(tableName = "artifacts", primaryKeys = ["repository", "path"])
data class Artifact(val repository: String, val path: String, val kind: String, val sha256: String, val json: String)

data class ArtifactHash(val path: String, val sha256: String)

@Entity(tableName = "cards", primaryKeys = ["repository", "path", "id"])
data class CardRow(val repository: String, val path: String, val id: String, val json: String)

@Fts4(notIndexed = ["repository", "path", "cardId"])
@Entity(tableName = "card_search")
data class CardSearch(val repository: String, val path: String, val cardId: String,
    val title: String, val description: String, val evidence: String)

@Entity(tableName = "requests", primaryKeys = ["repository", "requestId"])
data class RequestRow(val repository: String, val requestId: String, val path: String, val json: String,
    val createdAt: String, val status: String = "PENDING", val uploaded: Boolean = false,
    val cardId: String? = null, val reaction: String? = null, val reason: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val attempts: Int = 0,
    val nextAttemptAt: Long? = null, val dispatchedAt: Long? = null)

@Entity(tableName = "sync_state")
data class SyncState(@PrimaryKey val repository: String, val manifestSha: String, val etag: String?, val syncedAt: String,
    @androidx.room.ColumnInfo(defaultValue = "'OK'") val status: String = "OK", val issue: String? = null,
    val remainingFiles: String? = null, val commitSha: String? = null, val fileFailures: String? = null)

@Entity(tableName = "app_settings")
data class AppSettings(@PrimaryKey val id: Int = 0, val repository: String = "", val syncHours: Long = 24,
    val notifications: Boolean = true,
    @androidx.room.ColumnInfo(defaultValue = "'GITHUB_TOKEN'") val tokenName: String = app.ideacollector.secret.GITHUB_TOKEN,
    @androidx.room.ColumnInfo(defaultValue = "25200") val runTimeSeconds: Long = 25200,
    @androidx.room.ColumnInfo(defaultValue = "0") val wifiOnly: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "0") val chargingOnly: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "0") val batch: Boolean = false)

@Entity(tableName = "pending_local_changes")
data class PendingLocalChange(@PrimaryKey(autoGenerate = true) val id: Long = 0,
    val configName: String? = null, val json: String, @ColumnInfo(defaultValue = "0") val failed: Boolean = false)

@Dao
interface AppDao {
    @Insert suspend fun enqueueLocalChange(change: PendingLocalChange)
    @Query("SELECT * FROM pending_local_changes WHERE failed = 0 ORDER BY id") fun observeQueuedChanges(): Flow<List<PendingLocalChange>>
    @Query("SELECT * FROM pending_local_changes WHERE failed = 0 ORDER BY id") suspend fun localChanges(): List<PendingLocalChange>
    @Query("UPDATE pending_local_changes SET failed = 1 WHERE id = :id") suspend fun failLocalChange(id: Long)
    @Query("UPDATE pending_local_changes SET failed = 0 WHERE id = :id") suspend fun retryLocalChange(id: Long)
    @Query("SELECT * FROM pending_local_changes WHERE failed = 1 ORDER BY id")
    fun observeFailedLocalChanges(): Flow<List<PendingLocalChange>>
    @Query("DELETE FROM pending_local_changes WHERE id = :id") suspend fun removeLocalChange(id: Long)
    @Query("SELECT EXISTS(SELECT 1 FROM pending_local_changes WHERE failed = 0)") fun observePendingLocalChanges(): Flow<Boolean>

    @Query("SELECT * FROM app_settings WHERE id = 0") fun observeSettings(): Flow<AppSettings?>
    @Query("SELECT * FROM artifacts WHERE repository = :repo") fun observeArtifacts(repo: String): Flow<List<Artifact>>
    @Query("SELECT * FROM requests WHERE repository = :repo ORDER BY rowid DESC")
    fun observeRequests(repo: String): Flow<List<RequestRow>>
    @Query("DELETE FROM requests WHERE repository = '@sample'")
    suspend fun clearSampleRequests()
    @Query("UPDATE requests SET status = 'CANCELLED' WHERE repository = :repo AND requestId = :id AND reaction = 'NOT_INTERESTED' AND status IN ('PENDING', 'NEEDS_AUTH') AND uploaded = 0 AND attempts = 0")
    suspend fun cancelPendingFeedback(repo: String, id: String): Int
    @Query("SELECT * FROM app_settings WHERE id = 0") suspend fun settings(): AppSettings?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveSettings(settings: AppSettings)
    @Query("SELECT * FROM sync_state WHERE repository = :repo") suspend fun state(repo: String): SyncState?
    @Query("SELECT * FROM sync_state WHERE repository = :repo") fun observeState(repo: String): Flow<SyncState?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveState(state: SyncState)

    @Query("SELECT * FROM artifacts WHERE repository = :repo") suspend fun artifacts(repo: String): List<Artifact>
    @Query("SELECT path, sha256 FROM artifacts WHERE repository = :repo")
    suspend fun hashes(repo: String): List<ArtifactHash>
    @Query("SELECT * FROM artifacts WHERE repository = :repo AND kind = :kind ORDER BY path DESC")
    fun observeArtifacts(repo: String, kind: String): Flow<List<Artifact>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveArtifact(artifact: Artifact)
    @Query("DELETE FROM artifacts WHERE repository = :repo AND path = :path")
    suspend fun deleteArtifact(repo: String, path: String)

    @Query("SELECT EXISTS(SELECT 1 FROM cards WHERE repository = :repo)") suspend fun hasCards(repo: String): Boolean
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveCards(cards: List<CardRow>)
    @Insert suspend fun indexCards(cards: List<CardSearch>)
    @Query("DELETE FROM cards WHERE repository = :repo AND path = :path")
    suspend fun deleteCards(repo: String, path: String)
    @Query("DELETE FROM card_search WHERE repository = :repo AND path = :path")
    suspend fun deleteIndex(repo: String, path: String)
    @Query("""
        SELECT DISTINCT cards.* FROM cards JOIN card_search ON cards.repository = card_search.repository
            AND cards.path = card_search.path AND cards.id = card_search.cardId
        WHERE cards.repository = :repo AND card_search MATCH :query ORDER BY cards.path DESC
    """)
    suspend fun search(repo: String, query: String): List<CardRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertRequest(request: RequestRow)
    @Query("SELECT * FROM requests WHERE repository = :repo AND status = 'PENDING' ORDER BY rowid")
    suspend fun pending(repo: String): List<RequestRow>
    @Query("UPDATE requests SET status = :status, reason = :reason WHERE repository = :repo AND requestId = :id")
    suspend fun complete(repo: String, id: String, status: String, reason: String?)
}

internal val requestMetadataMigration = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE requests ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE requests ADD COLUMN nextAttemptAt INTEGER")
        db.execSQL("ALTER TABLE requests ADD COLUMN dispatchedAt INTEGER")
        db.execSQL("""
            UPDATE requests SET dispatchedAt = CAST(strftime('%s', 'now') AS INTEGER)
            WHERE status = 'SENT' AND instr(json, '"type":"analysis.request"') > 0
        """.trimIndent())
        db.execSQL("ALTER TABLE sync_state ADD COLUMN status TEXT NOT NULL DEFAULT 'OK'")
        db.execSQL("ALTER TABLE sync_state ADD COLUMN issue TEXT")
    }
}

internal val partialSyncMigration = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sync_state ADD COLUMN remainingFiles TEXT")
    }
}

internal val snapshotSyncMigration = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sync_state ADD COLUMN commitSha TEXT")
        db.execSQL("ALTER TABLE sync_state ADD COLUMN fileFailures TEXT")
    }
}

internal val dailyDispatchMigration = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS daily_dispatch (repository TEXT NOT NULL PRIMARY KEY, dispatchedAt TEXT NOT NULL)")
    }
}

internal val connectionStateMigration = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE app_settings ADD COLUMN tokenName TEXT NOT NULL DEFAULT 'GITHUB_TOKEN'")
        db.execSQL("ALTER TABLE daily_dispatch ADD COLUMN active INTEGER NOT NULL DEFAULT 0")
    }
}

internal val phoneRunMigration = object : androidx.room.migration.Migration(6, 7) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE daily_dispatch")
        db.execSQL("CREATE TABLE pending_local_changes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, configName TEXT, json TEXT NOT NULL)")
        db.execSQL("""
            INSERT INTO pending_local_changes (configName, json)
            SELECT NULL, '[' || json || ']' FROM requests
            WHERE status = 'PENDING' AND uploaded = 0 AND reaction IS NOT NULL AND repository NOT IN ('@phone', '@sample')
            ORDER BY rowid
        """.trimIndent())
        for (table in listOf("artifacts", "cards", "card_search", "requests", "sync_state")) {
            db.execSQL("DELETE FROM $table WHERE repository NOT IN ('@phone', '@sample')")
        }
        db.execSQL("ALTER TABLE app_settings ADD COLUMN runTimeSeconds INTEGER NOT NULL DEFAULT 25200")
        db.execSQL("ALTER TABLE app_settings ADD COLUMN wifiOnly INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE app_settings ADD COLUMN chargingOnly INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE app_settings ADD COLUMN batch INTEGER NOT NULL DEFAULT 0")
    }
}

internal val localChangeFailureMigration = object : androidx.room.migration.Migration(7, 8) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pending_local_changes ADD COLUMN failed INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(entities = [Artifact::class, CardRow::class, CardSearch::class, RequestRow::class,
    SyncState::class, AppSettings::class, PendingLocalChange::class], version = 8, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao
}

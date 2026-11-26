package app.ideacollector

import collector.prompt.mergeCriteria
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
import collector.runtime.Backup
import collector.runtime.CollectorPaths
import kotlinx.coroutines.launch
import collector.runtime.CollectorRuntime
import collector.runtime.BatchDeferredException
import collector.runtime.ScheduledOptions
import collector.runtime.RunStage
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal class BackupRecoveryBlocked : RunLockedException()
internal class RunAttemptLimitReached : RunLockedException()

internal enum class BackupRecovery { LOADING, READY, PENDING }

class CollectorApp : Application() {
    internal val database by lazy { Room.databaseBuilder(this, AppDatabase::class.java, "collector.db")
        .addMigrations(requestMetadataMigration, partialSyncMigration, snapshotSyncMigration, dailyDispatchMigration,
            connectionStateMigration, phoneRunMigration, localChangeFailureMigration).build() }
    internal val secrets by lazy { KeystoreSecretProvider(this) }
    internal val paths by lazy {
        CollectorPaths(filesDir.resolve("collector/out"), filesDir.resolve("collector/config"), cacheDir.resolve("collector"))
    }
    internal val sync by lazy { RepositorySync(database, paths) { recovery.value == BackupRecovery.READY } }
    internal val schedule by lazy { RunScheduler(this) }
    private val localChangesLock = Mutex()
    internal val recovery = kotlinx.coroutines.flow.MutableStateFlow(BackupRecovery.LOADING)
    internal val recoveryUnavailable = kotlinx.coroutines.flow.MutableStateFlow(false)
    internal fun requireRunReady() {
        if (recovery.value != BackupRecovery.READY) throw BackupRecoveryBlocked()
    }
    internal fun requireBackupReady() = check(recovery.value == BackupRecovery.READY)
    internal val backupState = kotlinx.coroutines.flow.MutableStateFlow(BackupState())
    internal val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)
    internal val operationRunning = kotlinx.coroutines.flow.MutableStateFlow(false)
    internal val pendingLocalChanges get() = database.dao().observePendingLocalChanges()
    internal val failedLocalChanges get() = database.dao().observeFailedLocalChanges()
    internal var runtimeOverride: CollectorRuntime? = null
    internal var testMailOverride: (suspend (Map<String, String>) -> collector.mail.MailSendResult)? = null
    internal var modelListOverride: (suspend (String, String) -> List<String>)? = null

    internal suspend fun modelsForKey(name: String, value: String, firstPageOnly: Boolean = false) =
        modelListOverride?.invoke(name, value) ?: modelsFor(name, value, firstPageOnly)

    private fun keyVerified(key: String): Boolean {
        val checks = getSharedPreferences("key-confirmation", 0)
        return !checks.getBoolean("failed-$key", false)
    }

    internal suspend fun hasVerifiedKey() = aiKeys.any { (key, _) -> keyVerified(key) && secrets.has(key) }

    private val mailKeys = listOf("MAIL_TO", "SMTP_USERNAME", "SMTP_PASSWORD")
    private val sourceKeys = listOf("GITHUB_TOKEN", "PRODUCTHUNT_TOKEN", "REDDIT_CLIENT_ID", "REDDIT_CLIENT_SECRET")

    internal suspend fun backupPrivateValues(): List<String> =
        secrets.getAll(mailKeys + aiKeys.map { it.first } + sourceKeys + settings().tokenName).values.filterNotNull()

    internal suspend fun redditKeysStored() =
        secrets.getAll(listOf("REDDIT_CLIENT_ID", "REDDIT_CLIENT_SECRET")).values.all { !it.isNullOrBlank() }

    internal suspend fun hasMailCredentials() = secrets.getAll(mailKeys).values.all { it != null }

    internal var mailEnabled: Boolean
        get() = getSharedPreferences("mail-settings", 0).getBoolean("enabled", true)
        set(value) { getSharedPreferences("mail-settings", 0).edit().putBoolean("enabled", value).apply() }

    internal var includeMailCandidates: Boolean
        get() = getSharedPreferences("mail-settings", 0).getBoolean("include-candidates", false)
        set(value) { getSharedPreferences("mail-settings", 0).edit().putBoolean("include-candidates", value).apply() }

    internal suspend fun mailDraft(recipient: String, sender: String, password: String): Map<String, String> {
        val entered = mailKeys.zip(listOf(recipient, sender, password).map(String::trim)).toMap()
        val stored = secrets.getAll(entered.filterValues(String::isBlank).keys)
        return entered.mapValues { (name, value) -> value.ifBlank { requireNotNull(stored[name]) } }
    }

    internal suspend fun testMail(draft: Map<String, String>) = withContext(Dispatchers.IO) {
        testMailOverride?.invoke(draft)
            ?: CollectorRuntime(paths, { draft[it] }, Clock.systemDefaultZone()).mailTest()
    }

    internal suspend fun mailConfigured() = mailEnabled && hasMailCredentials()

    internal suspend fun runtime(): CollectorRuntime = withContext(Dispatchers.IO) {
        runtimeOverride ?: run {
            val current = settings()
            val values = secrets.getAll(mailKeys + aiKeys.map { it.first } + sourceKeys).toMutableMap()
            val batchCredentials = aiKeys.associate { (key, _) -> key to values[key] }
            aiKeys.forEach { (key, _) -> if (!keyVerified(key)) values.remove(key) }
            if (values["GITHUB_TOKEN"] == null && current.tokenName != "GITHUB_TOKEN") {
                values["GITHUB_TOKEN"] = secrets.get(current.tokenName)
            }
            CollectorRuntime(paths, { values[it] }, Clock.systemDefaultZone(), batchRecoverySecrets = { batchCredentials[it] })
        }
    }

    internal suspend fun canRunDaily() = runtimeOverride != null || hasVerifiedKey()

    internal fun firstRunFinished() = getSharedPreferences("first-run", 0).getBoolean("finished", false)

    internal suspend fun migrateFirstRun() = withContext(Dispatchers.IO) {
        val preferences = getSharedPreferences("first-run", 0)
        if (!preferences.contains("finished")) {
            val dao = database.dao()
            val existing = aiKeys.any { (key, _) -> secrets.has(key) } ||
                dao.hasCards(LOCAL_REPO) ||
                listOf("", "runs", "runs/failed").any { directory ->
                    paths.outDir.resolve(directory).listFiles().orEmpty().any {
                        it.isFile && it.extension == "json" &&
                            runCatching { LocalDate.parse(it.name.take(10)) }.isSuccess
                    }
                } || dao.settings() != null || paths.outDir.resolve("settings/schedule.json").isFile
            preferences.edit().putBoolean("finished", existing).apply()
        }
        firstRunFinished()
    }

    internal fun finishFirstRun() {
        getSharedPreferences("first-run", 0).edit().putBoolean("finished", true).apply()
    }

    internal suspend fun executeDaily(date: LocalDate, resume: Boolean, automatic: Boolean,
        deferrals: Int = 0, onLockAcquired: () -> Unit = {}, onStage: (RunStage) -> Unit = {}): collector.runtime.ScheduledSummary? = withContext(Dispatchers.IO) {
        val runtime = runtime()
        fun markFailure() {
            val paid = runCatching { runtime.dailyCost(date) > 0 }.getOrDefault(true)
            schedule.failure(date, paid)
        }
        try {
            val mailAvailable = runtimeOverride != null || hasMailCredentials()
            runtime.runScheduled(ScheduledOptions(mail = mailAvailable,
                mailEnabled = { mailAvailable && mailEnabled },
                includeMailCandidates = includeMailCandidates,
                batchOverrideForRun = { resume || schedule.options().batch },
                deadline = Duration.ofMinutes(60), onStage = onStage, deferBatch = deferrals < 6,
                honorSchedule = automatic, runDate = date, beforeRun = { flushLocalChanges(it) },
                onLockAcquired = {
                    requireRunReady()
                    onLockAcquired()
                },
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
        } finally {
            runCatching { runtime.previousMailFailureCount(LocalDate.now()) }.getOrNull()?.let {
                RunNotifications.updatePreviousFailures(this@CollectorApp, it)
            }
        }
    }

    internal suspend fun finishOperation(releaseReservation: Boolean = true) = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
        localChangesLock.withLock {
            try {
                runCatching { flushLocalChangesLocked(null, deferScheduleUpdate = true) }
            } finally {
                operationRunning.value = false
            }
        }
        if (recovery.value == BackupRecovery.READY) {
            runCatching { schedule.finishScheduleUpdate() }
            if (releaseReservation) runCatching { schedule.releaseReservation() }
            runCatching { sync.refresh() }
        }
        Unit
    }

    internal fun firstRunDate(): LocalDate? =
        getSharedPreferences("first-run", 0).getString("date", null)?.let(LocalDate::parse)

    internal fun rememberFirstRunDate(date: LocalDate) {
        getSharedPreferences("first-run", 0).edit().putString("date", date.toString()).apply()
    }

    private fun recoveryFailed() {
        runCatching { Backup.markRecoveryPending(paths) }
        recovery.value = BackupRecovery.PENDING
        runCatching { RunNotifications.backupRecovery(this, pending = true) }
    }

    internal fun checkBackupRollback() {
        if (Backup.recoveryPending(paths)) recoveryFailed()
        else if (Backup.recoveryUnavailable(paths)) recoveryUnavailable.value = true
    }

    internal fun retryBackupRecovery() {
        if (operationRunning.value) return
        if (recovery.compareAndSet(BackupRecovery.PENDING, BackupRecovery.LOADING)) recoverBackup()
    }

    private fun recoverBackup() {
        appScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (Backup.recover(paths, deferCompletion = true, onUnavailable = { recoveryUnavailable.value = true })) {
                        sync.refreshAfterRecovery()
                        Backup.completeRecovery(paths)
                    }
                    cacheDir.listFiles().orEmpty().filter { it.name.startsWith("backup-transfer-") }.forEach { it.delete() }
                }
                runCatching { RunNotifications.backupRecovery(this@CollectorApp, pending = false) }
                recovery.value = BackupRecovery.READY
                runCatching { schedule.register() }
                runCatching { schedule.recoverPendingBatches() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                withContext(Dispatchers.IO) { recoveryFailed() }
            }
        }
    }

    internal fun dismissUnavailableRecovery() {
        recoveryUnavailable.value = false
        appScope.launch(Dispatchers.IO) { runCatching { Backup.acknowledgeUnavailableRecovery(paths) } }
    }

    override fun onCreate() {
        super.onCreate()
        recoverBackup()
        RunNotifications.restorePreviousFailures(this)
        val work = androidx.work.WorkManager.getInstance(this)
        listOf("periodic-sync", "manual-sync", "outbox").forEach { work.cancelUniqueWork(it) }
    }

    internal suspend fun settings() = database.dao().settings() ?: AppSettings()

    internal suspend fun saveLocalConfig(name: String, json: String) {
        saveChange(PendingLocalChange(configName = name, json = json))
    }

    internal suspend fun patchLocalConfig(name: String, fields: JsonObject) {
        saveChange(PendingLocalChange(configName = "patch:$name", json = fields.toString()))
    }

    internal suspend fun sourceSettings() = withContext(Dispatchers.IO) {
        localChangesLock.withLock { effectiveSources(database.dao().localChanges()) }
    }

    private fun effectiveSources(changes: List<PendingLocalChange>): collector.config.SourcesFile {
        val config = collector.config.ConfigLoader.load(paths.sourcesConfig)
        return config.copy(sources = config.sources.map { source ->
            changes.fold(source) { current, change ->
                when (change.configName) {
                    "source-patch:${source.sourceId}" -> mergeSourcePatch(current, Json.parseToJsonElement(change.json).jsonObject)
                    "source:${source.sourceId}" -> collector.config.ConfigLoader.json.decodeFromString(
                        collector.config.SourceConfig.serializer(), change.json)
                    else -> current
                }
            }
        })
    }

    internal suspend fun saveSource(original: collector.config.SourceConfig, edited: collector.config.SourceConfig) {
        val fields = sourcePatch(original, edited)
        if (fields.isEmpty()) return
        withContext(Dispatchers.IO) {
            localChangesLock.withLock {
                val config = effectiveSources(database.dao().localChanges())
                val current = config.sources.first { it.sourceId == original.sourceId }
                sourceReplacement(mergeSourcePatch(current, fields), config)
            }
        }
        saveChange(PendingLocalChange(configName = "source-patch:${original.sourceId}", json = fields.toString()))
    }

    private fun sourceReplacement(source: collector.config.SourceConfig,
        config: collector.config.SourcesFile = collector.config.ConfigLoader.load(paths.sourcesConfig)
    ): collector.config.SourcesFile {
        require(config.sources.any { it.sourceId == source.sourceId })
        val updated = config.copy(sources = config.sources.map { if (it.sourceId == source.sourceId) source else it })
        val profiles = collector.profile.ProfileLoader.loadDefaults()
        require(collector.profile.ProfileLoader.validateRouting(updated, profiles).isEmpty())
        return updated
    }

    internal suspend fun patchCriteria(fields: Map<String, String>) =
        saveChange(PendingLocalChange(configName = "criteria", json = Json.encodeToString(fields)))

    internal suspend fun criteriaSettings() = withContext(Dispatchers.IO) {
        localChangesLock.withLock {
            val queued = database.dao().localChanges()
            criteriaFields(effectiveCriteria(currentCriteria(), queued)) to effectivePipeline(this@CollectorApp, queued)
        }
    }

    internal fun currentCriteria(): String =
        collector.prompt.FilePromptStore(paths.promptsDir.toPath()).get("criteria")?.text
            ?: collector.prompt.PromptCatalog.defaultText("criteria")

    internal suspend fun resolveLocalChanges(ids: List<Long>, retry: Boolean) {
        localChangesLock.withLock {
            requireBackupReady()
            val dao = database.dao()
            ids.forEach { if (retry) dao.retryLocalChange(it) else dao.removeLocalChange(it) }
        }
        if (retry && !operationRunning.value) flushLocalChanges()
        sync.refresh()
    }

    internal suspend fun estimateAnalysis(themes: List<String>) = withContext(Dispatchers.IO) {
        runtime().estimateAnalysis(themes)
    }

    internal fun startAnalysis(themes: List<String>, limit: Double, budgetVersion: String?) =
        CollectorRunService.startAnalysis(this, themes, limit, budgetVersion)

    internal suspend fun submit(requests: List<InboxRequest>) =
        saveChange(PendingLocalChange(json = contractJson.encodeToString(requests)))

    private suspend fun saveChange(change: PendingLocalChange) = withContext(Dispatchers.IO) {
        localChangesLock.withLock {
            requireBackupReady()
            val dao = database.dao()
            if (operationRunning.value || dao.localChanges().isNotEmpty()) {
                dao.enqueueLocalChange(change)
            } else {
                try {
                    applyChange(change)
                } catch (_: RunLockedException) {
                    dao.enqueueLocalChange(change)
                }
            }
        }
        if (!operationRunning.value) flushLocalChanges()
        sync.refresh()
    }

    private suspend fun flushLocalChanges(
        editor: CollectorRuntime.LocalEditor? = null, deferScheduleUpdate: Boolean = editor != null
    ) = withContext(Dispatchers.IO) {
        localChangesLock.withLock { flushLocalChangesLocked(editor, deferScheduleUpdate) }
    }

    private suspend fun flushLocalChangesLocked(editor: CollectorRuntime.LocalEditor?, deferScheduleUpdate: Boolean) {
        if (recovery.value != BackupRecovery.READY) return
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

    internal suspend fun flushLocalChanges() = flushLocalChanges(null)

    internal fun observeQueuedSettings() = database.dao().observeQueuedChanges()

    internal fun queuedSettings(changes: List<PendingLocalChange>): List<InboxRequest.SettingsSet> =
        changes.filter { it.configName == null }.flatMap { change ->
            runCatching { contractJson.decodeFromString<List<InboxRequest>>(change.json) }.getOrDefault(emptyList())
                .filterIsInstance<InboxRequest.SettingsSet>()
        }

    private suspend fun applyChange(
        change: PendingLocalChange, editor: CollectorRuntime.LocalEditor? = null, deferScheduleUpdate: Boolean = false
    ) {
        if (change.configName != null) {
            val name = change.configName.removePrefix("patch:")
            if (name == "criteria") {
                fun applyCriteria(local: CollectorRuntime.LocalEditor) {
                    val original = currentCriteria()
                    val body = mergeCriteria(original, Json.decodeFromString<Map<String, String>>(change.json))
                    local.applyChanges(listOf(InboxRequest.PromptSet(collector.data.DataBranch.VERSION, requestId(),
                        java.time.Instant.now().toString(), "criteria", body, promptHash(original))))
                }
                if (editor == null) runtime().editLocal(::applyCriteria) else applyCriteria(editor)
            } else if (name.startsWith("source:") || name.startsWith("source-patch:")) {
                fun replaceSource(local: CollectorRuntime.LocalEditor) {
                    val loader = collector.config.ConfigLoader
                    val config = loader.load(paths.sourcesConfig)
                    val current = config.sources.first { it.sourceId == name.substringAfter(':') }
                    val source = if (name.startsWith("source-patch:"))
                        mergeSourcePatch(current, Json.parseToJsonElement(change.json).jsonObject)
                    else loader.json.decodeFromString(collector.config.SourceConfig.serializer(), change.json)
                    val updated = sourceReplacement(source, config)
                    local.saveConfig("sources.local.json", loader.json.encodeToString(collector.config.SourcesFile.serializer(), updated))
                }
                if (editor == null) runtime().editLocal(::replaceSource) else replaceSource(editor)
            } else if (name != change.configName) {
                check(name == "pipeline.local.json")
                fun applyPatch(local: CollectorRuntime.LocalEditor) {
                    val original = paths.pipelineConfig.takeIf { it.isFile }?.let {
                        Json.parseToJsonElement(it.readText()).jsonObject
                    }.orEmpty()
                    val patch = Json.parseToJsonElement(change.json).jsonObject
                    local.saveConfig(name, mergePipelinePatch(JsonObject(original), patch).toString())
                }
                if (editor == null) runtime().editLocal(::applyPatch) else applyPatch(editor)
            } else if (editor == null) runtime().saveLocalConfig(name, change.json)
            else editor.saveConfig(name, change.json)
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

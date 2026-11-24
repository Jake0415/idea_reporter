package app.ideacollector

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import collector.runtime.Backup
import collector.runtime.RunLockedException
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class BackupState(
    val working: Boolean = false,
    val draft: BackupDraft? = null,
    val message: Int? = null,
    val warning: Boolean = false
)

internal data class BackupDraft(val file: File, val summary: Backup.Preview)

@Composable
internal fun BackupSettings(app: CollectorApp) {
    val running by app.operationRunning.collectAsState()
    val state by app.backupState.collectAsState()

    fun work(failure: Int, block: suspend () -> BackupState) {
        if (app.backupState.value.working) return
        app.backupState.value = BackupState(working = true)
        app.appScope.launch {
            app.backupState.value = try {
                app.requireBackupReady()
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: RunLockedException) {
                BackupState(message = R.string.backup_busy)
            } catch (_: Backup.TooLarge) {
                BackupState(message = R.string.backup_too_large, warning = true)
            } catch (_: Exception) {
                BackupState(message = failure, warning = true)
            }
        }
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) work(R.string.backup_export_error) {
            withContext(Dispatchers.IO) {
                val file = File.createTempFile("backup-transfer-", ".json", app.cacheDir)
                try {
                    app.runtime().withRunLock {
                        if (app.operationRunning.value) throw RunLockedException()
                        file.outputStream().use { Backup.write(app.paths, it, privateValues = app.backupPrivateValues()) }
                    }
                    checkNotNull(app.contentResolver.openOutputStream(uri, "wt")).use { output ->
                        file.inputStream().use { it.copyTo(output) }
                    }
                } finally {
                    file.delete()
                }
            }
            BackupState(message = R.string.backup_exported)
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work(R.string.backup_invalid) {
            if (app.operationRunning.value) throw RunLockedException()
            val draft = withContext(Dispatchers.IO) {
                val file = File.createTempFile("backup-transfer-", ".json", app.cacheDir)
                try {
                    checkNotNull(app.contentResolver.openInputStream(uri)).use { Backup.copy(it, file) }
                    val backup = file.inputStream().use(Backup::read)
                    BackupDraft(file, Backup.preview(app.paths, backup))
                } catch (e: Exception) {
                    file.delete()
                    throw e
                }
            }
            BackupState(draft = draft)
        }
    }

    HorizontalDivider()
    Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.titleMedium)
    Small(R.string.backup_help)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Primary(R.string.backup_export, !running && !state.working) {
            export.launch("idea-collector-backup-${LocalDate.now()}.json")
        }
        OutlinedButton(
            onClick = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            modifier = Modifier.heightIn(min = 48.dp), enabled = !running && !state.working,
            shape = MaterialTheme.shapes.small,
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.tertiary)
        ) { Text(stringResource(R.string.backup_import)) }
    }
    val announcement = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    if (running) Box(announcement) { Notice(stringResource(R.string.backup_busy)) }
    state.message?.takeUnless { running && it == R.string.backup_busy }?.let {
        Box(announcement) { Notice(stringResource(it), warning = state.warning) }
    }
    Spacer(Modifier.height(24.dp))

    state.draft?.let { draft ->
        fun dismiss() {
            draft.file.delete()
            app.backupState.value = BackupState()
        }
        val data = draft.summary
        val period = if (data.from == null) stringResource(R.string.backup_no_period)
            else stringResource(R.string.backup_period, data.from.toString(), data.to.toString())
        AlertDialog(
            onDismissRequest = ::dismiss,
            title = { Text(stringResource(R.string.backup_preview)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.backup_preview_details, data.cards, data.tracked, period))
                }
            },
            dismissButton = { Secondary(R.string.cancel, onClick = ::dismiss) },
            confirmButton = {
                Primary(R.string.backup_import_confirm, !running && !state.working) {
                    work(R.string.backup_import_error) {
                        try {
                            if (!app.operationRunning.compareAndSet(expect = false, update = true)) throw RunLockedException()
                            val marked = try {
                                withContext(Dispatchers.IO) {
                                    app.runtime().withRunLock {
                                        val backup = draft.file.inputStream().use(Backup::read)
                                        try {
                                            Backup.restore(app.paths, backup) { app.sync.refresh(requireComplete = it) }
                                        } catch (e: Exception) {
                                            app.checkBackupRollback()
                                            throw e
                                        }
                                    }
                                }
                                runCatching { app.schedule.deferScheduleUpdate() }.isSuccess
                            } finally {
                                app.finishOperation(releaseReservation = false)
                            }
                            if (marked && !app.schedule.hasPendingUpdate()) BackupState(message = R.string.backup_imported)
                            else BackupState(message = R.string.backup_alarm_pending, warning = true)
                        } finally {
                            draft.file.delete()
                        }
                    }
                }
            }
        )
    }
}

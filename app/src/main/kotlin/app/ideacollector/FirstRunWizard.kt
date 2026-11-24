package app.ideacollector

import kotlinx.coroutines.launch
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import collector.budget.BudgetSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import app.ideacollector.work.CollectorRunService
import app.ideacollector.github.contractJson
import java.time.LocalTime
import java.time.LocalDate

@Composable
internal fun FirstRunWizard(app: CollectorApp, operation: (suspend () -> Unit) -> Unit,
    estimatedUsd: Double? = null, showRuns: () -> Unit = {}, done: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var restored by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    var time by rememberSaveable { mutableStateOf<Int?>(null) }
    val status = rememberRunStatus(app, step)
    val running by CollectorRunService.running.collectAsState()
    val operationRunning by app.operationRunning.collectAsState()
    val outcome by CollectorRunService.dailyOutcome.collectAsState()
    var started by rememberSaveable { mutableStateOf(false) }
    var recorded by remember { mutableStateOf(false) }
    var cardCount by remember { mutableIntStateOf(0) }
    var mailStatus by remember { mutableStateOf<Int?>(null) }
    var pendingBatch by remember { mutableStateOf(false) }
    var batchCancelled by remember { mutableStateOf(false) }
    val wizardScope = androidx.compose.runtime.rememberCoroutineScope()
    var retryBlocked by remember { mutableStateOf(false) }
    var firstStatusLoaded by remember { mutableStateOf(false) }
    var failure by rememberSaveable { mutableStateOf<Int?>(null) }
    suspend fun checkKeys() {
        checked = app.hasVerifiedKey()
    }
    fun finish() {
        app.finishFirstRun()
        done()
    }
    LaunchedEffect(app) {
        checkKeys()
        if (time == null) time = app.schedule.options().time.toSecondOfDay()
        val target = app.firstRunDate()
        if (step == 0 && target != null) step = 4
        restored = true
    }
    LaunchedEffect(step, running, operationRunning, outcome) {
        if (step == 4 && !running && !operationRunning) {
            val target = app.firstRunDate()
            val date = target ?: LocalDate.now()
            val result = outcome.takeIf { started }
            val runtime = app.runtime()
            recorded = withContext(Dispatchers.IO) {
                if (target == null) app.schedule.recorded(date) else runtime.paths.outDir.resolve("$date.json").isFile
            }
            if (recorded) {
                val (count, mailLabel) = withContext(Dispatchers.IO) {
                    val report = runtime.paths.outDir.resolve("$date.json")
                    val count = if (report.isFile) runCatching {
                        contractJson.decodeFromString(collector.pipeline.PipelineResult.serializer(), report.readText()).report.cardCount
                    }.getOrDefault(0) else 0
                    val mailLabel = if (!app.mailConfigured()) R.string.r3_first_mail_disabled else when (runtime.scheduledMailStatus(date)) {
                        collector.runtime.ScheduledMailStatus.SENT -> R.string.r3_first_mail_sent
                        collector.runtime.ScheduledMailStatus.PENDING -> R.string.r3_mail_waiting
                        collector.runtime.ScheduledMailStatus.FAILED -> R.string.r3_mail_failed
                    }
                    count to mailLabel
                }
                cardCount = count
                mailStatus = mailLabel
            }
            val resumable = !recorded && withContext(Dispatchers.IO) { runtime.hasBatchToResume(date) }
            if (target != null && resumable && !app.schedule.cancelled(date) && !app.schedule.hasBatchContinuation(date)) {
                app.schedule.continueBatch(date, 0, manual = true, policy = androidx.work.ExistingWorkPolicy.KEEP)
            }
            val continuation = app.schedule.hasBatchContinuation(date)
            // 사용자가 묶음 처리를 취소했으면 "대기 중"으로 보이지 않게 하고 [이어받기]를 따로 준다.
            batchCancelled = !recorded && resumable && app.schedule.cancelled(date) && !continuation
            pendingBatch = !recorded && !batchCancelled && (result?.reason == R.string.r3_batch_pending_first ||
                resumable || continuation)
            retryBlocked = !recorded && !pendingBatch && !batchCancelled && date == LocalDate.now() &&
                (result?.reason == R.string.r3_first_retry_blocked || !app.schedule.mayRetryPaid(date) ||
                    withContext(Dispatchers.IO) { runtime.dailyCost(date) > 0 })
            if (target != null && date != LocalDate.now() && !recorded && !pendingBatch && !batchCancelled) failure = R.string.run_failed
            if (result != null || (started && !recorded && !pendingBatch && !batchCancelled)) {
                failure = result?.reason?.takeUnless { result.successful } ?: R.string.run_failed.takeUnless {
                    result?.successful == true
                }
                started = false
            }
            firstStatusLoaded = true
        }
    }
    val next = remember(step) { mutableStateOf<(() -> Unit)?>(null) }
    val skipMail = remember(step) { mutableStateOf<(() -> Unit)?>(null) }
    val budget by produceState(BudgetSettings().dailyCardUsd, app) {
        value = withContext(Dispatchers.IO) { BudgetSettings.load(app.paths.outDir) {}.dailyCardUsd }
    }
    if (!restored) {
        Small(R.string.loading)
        return
    }
    val scroll = remember(step) { ScrollState(0) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.r3_setup), style = MaterialTheme.typography.titleLarge)
            Small(R.string.r3_step, step + 1)
            Text(stringResource(listOf(R.string.r3_keys, R.string.r3_mail, R.string.criteria, R.string.r3_permissions,
                R.string.r3_first)[step]), style = MaterialTheme.typography.titleMedium)
            when (step) {
                0 -> {
                    SecureTokenInput()
                    Small(R.string.r3_key_intro)
                    Small(R.string.r3_key_limit, budget)
                    AiKeys(app, { operation { checkKeys() } }, onboarding = true)
                    GitHubKey(app, true, operation)
                    WizardNext(checked, { step++ }) { next.value = it }
                }
                1 -> MailSettings(app, operation, onboarding = true, next = { next.value = it },
                    skip = { skipMail.value = it }) { step++ }
                2 -> {
                    CriteriaFields(app, operation, onboarding = true, next = { next.value = it }) { step++ }
                }
                3 -> {
                    time?.let { RunTimePicker(it) { selected -> time = selected } }
                    RunPermissions(app, status, notifications = true)
                    WizardNext(time != null, {
                        operation {
                            val saved = app.schedule.options()
                            app.schedule.save(saved.copy(time = LocalTime.ofSecondOfDay(requireNotNull(time).toLong())))
                            step++
                        }
                    }) { next.value = it }
                }
                4 -> {
                    if (estimatedUsd == null || estimatedUsd <= 0) Small(R.string.r3_first_help)
                    else Small(R.string.r3_first_help_estimate, estimatedUsd)
                    if (running) RunProgress()
                    when {
                        recorded -> mailStatus?.let { Small(R.string.r3_first_complete, cardCount, stringResource(it)) }
                        pendingBatch -> Notice(stringResource(R.string.r3_batch_pending_first))
                        batchCancelled -> Notice(stringResource(R.string.r3_batch_cancelled_first), true)
                        retryBlocked -> time?.let {
                            Notice(stringResource(R.string.r3_first_retry_blocked,
                                LocalTime.ofSecondOfDay(it.toLong()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))),
                                true, showRuns)
                        }
                        else -> failure?.let { Notice(stringResource(it), true) }
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            if (step == 1) skipMail.value?.let { Secondary(R.string.r3_mail_skip, alignStart = true, onClick = it) }
            if (step == 2) Secondary(R.string.r3_skip, alignStart = true) { step++ }
            if (step == 4 && firstStatusLoaded && !running && !started && !recorded && !pendingBatch && !retryBlocked && !batchCancelled) {
                Secondary(R.string.r3_run_later, alignStart = true, onClick = ::finish)
            }
        }
        HorizontalDivider()
        if (next.value == null && step in 0..1) {
            Text(stringResource(if (step == 0) R.string.r3_next_key_hint else R.string.r3_next_mail_hint),
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            if (step == 0 || (step == 4 && (started || running || recorded || pendingBatch || retryBlocked || batchCancelled ||
                    app.firstRunDate() != null))) Spacer(Modifier.width(1.dp))
            else OutlinedButton(onClick = { step-- }, enabled = !started && !running) {
                Text(stringResource(R.string.r3_previous))
            }
            val advance = next.value
            if (step != 4) Primary(R.string.r3_next, advance != null) { advance?.invoke() }
            else when {
                running || started -> Primary(R.string.r3_running, false) {}
                recorded || pendingBatch -> Primary(R.string.success, true, ::finish)
                batchCancelled -> Primary(R.string.r3_batch_resume, !operationRunning) {
                    val date = app.firstRunDate() ?: LocalDate.now()
                    wizardScope.launch {
                        app.schedule.clearCancellation(date)
                        runCatching { app.schedule.continueBatch(date, 0, manual = true, policy = androidx.work.ExistingWorkPolicy.KEEP) }
                        batchCancelled = false
                        pendingBatch = true
                    }
                }
                retryBlocked -> Primary(R.string.r3_today_button, true, ::finish)
                else -> Primary(if (failure == null) R.string.r3_first_run else R.string.r3_retry,
                    firstStatusLoaded && checked && !operationRunning) {
                    CollectorRunService.dailyOutcome.value = null
                    val today = LocalDate.now()
                    val date = app.firstRunDate()?.takeIf { it == today || recorded || pendingBatch } ?: today
                    app.rememberFirstRunDate(date)
                    started = CollectorRunService.startDaily(app, date = date, firstRun = true)
                    failure = R.string.run_failed.takeUnless { started }
                }
            }
        }
    }
}

@Composable
internal fun WizardNext(enabled: Boolean, action: () -> Unit, register: ((() -> Unit)?) -> Unit) {
    val latest = rememberUpdatedState(action)
    val invoke = remember { { latest.value() } }
    SideEffect { register(invoke.takeIf { enabled }) }
}

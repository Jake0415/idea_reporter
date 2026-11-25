package app.ideacollector

import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import app.ideacollector.data.AppSettings
import app.ideacollector.github.contractJson
import app.ideacollector.work.CollectorRunService
import app.ideacollector.work.RunNotifications
import app.ideacollector.work.RunOptions
import app.ideacollector.work.RunStatus
import collector.budget.BudgetSettings
import collector.data.DataBranch.VERSION
import collector.data.InboxRequest
import collector.data.ScheduleSettings
import collector.runtime.ScheduledMailStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

@Composable
internal fun RunProgress(cancel: (() -> Unit)? = null) {
    val stage by RunNotifications.currentStage.collectAsState()
    val step = when (stage) {
        R.string.run_collecting -> 1
        R.string.run_classifying -> 2
        R.string.run_writing -> 3
        R.string.run_mailing -> 5
        else -> 4
    }
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    Surface(color = if (dark) androidx.compose.ui.graphics.Color(0xFF0B2A45) else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (dark) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.r3_progress, step, stringResource(stage)), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall)
                cancel?.let { TextButton(onClick = it) { Text(stringResource(R.string.run_cancel), color = MaterialTheme.colorScheme.tertiary) } }
            }
            LinearProgressIndicator(progress = { step / 5f }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary,
                trackColor = if (dark) androidx.compose.ui.graphics.Color(0xFF354655) else MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TodayRunStatus(app: CollectorApp, data: ScreenState, status: RunStatus?, running: Boolean,
    open: (Page, String) -> Unit, blocked: (Int?) -> Unit) {
    val date = LocalDate.now()
    var missingKey by remember { mutableStateOf(false) }
    var mail by remember { mutableStateOf<ScheduledMailStatus?>(null) }
    var overBudget by remember { mutableStateOf(false) }
    val previous by RunNotifications.previousFailures.collectAsState()
    var mailAvailable by remember { mutableStateOf(false) }
    var mailConfigured by remember { mutableStateOf(false) }
    LaunchedEffect(status, running, data) {
        withContext(Dispatchers.IO) {
            missingKey = app.runtimeOverride == null && !app.hasVerifiedKey()
            mail = runCatching {
                val runtime = app.runtime()
                val configured = app.mailConfigured()
                configured to data.daily.firstOrNull()?.report?.runDate?.let(LocalDate::parse)
                    ?.takeIf { configured }?.let(runtime::scheduledMailStatus)
            }.onFailure { if (it is CancellationException) throw it }.getOrNull().also { loaded ->
                mailAvailable = loaded != null
                mailConfigured = loaded?.first == true
            }?.second
            overBudget = runCatching { app.runtime().dailyBudgetExceeded(date) }
                .onFailure { if (it is CancellationException) throw it }.getOrDefault(false)
        }
    }
    status?.let {
        val next = it.next?.let { value -> scheduleTime(value) } ?: stringResource(R.string.r3_disabled)
        val last = (data.runs.firstOrNull()?.second ?: data.daily.firstOrNull()?.report)?.startedAt
            ?.let { value -> runCatching { scheduleTimeValue(value) }.getOrNull() }
        val scheduleText = stringResource(R.string.r3_status, stringResource(R.string.r3_next_run, next),
            stringResource(R.string.r3_last_run, last?.let { scheduleTime(it) } ?: stringResource(R.string.r3_no_run)))
        val mailText = stringResource(when (mail) {
            ScheduledMailStatus.SENT -> R.string.r3_mail_sent
            ScheduledMailStatus.FAILED -> R.string.r3_mail_fix
            ScheduledMailStatus.PENDING -> R.string.r3_mail_waiting
            null -> if (mailConfigured) R.string.r3_mail_waiting else R.string.r3_mail_disabled
        })
        val line = stringResource(R.string.r3_status_mail, scheduleText, mailText)
        if (mail == ScheduledMailStatus.FAILED) FlowRow(
            itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Small(scheduleText)
            TextButton(onClick = { open(Page.OPERATE, "mail") },
                contentPadding = PaddingValues(0.dp), modifier = Modifier.minimumInteractiveComponentSize()) {
                Text(" · " + mailText, color = warningContent, style = MaterialTheme.typography.bodySmall)
            }
        }
        else Box(Modifier.heightIn(min = 48.dp), contentAlignment = androidx.compose.ui.Alignment.CenterStart) {
            Small(if (mailAvailable) line else scheduleText)
        }
    }
    SideEffect { blocked(when {
        missingKey -> R.string.r3_key_warning
        overBudget -> R.string.r3_budget_warning
        else -> null
    }) }
    val missedReason = if (status?.missed != true || running) null else when {
        app.schedule.cancelled(date) -> R.string.r3_cancelled
        app.schedule.wasSystemCancelled(date) -> R.string.r3_system_cancelled
        app.schedule.failed(date) -> R.string.r3_run_failure
        else -> R.string.r3_too_late
    }
    RunWarnings(status, missedReason, missingKey, overBudget, { open(Page.OPERATE, "") },
        { openExactSettings(app) }, { app.schedule.requestBatteryExemption() },
        mailFailed = mail == ScheduledMailStatus.FAILED, mail = { open(Page.OPERATE, "mail") },
        key = { open(Page.OPERATE, "keys") }, budget = { open(Page.OPERATE, "budget") }, previousMailFailures = previous,
        mailHistory = { open(Page.RUNS, "") })
    if (running) RunProgress { CollectorRunService.cancelActive(app) }
}

internal enum class RunWarning { KEY, FAILURE, MISSED, BATTERY, EXACT, MAIL, BUDGET }

internal fun orderedWarnings(status: RunStatus?, missedReason: Int?, missingKey: Boolean,
    overBudget: Boolean, mailFailed: Boolean) = buildList {
    if (missingKey) add(RunWarning.KEY)
    if (missedReason == R.string.r3_run_failure) add(RunWarning.FAILURE)
    else if (missedReason != null) add(RunWarning.MISSED)
    if (status?.batteryExemptionRequired == true) add(RunWarning.BATTERY)
    if (status?.next != null && !status.exact) add(RunWarning.EXACT)
    if (mailFailed) add(RunWarning.MAIL)
    if (overBudget) add(RunWarning.BUDGET)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RunWarnings(status: RunStatus?, missedReason: Int?, missingKey: Boolean, overBudget: Boolean,
    settings: () -> Unit, exact: () -> Unit, battery: () -> Unit = settings,
    mailFailed: Boolean = false, mail: () -> Unit = settings,
    key: () -> Unit = settings, budget: () -> Unit = settings, previousMailFailures: Int = 0, mailHistory: () -> Unit = mail) {
    val warnings = orderedWarnings(status, missedReason, missingKey, overBudget, mailFailed || previousMailFailures > 0)
    var expanded by rememberSaveable { mutableStateOf(false) }
    @Composable fun Warning(warning: RunWarning, more: Int = 0) {
        val (text, action) = when (warning) {
            RunWarning.KEY -> stringResource(R.string.r3_key_warning) to key
            RunWarning.FAILURE, RunWarning.MISSED -> stringResource(R.string.r3_missed,
                stringResource(requireNotNull(missedReason))) to settings
            RunWarning.BATTERY -> stringResource(R.string.r3_battery_warning) to battery
            RunWarning.EXACT -> stringResource(R.string.r3_exact_warning) to exact
            RunWarning.MAIL -> (if (previousMailFailures > 0)
                stringResource(R.string.r3_mail_history, previousMailFailures)
                else stringResource(R.string.r3_mail_fix)) to if (previousMailFailures > 0) mailHistory else mail
            RunWarning.BUDGET -> stringResource(R.string.r3_budget_warning) to budget
        }
        if (more == 0) Notice(text, true) { expanded = false; action() }
        else Surface(color = warningBackground, contentColor = warningContent, shape = MaterialTheme.shapes.medium) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = action, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) {
                    Text(text, Modifier.fillMaxWidth(), color = warningContent,
                        style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(start = 8.dp)) {
                    Text(stringResource(R.string.r3_warnings_more, more), color = warningContent)
                }
            }
        }
    }
    warnings.firstOrNull()?.let { Warning(it, warnings.size - 1) }
    if (expanded && warnings.isNotEmpty()) ModalBottomSheet(onDismissRequest = { expanded = false }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.r3_warning_title), style = MaterialTheme.typography.titleMedium)
            warnings.forEach { Warning(it) }
        }
    }
}

private fun scheduleTimeValue(value: String) = Instant.parse(value).atZone(ZonedDateTime.now().zone)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PhoneOperations(app: CollectorApp, settings: AppSettings, data: ScreenState,
    operation: (suspend () -> Unit) -> Unit, section: String, settingsReadError: Boolean, onSaved: () -> Unit) {
    val mailSection = remember { BringIntoViewRequester() }
    val keySection = remember { BringIntoViewRequester() }
    val budgetSection = remember { BringIntoViewRequester() }
    val queued by remember(app) { app.observeQueuedSettings() }.collectAsState(emptyList())
    val queuedSettings = remember(queued) { app.queuedSettings(queued) }
    val running by CollectorRunService.running.collectAsState()
    val status = rememberRunStatus(app, running, settings, data)
    val failed by app.failedLocalChanges.collectAsState(emptyList())
    var discard by rememberSaveable { mutableStateOf(false) }
    if (failed.isNotEmpty()) {
        Surface(color = warningBackground, contentColor = warningContent, shape = MaterialTheme.shapes.medium) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.r3_failed_changes, failed.size), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall)
                Secondary(R.string.r3_retry) { operation { app.resolveLocalChanges(failed.map { it.id }, retry = true) } }
                Secondary(R.string.r3_discard) { discard = true }
            }
        }
        if (discard) AlertDialog(onDismissRequest = { discard = false },
            title = { Text(stringResource(R.string.r3_discard_confirm)) },
            dismissButton = { Secondary(R.string.cancel) { discard = false } },
            confirmButton = { Primary(R.string.r3_discard) {
                discard = false
                operation { app.resolveLocalChanges(failed.map { it.id }, retry = false) }
            } })
    }
    val schedule = remember(data) { runCatching { data.file("settings/schedule.json")?.let {
        contractJson.decodeFromString<ScheduleSettings>(it)
    } } }
    val budget = remember(data) { runCatching { data.file("settings/budget.json")?.let {
        contractJson.decodeFromString<BudgetSettings>(it)
    } ?: BudgetSettings() } }
    @Composable fun CredentialsAndModels() {
        HorizontalDivider()
        Text(stringResource(R.string.r3_mail), Modifier.bringIntoViewRequester(mailSection),
            style = MaterialTheme.typography.titleMedium)
        MailSettings(app, operation)
        HorizontalDivider()
        Text(stringResource(R.string.r3_keys), Modifier.bringIntoViewRequester(keySection), style = MaterialTheme.typography.titleMedium)
        var models by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
        AiKeys(app, {}, onModels = { key, available -> models = models + (key to available) })
        GitHubKey(app, false, operation)
        ModelSettings(app, data, queued, models, operation, onSaved)
    }
    LaunchedEffect(section) {
        when (section) {
            "mail" -> mailSection.bringIntoView()
            "keys" -> keySection.bringIntoView()
            "budget" -> budgetSection.bringIntoView()
        }
    }
    if (schedule.isFailure || budget.isFailure || settingsReadError) {
        Notice(stringResource(R.string.settings_read_error), true)
        CredentialsAndModels()
    } else {
        val saved = remember(queuedSettings, budget) {
            val base = budget.getOrThrow()
            val merged = queuedSettings.fold(Json.encodeToJsonElement(BudgetSettings.serializer(), base).jsonObject) { value, change ->
                JsonObject(value + change.budget.orEmpty())
            }
            Json.decodeFromJsonElement(BudgetSettings.serializer(), merged)
        }
        val savedEnabled = queuedSettings.lastOrNull { it.schedule != null }?.schedule?.enabled
            ?: schedule.getOrNull()?.enabled ?: true
        var enabled by rememberSaveable(savedEnabled) { mutableStateOf(savedEnabled) }
        var seconds by rememberSaveable(settings.runTimeSeconds) { mutableIntStateOf(settings.runTimeSeconds.toInt()) }
        var wifi by rememberSaveable(settings.wifiOnly) { mutableStateOf(settings.wifiOnly) }
        var charging by rememberSaveable(settings.chargingOnly) { mutableStateOf(settings.chargingOnly) }
        var batch by rememberSaveable(settings.batch) { mutableStateOf(settings.batch) }
        var daily by rememberSaveable(saved.dailyCardUsd) { mutableStateOf(saved.dailyCardUsd.toString()) }
        var monthly by rememberSaveable(saved.monthlyTotalUsd) { mutableStateOf(saved.monthlyTotalUsd?.toString().orEmpty()) }
        var analysis by rememberSaveable(saved.analysisRequestUsd) { mutableStateOf(saved.analysisRequestUsd.toString()) }
        var requested by rememberSaveable(saved.requestCardUsd) { mutableStateOf(saved.requestCardUsd.toString()) }
        var requestedDaily by rememberSaveable(saved.requestDailyUsd) { mutableStateOf(saved.requestDailyUsd.toString()) }
        Text(stringResource(R.string.r3_schedule), style = MaterialTheme.typography.titleMedium)
        RunOption(R.string.daily_enabled, enabled) { enabled = it }
        RunTimePicker(seconds) { seconds = it }
        status?.let { Small(R.string.r3_next_run, it.next?.let { next -> scheduleTime(next) } ?: stringResource(R.string.r3_disabled)) }
        RunOption(R.string.run_wifi, wifi) { wifi = it }
        RunOption(R.string.run_charging, charging) { charging = it }
        Text(stringResource(R.string.r3_permission_section), style = MaterialTheme.typography.titleMedium)
        RunPermissions(app, status, notifications = true)
        if (app.schedule.hasPendingUpdate()) Small(R.string.r3_pending_schedule)
        CredentialsAndModels()
        HorizontalDivider()
        Text(stringResource(R.string.r3_budget), Modifier.bringIntoViewRequester(budgetSection), style = MaterialTheme.typography.titleMedium)
        val requestedBlocked = remember { mutableStateOf(false) }
        LaunchedEffect(app) {
            // 읽기 실패로 설정 화면이 꺼지지 않게 한다. 실행 직전에 core가 차단 표식을 다시 확인한다.
            requestedBlocked.value = try {
                withContext(Dispatchers.IO) { app.runtime().requestedCardsBlocked() }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }
        }
        if (requestedBlocked.value) {
            Notice(stringResource(R.string.request_card_settlement_block_here), warning = true)
            Secondary(R.string.request_card_release) {
                operation {
                    withContext(Dispatchers.IO) { app.runtime().releaseRequestedCards() }
                    requestedBlocked.value = false
                }
            }
        }
        Field(daily, { daily = it }, R.string.daily_budget, true, help = R.string.budget_daily_card_help, decimal = true)
        Field(monthly, { monthly = it }, R.string.r3_monthly, true, help = R.string.r3_monthly_hint, decimal = true)
        Field(analysis, { analysis = it }, R.string.r3_analysis_limit, true, decimal = true)
        Field(requested, { requested = it }, R.string.request_card_limit, true, decimal = true)
        Field(requestedDaily, { requestedDaily = it }, R.string.request_card_daily_limit, true,
            help = R.string.request_card_daily_limit_help, decimal = true)
        val requestedDailyAmount = budgetAmount(requestedDaily)
        val dailyAmount = budgetAmount(daily)
        val monthlyAmount = budgetAmount(monthly)
        val analysisAmount = budgetAmount(analysis)
        val requestedAmount = budgetAmount(requested)
        val valid = dailyAmount != null && analysisAmount != null && requestedAmount != null && requestedDailyAmount != null && (monthly.isBlank() || monthlyAmount != null)
        Text(stringResource(R.string.r3_advanced), style = MaterialTheme.typography.titleMedium)
        RunOption(R.string.run_batch, batch) { batch = it }
        if (batch) Small(R.string.r3_batch_hint)
        if (!valid) Small(R.string.budget_invalid)
        Primary(R.string.save, valid) {
            operation {
                val changes = buildMap<String, JsonElement> {
                    if (dailyAmount?.toDouble() != saved.dailyCardUsd) put("dailyCardUsd", JsonPrimitive(dailyAmount))
                    if (monthlyAmount?.toDouble() != saved.monthlyTotalUsd) put("monthlyTotalUsd", monthlyAmount?.let(::JsonPrimitive) ?: JsonNull)
                    if (analysisAmount?.toDouble() != saved.analysisRequestUsd) put("analysisRequestUsd", JsonPrimitive(analysisAmount))
                    if (requestedAmount?.toDouble() != saved.requestCardUsd) put("requestCardUsd", JsonPrimitive(requestedAmount))
                    if (requestedDailyAmount?.toDouble() != saved.requestDailyUsd) put("requestDailyUsd", JsonPrimitive(requestedDailyAmount))
                }
                val scheduleChange = ScheduleSettings(enabled).takeIf { enabled != savedEnabled }
                if (changes.isNotEmpty() || scheduleChange != null) app.submit(listOf(InboxRequest.SettingsSet(
                    VERSION, requestId(), Instant.now().toString(), changes.takeIf { it.isNotEmpty() }?.let(::JsonObject),
                    scheduleChange)))
                val options = RunOptions(LocalTime.ofSecondOfDay(seconds.toLong()), wifi, charging, batch)
                val changedSchedule = options != app.schedule.options()
                if (changedSchedule) app.schedule.save(options)
                if (changes.isNotEmpty() || scheduleChange != null || changedSchedule) onSaved()
            }
        }
    }

    BackupSettings(app)
}

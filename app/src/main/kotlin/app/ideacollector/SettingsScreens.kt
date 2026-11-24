@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.ideacollector

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.ideacollector.data.RequestRow
import app.ideacollector.github.contractJson
import app.ideacollector.github.sha256
import app.ideacollector.work.requestNotifications
import app.ideacollector.work.RunOptions
import app.ideacollector.work.CollectorRunService
import collector.budget.BudgetSettings
import collector.data.DataBranch.VERSION
import collector.data.InboxRequest
import collector.data.ScheduleSettings
import collector.model.RunReport
import collector.prompt.PromptCatalog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import collector.config.ConfigLoader
import collector.config.PipelineConfig
import java.time.Instant

@Composable
internal fun Field(value: String, update: (String) -> Unit, label: Int, singleLine: Boolean = false,
    secret: Boolean = false, monospace: Boolean = false, enabled: Boolean = true) {
    OutlinedTextField(value, update, Modifier.fillMaxWidth(), label = { Text(stringResource(label)) },
        enabled = enabled,
        singleLine = singleLine, maxLines = if (monospace) 10 else Int.MAX_VALUE, shape = MaterialTheme.shapes.small,
        keyboardOptions = if (secret) androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password
        ) else androidx.compose.foundation.text.KeyboardOptions.Default,
        textStyle = if (monospace) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            else MaterialTheme.typography.bodyMedium,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.tertiary,
            focusedLabelColor = MaterialTheme.colorScheme.tertiary, cursorColor = MaterialTheme.colorScheme.tertiary))
}

@Composable private fun SecureTokenInput() {
    val window = (LocalContext.current as Activity).window
    DisposableEffect(window) {
        val flag = android.view.WindowManager.LayoutParams.FLAG_SECURE
        val alreadySecure = window.attributes.flags and flag != 0
        window.addFlags(flag)
        onDispose { if (!alreadySecure) window.clearFlags(flag) }
    }
}

@Composable internal fun Connection(app: CollectorApp, operation: (suspend () -> Unit) -> Unit,
    done: () -> Unit) {
    SecureTokenInput()
    val activity = LocalContext.current as Activity
    val labels = listOf(
        "ANTHROPIC_API_KEY" to R.string.key_anthropic, "OPENAI_API_KEY" to R.string.key_openai,
        "GITHUB_TOKEN" to R.string.token, "SMTP_USERNAME" to R.string.key_smtp_username,
        "SMTP_PASSWORD" to R.string.key_smtp_password, "MAIL_TO" to R.string.key_mail_to)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        labels.forEach { (name, label) ->
            var value by remember { mutableStateOf("") }
            Field(value, { value = it }, label, singleLine = true, secret = true)
            Primary(R.string.save, value.isNotBlank()) {
                operation { check(app.secrets.set(name, value.trim())); value = "" }
            }
        }
        Secondary(R.string.run_notifications) { requestNotifications(activity, 52) }
        Secondary(R.string.back) { done() }
    }
}

@Composable internal fun Operations(app: CollectorApp, settings: app.ideacollector.data.AppSettings, data: ScreenState,
    send: (List<InboxRequest>) -> Unit, retry: (RequestRow) -> Unit, operation: (suspend () -> Unit) -> Unit,
    settingsReadError: Boolean) {
    val schedule = runCatching { data.file("settings/schedule.json")?.let { contractJson.decodeFromString<ScheduleSettings>(it) } }
    val budgetSettings = runCatching { data.file("settings/budget.json")?.let { contractJson.decodeFromString<BudgetSettings>(it) } }
    val readError = schedule.isFailure || budgetSettings.isFailure || settingsReadError
    if (readError) {
        Notice(stringResource(R.string.settings_read_error), warning = true)
    }
    val savedSchedule = schedule.getOrNull()
    val savedBudget = budgetSettings.getOrNull()
    val requests = data.requestsOf<InboxRequest.SettingsSet>()
    val pending = requests.filter { it.status in optimisticStatuses }.mapNotNull { data.payload(it) as? InboxRequest.SettingsSet }
    val currentEnabled = pending.firstNotNullOfOrNull { it.schedule?.enabled } ?: savedSchedule?.enabled ?: true
    val currentBudget = pending.firstNotNullOfOrNull { it.budget?.get("dailyCardUsd")?.toString() }
        ?: (savedBudget ?: BudgetSettings()).dailyCardUsd.toString()
    var enabledDraft by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var budgetDraft by rememberSaveable { mutableStateOf<String?>(null) }
    val enabled = enabledDraft ?: currentEnabled
    val budget = budgetDraft ?: currentBudget
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.daily_enabled), Modifier.weight(1f))
        Switch(enabled, { enabledDraft = it.takeUnless { value -> value == currentEnabled } },
            modifier = Modifier.heightIn(min = 48.dp))
    }
    Field(budget, { budgetDraft = it.takeUnless { value -> value == currentBudget } }, R.string.daily_budget, true)
    val amount = budget.toBigDecimalOrNull()
    val valid = amount != null && amount.signum() >= 0 && amount.stripTrailingZeros().scale() <= 2 && amount.toDouble().isFinite()
    val budgetChanged = amount?.compareTo(currentBudget.toBigDecimal()) != 0
    val saveEnabled = !readError && valid && (enabled != currentEnabled || budgetChanged)
    Primary(R.string.save, saveEnabled) {
        send(listOf(InboxRequest.SettingsSet(VERSION, requestId(), Instant.now().toString(),
            budget = if (budgetChanged) JsonObject(mapOf("dailyCardUsd" to JsonPrimitive(amount))) else null,
            schedule = if (enabled != currentEnabled) ScheduleSettings(enabled) else null)))
    }
    if (!saveEnabled) Small(when {
        readError -> R.string.sync_then_retry
        !valid -> R.string.budget_invalid
        else -> R.string.budget_save_hint
    })
    RequestChips(requests, retry)
    HorizontalDivider()
    val savedTime = java.time.LocalTime.ofSecondOfDay(settings.runTimeSeconds)
    var time by rememberSaveable(savedTime) { mutableStateOf(savedTime.toString()) }
    Field(time, { time = it }, R.string.run_time_setting, true)
    var wifi by rememberSaveable(settings.wifiOnly) { mutableStateOf(settings.wifiOnly) }
    var charging by rememberSaveable(settings.chargingOnly) { mutableStateOf(settings.chargingOnly) }
    var batch by rememberSaveable(settings.batch) { mutableStateOf(settings.batch) }
    RunOption(R.string.run_wifi, wifi) { wifi = it }
    RunOption(R.string.run_charging, charging) { charging = it }
    RunOption(R.string.run_batch, batch) { batch = it }
    val parsed = runCatching { java.time.LocalTime.parse(time) }.getOrNull()
    val changed = parsed != savedTime || wifi != settings.wifiOnly || charging != settings.chargingOnly || batch != settings.batch
    Primary(R.string.save, parsed != null && changed) {
        operation { app.schedule.save(RunOptions(checkNotNull(parsed), wifi, charging, batch)) }
    }
    data.latestTrend?.takeIf { it.themes.isNotEmpty() }?.let { AnalysisControls(app, it, operation) }
}

@Composable private fun AnalysisControls(app: CollectorApp, report: collector.trend.TrendReport,
    operation: (suspend () -> Unit) -> Unit) {
    var selected by remember(report.date) { mutableStateOf(emptyList<String>()) }
    var estimate by remember(selected) { mutableStateOf<collector.runtime.AnalysisEstimate?>(null) }
    val running by CollectorRunService.running.collectAsState()
    HorizontalDivider()
    Text(stringResource(R.string.analysis_title), style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        report.themes.filter { it.id.isNotBlank() }.forEach { theme ->
            val included = theme.id in selected
            FilterChip(included, {
                selected = if (included) selected - theme.id else selected + theme.id
            }, label = { Text(theme.name) }, enabled = included || selected.size < 3)
        }
    }
    Primary(R.string.analysis_estimate, selected.isNotEmpty()) {
        val themes = selected
        if (themes.isNotEmpty()) operation {
            val answer = app.estimateAnalysis(themes)
            if (selected == themes) estimate = answer
        }
    }
    estimate?.let { answer ->
        val limit = answer.reservationUsd
        if (answer.canReserve && limit != null) {
            Small(R.string.analysis_estimate_result, java.math.BigDecimal.valueOf(answer.estimatedUsd ?: limit).toPlainString(),
                java.math.BigDecimal.valueOf(limit).toPlainString())
            Primary(R.string.analysis_approve, !running) {
                operation { app.startAnalysis(selected, limit) }
            }
        } else Notice(stringResource(R.string.analysis_unavailable), warning = true)
    }
}

@Composable private fun RunOption(label: Int, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(label), Modifier.weight(1f))
        Switch(enabled, change, modifier = Modifier.heightIn(min = 48.dp))
    }
}

@Composable internal fun Runs(data: ScreenState, open: (Page, String) -> Unit) {
    if (data.runs.isEmpty()) Small(R.string.empty)
    data.runs.forEach { (path, run) ->
        val result = runResult(path, run)
        if (result == R.string.partial) {
            Notice(stringResource(R.string.run_partial_row, runTime(run.startedAt)), warning = true) { open(Page.RUN_DETAIL, path) }
        } else Nav(runTime(run.startedAt), stringResource(R.string.reason_row, stringResource(result),
            stringResource(R.string.meta_cost, run.costUsd))) { open(Page.RUN_DETAIL, path) }
    }
}

private fun runResult(path: String, run: RunReport) = when {
    "/failed/" in path -> R.string.sync_error
    run.issues.isNotEmpty() || run.failedSources.isNotEmpty() -> R.string.partial
    else -> R.string.success
}

@Composable internal fun RunDetail(data: ScreenState, path: String) {
    val report = data.runs.firstOrNull { it.first == path }?.second
        ?: data.daily.firstOrNull { "out/${it.report.runDate}.json" == path }?.report
    if (report == null) { Small(R.string.missing); return }
    val result = runResult(path, report)
    Notice(stringResource(result), warning = result != R.string.success)
    Text(stringResource(R.string.meta, displayDate(report.runDate), runTime(report.startedAt, withDate = false), report.costUsd))
    RunCounts(report)
    Small(if (report.classificationBatchWaitMillis > 0 || report.llmCalls.any { it.stage == "classify" && it.batch })
        R.string.batch_yes else R.string.batch_no)
    if (report.stages.isNotEmpty()) {
        Text(stringResource(R.string.stages), style = MaterialTheme.typography.titleMedium)
        Panel {
            report.stages.forEach {
                Small(R.string.stage_values, stringResource(stageName(it.stage)), it.input, it.output, it.millis)
            }
        }
    }
    if (report.sources.isNotEmpty()) {
        Text(stringResource(R.string.sources), style = MaterialTheme.typography.titleMedium)
        Panel {
            report.sources.forEach {
                Small(R.string.source_outcome, it.source, it.fetched + it.trackingFetched,
                    it.error ?: stringResource(R.string.success))
            }
        }
    }
    Block(R.string.issues, report.issues.joinToString("\n"))
}

private fun stageName(stage: String) = when (stage) {
    "collect" -> R.string.stage_collect
    "filter" -> R.string.stage_filter
    "dedupe" -> R.string.stage_dedupe
    "score" -> R.string.stage_score
    "classify" -> R.string.stage_classify
    "select" -> R.string.stage_select
    "enrich" -> R.string.stage_enrich
    "generate" -> R.string.stage_generate
    "track" -> R.string.track
    "deliver" -> R.string.stage_deliver
    else -> R.string.other
}

@Composable internal fun Prompts(data: ScreenState, open: (Page, String) -> Unit, send: (List<InboxRequest>) -> Unit) {
    val changed = PromptCatalog.entries.keys.filter { promptBody(data.prompt(it)) != promptBody(PromptCatalog.defaultText(it)) }
    var resetAll by rememberSaveable { mutableStateOf(false) }
    if (changed.isNotEmpty()) Nav(stringResource(R.string.prompt_reset_all, changed.size)) { resetAll = true }
    if (resetAll) AlertDialog(onDismissRequest = { resetAll = false },
        title = { Text(stringResource(R.string.prompt_reset_all_confirm)) },
        confirmButton = { Primary(R.string.reset) {
            resetAll = false
            val now = Instant.now()
            send(changed.mapIndexed { index, id ->
                InboxRequest.PromptReset(VERSION, requestId(), now.plusMillis(index.toLong()).toString(),
                    id, promptHash(data.prompt(id)))
            })
        } }, dismissButton = { Secondary(R.string.cancel) { resetAll = false } })
    Small(R.string.prompt_later)
    val ids = listOf("classify") + PromptCatalog.entries.keys.filter { it.startsWith("profile.") } +
        listOf("ideas", "track", "criteria")
    ids.forEach { id ->
        Nav(promptName(id), promptStatus(data, id)) {
            open(Page.PROMPT_EDIT, id)
        }
    }
}

private data class Revision(val text: String, val version: Int, val baseHash: String, val savedAt: String?)

private fun revisions(data: ScreenState, id: String) =
    data.file("config/prompts/$id.versions.json")?.let {
        runCatching {
            contractJson.parseToJsonElement(it).jsonArray.map { entry ->
                val row = entry.jsonObject
                Revision(row.getValue("text").jsonPrimitive.content, row.getValue("version").jsonPrimitive.int,
                    row.getValue("baseHash").jsonPrimitive.content, row["savedAt"]?.jsonPrimitive?.contentOrNull)
            }
        }.getOrNull()
    }.orEmpty()

@Composable private fun promptStatus(data: ScreenState, id: String): String {
    val override = data.file("config/prompts/$id.md") ?: return stringResource(R.string.defaults)
    val revision = revisions(data, id).firstOrNull { it.text == override }
        ?: return stringResource(R.string.modified)
    val base = PromptCatalog.defaultText(id)
    val changed = revision.baseHash.isNotEmpty() && revision.baseHash !in setOf(promptHash(base), base.toByteArray().sha256())
    return stringResource(R.string.reason_row,
        stringResource(if (changed) R.string.base_changed else R.string.modified),
        stringResource(R.string.revision, revision.version, revision.savedAt.orEmpty().take(10)))
}

@Composable internal fun PromptEditor(data: ScreenState, id: String, send: (List<InboxRequest>) -> Unit,
    retry: (RequestRow) -> Unit, back: () -> Unit, save: (InboxRequest.PromptSet, () -> Unit) -> Unit,
    registerLeave: (((() -> Unit) -> Unit)?) -> Unit) {
    val info = PromptCatalog.entries[id]
    if (info == null) { Secondary(R.string.back, back); Small(R.string.missing); return }
    val base = remember(id) { PromptCatalog.defaultText(id) }
    val raw = data.prompt(id)
    val current = visiblePromptBody(raw)
    var draft by rememberSaveable(id) { mutableStateOf(current) }
    var baseline by rememberSaveable(id) { mutableStateOf(current) }
    var baselineHash by rememberSaveable(id) { mutableStateOf(promptHash(raw)) }
    var editing by rememberSaveable(id) { mutableStateOf(false) }
    var compare by rememberSaveable(id) { mutableStateOf(false) }
    var history by rememberSaveable(id) { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf<(() -> Unit)?>(null) }
    var revisionError by rememberSaveable { mutableStateOf(false) }
    var notesExpanded by rememberSaveable { mutableStateOf(false) }
    val requests = data.requests.filter { row -> when (val request = data.payload(row)) {
        is InboxRequest.PromptSet -> request.promptId == id
        is InboxRequest.PromptReset -> request.promptId == id
        else -> false
    } }
    val dirty = editing && draft != baseline
    val leave: (() -> Unit) -> Unit = { action -> if (dirty) discard = action else action() }
    val latestLeave by rememberUpdatedState(leave)
    DisposableEffect(id) {
        registerLeave { latestLeave(it) }
        onDispose { registerLeave(null) }
    }
    BackHandler { leave(back) }
    val backLabel = stringResource(R.string.back)
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        IconButton(onClick = { leave(back) }, modifier = Modifier.size(48.dp)) {
            Text("←", modifier = Modifier.clearAndSetSemantics { contentDescription = backLabel })
        }
        Text(stringResource(R.string.prompt_edit), style = MaterialTheme.typography.titleLarge)
    }
    Text(promptName(id), style = MaterialTheme.typography.titleMedium)
    Small(promptStatus(data, id))
    Small(R.string.prompt_io, info.input, info.output)
    Notice(stringResource(R.string.prompt_format))
    Small(R.string.prompt_later)
    RequestChips(requests, retry)
    val rejected = requests.firstOrNull()?.takeIf { it.status == "REJECTED" && it.reason == "Prompt baseHash does not match" }
        ?.let(data::payload) as? InboxRequest.PromptSet
    if (rejected != null) TextButton(onClick = {
        baseline = current
        baselineHash = promptHash(raw)
        draft = visiblePromptBody(rejected.body)
        editing = true
        compare = true
    }) { Text(stringResource(R.string.prompt_rebase)) }
    Small(R.string.char_count, if (editing) draft.length else current.length, 20_000)
    FlowRow {
        if (editing) Primary(R.string.save, dirty && promptBody(draft).isNotBlank() && draft.length <= 20_000) {
            val submitted = draft
            save(InboxRequest.PromptSet(VERSION, requestId(), Instant.now().toString(), id, submitted, baselineHash)) {
                if (draft == submitted) editing = false
            }
        }
        else Secondary(R.string.edit) { baseline = current; baselineHash = promptHash(raw); draft = current; editing = true }
        Secondary(R.string.compare) { compare = !compare }
        if (!editing && current != visiblePromptBody(base)) Secondary(R.string.reset) { resetting = true }
        if (!editing && revisions(data, id).isNotEmpty()) Secondary(R.string.revisions) { history = !history }
    }
    if (editing) {
        Field(draft, { draft = it }, R.string.prompt_body, monospace = true)
        when {
            draft.length > 20_000 -> Small(R.string.prompt_too_long)
            promptBody(draft).isBlank() -> Small(R.string.prompt_empty)
            !dirty -> Small(R.string.prompt_unchanged)
        }
    }
    if (revisionError) Notice(stringResource(R.string.prompt_revision_too_long), warning = true)
    if (history) revisions(data, id).forEach { revision ->
        Nav(stringResource(R.string.revision, revision.version, revision.savedAt.orEmpty().take(10)),
            stringResource(R.string.load_revision)) {
            revisionError = revision.text.length > 20_000
            if (!revisionError) {
                baseline = current; baselineHash = promptHash(raw); draft = visiblePromptBody(revision.text)
                editing = true; history = false
            }
        }
    }
    if (compare) {
        val before = if (editing) baseline else visiblePromptBody(base)
        val after = if (editing) draft else current
        val (removed, added) = remember(before, after) { lineChanges(before, after) }
        Block(R.string.removed_lines, removed.joinToString("\n"))
        Block(R.string.added_lines, added.joinToString("\n"))
        if (removed.isEmpty() && added.isEmpty()) Small(R.string.no_changed_lines)
    }
    val notes = remember(raw) { promptComments.findAll(raw).joinToString("\n") { it.value } }
    if (notes.isNotEmpty()) {
        TextButton(onClick = { notesExpanded = !notesExpanded }) {
            Text(stringResource(if (notesExpanded) R.string.prompt_notes_hide else R.string.prompt_notes_show))
        }
        if (notesExpanded) Small(notes)
    }
    if (!editing) Text(current, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
    if (resetting) AlertDialog(onDismissRequest = { resetting = false },
        title = { Text(stringResource(R.string.reset)) },
        confirmButton = { Primary(R.string.reset) {
            resetting = false
            send(listOf(InboxRequest.PromptReset(VERSION, requestId(), Instant.now().toString(), id, promptHash(raw))))
        } }, dismissButton = { Secondary(R.string.cancel) { resetting = false } })
    discard?.let { action -> AlertDialog(onDismissRequest = { discard = null },
        title = { Text(stringResource(R.string.discard_title)) },
        confirmButton = { Primary(R.string.discard) { discard = null; action() } },
        dismissButton = { Secondary(R.string.continue_edit) { discard = null } }) }
}

internal fun lineChanges(before: String, after: String): Pair<List<String>, List<String>> {
    val original = before.lines()
    val updated = after.lines()
    val prefix = original.zip(updated).takeWhile { (a, b) -> a == b }.size
    val suffix = original.drop(prefix).asReversed().zip(updated.drop(prefix).asReversed())
        .takeWhile { (a, b) -> a == b }.size
    val old = original.subList(prefix, original.size - suffix)
    val new = updated.subList(prefix, updated.size - suffix)
    if (old.isEmpty() || new.isEmpty() || old.size.toLong() * new.size > 1_000_000) return old to new
    val common = Array(old.size + 1) { IntArray(new.size + 1) }
    for (i in old.indices.reversed()) for (j in new.indices.reversed()) {
        common[i][j] = if (old[i] == new[j]) 1 + common[i + 1][j + 1]
            else maxOf(common[i + 1][j], common[i][j + 1])
    }
    val removed = mutableListOf<String>()
    val added = mutableListOf<String>()
    var i = 0
    var j = 0
    while (i < old.size && j < new.size) {
        if (old[i] == new[j]) {
            i++
            j++
        } else if (common[i + 1][j] >= common[i][j + 1]) removed += old[i++]
        else added += new[j++]
    }
    removed += old.subList(i, old.size)
    added += new.subList(j, new.size)
    return removed to added
}

@Composable private fun promptName(id: String): String {
    val profile = when {
        "tech-signal" in id -> R.string.tech
        "demand-signal" in id -> R.string.demand
        "market-signal" in id -> R.string.products
        else -> return PromptCatalog.entries.getValue(id).name
    }
    return stringResource(if (id.endsWith(".match")) R.string.prompt_match else R.string.prompt_rubric, stringResource(profile))
}

private fun promptBody(text: String) = text.replaceFirst(Regex("""^(?:\s*<!--.*?-->)+""", RegexOption.DOT_MATCHES_ALL), "").trim()
private val promptComments = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
private fun visiblePromptBody(text: String) = text.replace(promptComments, "").trim()
private fun promptHash(text: String) = promptBody(text).toByteArray().sha256()

@Composable internal fun ReadOnlySources() {
    Small(R.string.read_only)
    Small(R.string.sources_defaults)
    val sourceConfig = remember { runCatching { ConfigLoader.loadDefault() }.getOrNull() }
    if (sourceConfig == null) Small(R.string.error)
    else sourceConfig.sources.forEach { source ->
        val name = when (source.sourceId) {
            "hn" -> R.string.source_hn
            "github" -> R.string.source_github
            "github-issues" -> R.string.source_issues
            "hf" -> R.string.source_hf
            "reddit" -> R.string.source_reddit
            "ph" -> R.string.source_ph
            else -> R.string.source_rss
        }
        Panel {
            Text(stringResource(name), style = MaterialTheme.typography.titleMedium)
            Small(if (source.enabled) R.string.enabled else R.string.disabled)
            Small(R.string.source_values, source.window().toHours(), source.minRawScore, source.maxItems)
        }
    }
}

@Composable internal fun ReadOnlyCriteria(data: ScreenState) {
    Small(R.string.read_only)
    Text(data.prompt("criteria"))
    val defaults = PipelineConfig.defaults
    Panel {
        Text(stringResource(R.string.card_defaults), style = MaterialTheme.typography.titleMedium)
        Small(R.string.card_defaults_values, defaults.ideaCount, defaults.maxCardsPerCategory,
            defaults.maxCandidatesPerTag, defaults.feedbackExamples)
    }
}

@Composable internal fun About() {
    val context = LocalContext.current
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    Small(R.string.version, version)
    Text(stringResource(R.string.licenses), style = MaterialTheme.typography.titleMedium)
    val license = remember(context) {
        runCatching { context.assets.open("licenses/heroicons.txt").bufferedReader().use { it.readText() } }
            .getOrElse { context.getString(R.string.settings_read_error) }
    }
    Text(license)
}

@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.ideacollector

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
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
import app.ideacollector.work.CollectorRunService
import collector.data.DataBranch.VERSION
import collector.data.InboxRequest
import collector.model.RunReport
import collector.prompt.PromptCatalog
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

@Composable
internal fun Field(value: String, update: (String) -> Unit, label: Int, singleLine: Boolean = false,
    secret: Boolean = false, monospace: Boolean = false, enabled: Boolean = true,
    email: Boolean = false, error: Int? = null, hint: String? = null, minLines: Int = 1, help: Int? = null,
    decimal: Boolean = false, modifier: Modifier = Modifier, onDone: (() -> Unit)? = null) {
    OutlinedTextField(value, update, modifier.fillMaxWidth(), label = { Text(stringResource(label)) },
        enabled = enabled, isError = error != null,
        supportingText = (error ?: help)?.let { id -> { Text(stringResource(id)) } },
        placeholder = hint?.let { message -> { Text(message) } },
        singleLine = singleLine, minLines = minLines, maxLines = if (monospace) 10 else Int.MAX_VALUE, shape = MaterialTheme.shapes.small,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = when {
            secret -> androidx.compose.ui.text.input.KeyboardType.Password
            email -> androidx.compose.ui.text.input.KeyboardType.Email
            decimal -> androidx.compose.ui.text.input.KeyboardType.Decimal
            else -> androidx.compose.ui.text.input.KeyboardType.Text
        }, imeAction = if (onDone == null) androidx.compose.ui.text.input.ImeAction.Default
            else androidx.compose.ui.text.input.ImeAction.Done),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = onDone?.let { action -> { action() } }),
        textStyle = if (monospace) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            else MaterialTheme.typography.bodyMedium,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.tertiary,
            focusedLabelColor = MaterialTheme.colorScheme.tertiary, cursorColor = MaterialTheme.colorScheme.tertiary))
}

private val secureInputs = mutableMapOf<android.view.Window, Pair<Int, Boolean>>()

@Composable internal fun SecureTokenInput() {
    val window = (LocalContext.current as Activity).window
    DisposableEffect(window) {
        val flag = android.view.WindowManager.LayoutParams.FLAG_SECURE
        val (count, owned) = secureInputs[window] ?: (0 to (window.attributes.flags and flag == 0))
        secureInputs[window] = count + 1 to owned
        if (owned) window.addFlags(flag)
        onDispose {
            val (active, clear) = secureInputs.getValue(window)
            if (active == 1) {
                secureInputs.remove(window)
                if (clear) window.clearFlags(flag)
            } else secureInputs[window] = active - 1 to clear
        }
    }
}

@Composable internal fun AnalysisControls(app: CollectorApp, viewModel: ScreensViewModel, report: collector.trend.TrendReport,
    budget: String?) {
    var selected by rememberSaveable(report.date) { mutableStateOf(arrayListOf<String>()) }
    var quote by rememberSaveable(report.date) { mutableStateOf<String?>(null) }
    var approved by rememberSaveable { mutableStateOf(false) }
    var quotedSelection by rememberSaveable(report.date) { mutableStateOf<ArrayList<String>?>(null) }
    var quotedBudget by rememberSaveable(report.date) { mutableStateOf<String?>(null) }
    var revision by rememberSaveable(report.date) { mutableIntStateOf(0) }
    var failure by rememberSaveable(report.date) { mutableStateOf<String?>(null) }
    var requote by rememberSaveable(report.date) { mutableStateOf(false) }
    val outcome by CollectorRunService.analysisOutcome.collectAsState()
    LaunchedEffect(outcome) {
        val result = outcome ?: return@LaunchedEffect
        if (!result.successful) {
            requote = result.requote
            if (result.requote) {
                quote = null
                revision++
            } else failure = result.failure ?: ""
        }
        CollectorRunService.analysisOutcome.compareAndSet(result, null)
    }
    val running by app.operationRunning.collectAsState()
    var quoteLocked by remember { mutableStateOf(false) }
    LaunchedEffect(report.date, budget, selected, revision, running) {
        if (quote != null && quotedSelection == selected && quotedBudget == budget) return@LaunchedEffect
        quote = null
        approved = false
        quoteLocked = false
        if (selected.isEmpty() || running) return@LaunchedEffect
        val themes = ArrayList(selected)
        repeat(4) { attempt ->
            val answer = viewModel.analysisQuote(app, report.date, budget, themes, revision)
            quoteLocked = answer.exceptionOrNull() is collector.runtime.RunLockedException
            if (quoteLocked && app.operationRunning.value) return@LaunchedEffect
            if (quoteLocked && attempt < 3) {
                kotlinx.coroutines.delay(2_000)
            } else {
                quoteLocked = false
                answer.onSuccess {
                    quotedSelection = themes
                    quotedBudget = budget
                    quote = contractJson.encodeToString(collector.runtime.AnalysisEstimate.serializer(), it)
                }.onFailure { failure = "" }
                return@LaunchedEffect
            }
        }
    }
    val estimate = quote?.takeIf { quotedSelection == selected && quotedBudget == budget }?.let { contractJson.decodeFromString(collector.runtime.AnalysisEstimate.serializer(), it) }
    HorizontalDivider()
    if (selected.isNotEmpty() && (quoteLocked || (running && quote == null))) Small(R.string.r3_analysis_quote_wait)
    if (report.themes.none { it.candidate && it.id.isNotBlank() }) Small(R.string.r3_analysis_empty)
    Small(R.string.r3_analysis_help)
    if (requote) Notice(stringResource(R.string.r3_analysis_requote), true)
    failure?.let {
        Notice(stringResource(R.string.r3_analysis_failure, it.ifBlank { stringResource(R.string.run_failed) }), true)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        report.themes.filter { it.candidate && it.id.isNotBlank() }.forEach { theme ->
            val included = theme.id in selected
            FilterChip(included, {
                selected = ArrayList(if (included) selected - theme.id else selected + theme.id)
            }, label = { Text(stringResource(R.string.r3_analysis_candidate, theme.name)) }, enabled = included || selected.size < 3)
        }
    }
    estimate?.let { answer ->
        val limit = answer.reservationUsd
        if (answer.canReserve && limit != null) {
            val amount = answer.estimatedUsd ?: limit
            val estimateLabel = if (amount < 0.01) stringResource(R.string.r3_analysis_execute_small)
                else stringResource(R.string.r3_analysis_execute, amount)
            val limitLabel = if (limit < 0.01) stringResource(R.string.r3_analysis_cap_small)
                else stringResource(R.string.r3_analysis_cap, limit)
            val amountLine = stringResource(R.string.analysis_estimate_result, estimateLabel, limitLabel)
            Small(amountLine)
            Primary(R.string.r3_analysis_run, !running) { approved = true }
            if (approved) AlertDialog(onDismissRequest = { approved = false },
                title = { Text(stringResource(R.string.analysis_approve)) },
                text = { Text(amountLine) },
                dismissButton = { Secondary(R.string.cancel) { approved = false } },
                confirmButton = { Primary(R.string.r3_check, !running) {
                    approved = false
                    failure = null
                    requote = false
                    app.startAnalysis(selected, limit, answer.budgetVersion)
                } })
        } else Notice(stringResource(R.string.analysis_unavailable), warning = true)
    }
}

@Composable internal fun RunOption(label: Int, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(enabled, role = Role.Switch, onValueChange = change),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f))
        Switch(enabled, onCheckedChange = null)
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
    val ids = listOf("classify") + PromptCatalog.entries.keys.filter { it.startsWith("profile.") } +
        listOf("ideas", "track", "criteria", "analysis-opportunity")
    ids.forEach { id ->
        val info = PromptCatalog.entries.getValue(id)
        val detail = stringResource(R.string.prompt_io, info.input, info.output)
        Nav(promptName(id), stringResource(R.string.reason_row, promptStatus(data, id), detail)) {
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
    trial: (@Composable (String) -> Unit)? = null,
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
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(promptName(id), style = MaterialTheme.typography.titleMedium)
            Small(promptStatus(data, id))
            Small(R.string.prompt_io, info.input, info.output)
            Notice(stringResource(R.string.prompt_format))
            if (trial == null) {
                Small(R.string.prompt_later)
                RequestChips(requests, retry)
            }
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
                if (!editing) Secondary(R.string.edit) { baseline = current; baselineHash = promptHash(raw); draft = current; editing = true }
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
                TextButton(onClick = { notesExpanded = !notesExpanded },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.tertiary)) {
                    Text(stringResource(if (notesExpanded) R.string.prompt_notes_hide else R.string.prompt_notes_show))
                }
                if (notesExpanded) Small(notes)
            }
            if (!editing) Text(current, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
        }
        if (editing || trial != null) HorizontalDivider()
        if (editing) Primary(R.string.save, dirty && promptBody(draft).isNotBlank() && draft.length <= 20_000) {
            val submitted = draft
            save(InboxRequest.PromptSet(VERSION, requestId(), Instant.now().toString(), id, submitted, baselineHash)) {
                if (draft == submitted) editing = false
            }
        }
        trial?.invoke(if (editing) draft else current)
    }
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
internal fun promptHash(text: String) = promptBody(text).toByteArray().sha256()

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

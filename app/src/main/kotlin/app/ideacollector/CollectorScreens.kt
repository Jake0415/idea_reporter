package app.ideacollector

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ideacollector.data.AppSettings
import app.ideacollector.data.RequestRow
import app.ideacollector.data.LOCAL_REPO
import app.ideacollector.work.CollectorRunService
import collector.data.InboxRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

internal enum class Page(val title: Int) {
    TODAY(R.string.today_title), TRACK(R.string.track), ARCHIVE(R.string.archive), SETTINGS(R.string.settings),
    CARD(R.string.card), CANDIDATES(R.string.candidates_title), TRACK_DETAIL(R.string.track_detail), RUNS(R.string.runs),
    RUN_DETAIL(R.string.run_detail), PROMPTS(R.string.prompts), PROMPT_EDIT(R.string.prompt_edit),
    OPERATE(R.string.operate), SOURCES(R.string.sources), CRITERIA(R.string.criteria), ABOUT(R.string.about),
    SOURCE_DETAIL(R.string.sources), ANALYSIS(R.string.analysis_title), ANALYSIS_RESULT(R.string.r3_analysis_result)
}

internal data class Route(val page: Page, val id: String = "")
private val routeStackSaver = listSaver<List<Route>, String>(
    save = { routes -> routes.flatMap { listOf(it.page.name, it.id) } },
    restore = { items -> items.chunked(2).map {
        Route(if (it[0] == "CONNECT") Page.OPERATE else Page.valueOf(it[0]), it[1])
    } }
)
internal const val SAMPLE_REPO = "@sample"

private data class Tab(val page: Page, val label: Int, val outline: Int, val solid: Int)
private val tabs = listOf(
    Tab(Page.TODAY, R.string.today, R.drawable.ic_home_outline, R.drawable.ic_home_solid),
    Tab(Page.TRACK, R.string.track, R.drawable.ic_track_outline, R.drawable.ic_track_solid),
    Tab(Page.ARCHIVE, R.string.archive, R.drawable.ic_archive_outline, R.drawable.ic_archive_solid),
    Tab(Page.SETTINGS, R.string.settings, R.drawable.ic_settings_outline, R.drawable.ic_settings_solid)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollectorScreens(app: CollectorApp, viewModel: ScreensViewModel, destination: Route?,
    destinationHandled: () -> Unit) {
    val recovery by app.recovery.collectAsState()
    if (recovery != BackupRecovery.READY) {
        Scaffold { padding ->
            Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (recovery == BackupRecovery.PENDING) {
                    Notice(stringResource(R.string.backup_recovery_pending), warning = true)
                    val running by app.operationRunning.collectAsState()
                    Primary(R.string.r3_retry, enabled = !running) { app.retryBackupRecovery() }
                } else Text(stringResource(R.string.loading))
            }
        }
        return
    }
    val recoveryUnavailable by app.recoveryUnavailable.collectAsState()
    if (recoveryUnavailable) {
        AlertDialog(
            onDismissRequest = app::dismissUnavailableRecovery,
            text = { Text(stringResource(R.string.backup_recovery_unavailable)) },
            confirmButton = { TextButton(onClick = app::dismissUnavailableRecovery) { Text(stringResource(android.R.string.ok)) } }
        )
    }
    val activity = LocalContext.current as Activity
    val dao = app.database.dao()
    var settings by remember { mutableStateOf<AppSettings?>(null) }
    LaunchedEffect(dao) { dao.observeSettings().collect { settings = it ?: AppSettings() } }
    val current = settings
    if (current == null) {
        Scaffold { Text(stringResource(R.string.loading), Modifier.padding(it).padding(16.dp)) }
        return
    }
    val repo = if (current.repository == SAMPLE_REPO) SAMPLE_REPO else LOCAL_REPO
    val sample = repo == SAMPLE_REPO
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var stack by rememberSaveable(stateSaver = routeStackSaver) { mutableStateOf(emptyList<Route>()) }
    var leaveEditor by remember { mutableStateOf<((() -> Unit) -> Unit)?>(null) }
    var onboarding by remember(app) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(app) { onboarding = !app.migrateFirstRun() }
    val route = stack.lastOrNull() ?: Route(tabs[tab].page)
    fun go(next: Route) { stack = stack + next }
    fun back() { stack = stack.dropLast(1) }
    BackHandler(stack.isNotEmpty() && route.page != Page.PROMPT_EDIT) { back() }
    LaunchedEffect(destination, leaveEditor, route.page) {
        if (destination != null && (route.page != Page.PROMPT_EDIT || leaveEditor != null)) {
            destinationHandled()
            val move = {
                tab = if (destination.page == Page.TRACK_DETAIL) 1 else 0
                stack = if (destination.page == Page.TODAY) emptyList() else listOf(destination)
            }
            leaveEditor?.invoke(move) ?: move()
        }
    }
    var state by remember(repo) { mutableStateOf<ScreenState?>(null) }
    var readError by remember(repo) { mutableStateOf(false) }
    val syncFlow = remember(repo) { dao.observeState(repo) }
    val syncState by syncFlow.collectAsState(null)
    val scope = rememberCoroutineScope()
    var error by remember(repo) { mutableStateOf<Int?>(null) }
    var runBlockedReason by remember { mutableStateOf<Int?>(null) }
    var syncing by remember(repo) { mutableStateOf(false) }
    val running by CollectorRunService.running.collectAsState()
    var canRun by remember { mutableStateOf(false) }
    LaunchedEffect(route.page, running) {
        if (route.page == Page.TODAY) canRun = app.runtimeOverride != null || app.hasVerifiedKey()
    }
    var confirmRun by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(repo, reload) {
        viewModel.observe(repo).catch { readError = true }.collect { state = it; readError = false }
    }
    fun operation(block: suspend () -> Unit) {
        scope.launch {
            error = null
            try { block() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = R.string.error }
        }
    }
    suspend fun sync() {
        if (sample || syncing) return
        syncing = true
        try {
            app.sync.refresh()
            reload++
        } finally { syncing = false }
    }
    val enqueue: suspend (List<InboxRequest>) -> Unit = { requests ->
        if (sample) app.sync.enqueueSample(repo, requests)
        else app.submit(requests)
    }
    val send: (List<InboxRequest>) -> Unit = { requests -> operation { enqueue(requests) } }
    val retry: (RequestRow) -> Unit = { row -> state?.payload(row)?.let { send(listOf(it.renew())) } }
    val undo: (RequestRow) -> Unit = { row ->
        operation {
            if (sample) check(dao.cancelPendingFeedback(repo, row.requestId) == 1)
            else enqueue(listOf(InboxRequest.FeedbackUndo(collector.data.DataBranch.VERSION, requestId(),
                java.time.Instant.now().toString(), checkNotNull(row.cardId), row.createdAt)))
        }
    }
    val open: (Page, String) -> Unit = { page, id -> go(Route(page, id)) }

    if (onboarding == null && !sample) return
    if (onboarding == true && !sample) {
        Scaffold { padding ->
            Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
                error?.let { Notice(stringResource(it), warning = true) }
                val estimate = state?.daily?.map { it.report.costUsd }?.filter { it > 0 }
                    ?.takeIf { it.isNotEmpty() }?.average()
                var showingRuns by rememberSaveable { mutableStateOf(false) }
                BackHandler(showingRuns) { showingRuns = false }
                if (showingRuns) {
                    Secondary(R.string.back) { showingRuns = false }
                    val data = state
                    if (data != null) Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                        val detail = stack.lastOrNull()?.takeIf { it.page == Page.RUN_DETAIL }
                        if (detail == null) Runs(data, open) else RunDetail(data, detail.id)
                    }
                } else FirstRunWizard(app, ::operation, estimate, showRuns = { showingRuns = true }) {
                    tab = 0
                    stack = emptyList()
                    onboarding = false
                }
            }
        }
        return
    }
    val operationRunning by app.operationRunning.collectAsState()
    val pendingLocal by app.pendingLocalChanges.collectAsState(false)
    val failedLocal by app.failedLocalChanges.collectAsState(emptyList())
    val snackbar = remember { SnackbarHostState() }
    val pendingMessage = stringResource(R.string.r3_saved_pending)
    LaunchedEffect(operationRunning, failedLocal.isNotEmpty()) {
        if (!operationRunning || failedLocal.isNotEmpty()) {
            snackbar.currentSnackbarData?.takeIf { it.visuals.message == pendingMessage }?.dismiss()
        }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        run {
            val backLabel = stringResource(R.string.back)
            val runLabel = stringResource(R.string.run_now)
            TopAppBar(title = {
                if (route.page != Page.CARD) Text(stringResource(if (route.page == Page.SOURCE_DETAIL)
                    sourceName(route.id) else route.page.title))
            }, navigationIcon = {
                if (stack.isNotEmpty()) IconButton(onClick = { leaveEditor?.invoke(::back) ?: back() },
                    modifier = Modifier.size(48.dp)) {
                    Text("←", modifier = Modifier.clearAndSetSemantics { contentDescription = backLabel })
                }
            }, actions = {
                if (route.page == Page.CARD) {
                    val data = state
                    val card = data?.cards?.firstOrNull { it.id == route.id }
                    if (data != null && card != null) Star(data, card, send)
                }
                if (route.page == Page.TODAY) IconButton(
                    onClick = {
                        val reason = runBlockedReason ?: R.string.r3_key_warning.takeUnless { canRun }
                        if (reason == null) confirmRun = true
                        else scope.launch { snackbar.showSnackbar(activity.getString(reason)) }
                    },
                    enabled = !sample && !running && !operationRunning,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(painterResource(R.drawable.ic_refresh),
                        contentDescription = if (running) stringResource(R.string.r3_running) else runLabel,
                        tint = when {
                            running -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            canRun && runBlockedReason == null -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        })
                }
            })
        }
    }, bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            tabs.forEachIndexed { index, item ->
                val selected = index == tab
                NavigationBarItem(selected, {
                    val move = { tab = index; stack = emptyList<Route>() }
                    leaveEditor?.invoke(move) ?: move()
                }, icon = {
                    Icon(painterResource(if (selected) item.solid else item.outline), null)
                }, label = {
                    Text(stringResource(item.label), style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                }, colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.secondaryContainer
                ))
            }
        }
    }) { padding ->
        PullToRefreshBox(isRefreshing = syncing, onRefresh = {
            if (route.page == Page.TODAY) operation { sync() }
        }, modifier = Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            key(route.page.name, route.id) {
                val scroll = rememberScrollState()
                val content = if (route.page in setOf(Page.SOURCE_DETAIL, Page.PROMPT_EDIT, Page.CANDIDATES)) Modifier else Modifier.verticalScroll(scroll)
                val keyboard = if (route.page in setOf(Page.OPERATE, Page.CRITERIA, Page.PROMPT_EDIT)) {
                    Modifier.imePadding()
                } else Modifier
                Column(Modifier.fillMaxSize().then(keyboard).then(content).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    error?.let { Notice(stringResource(it), warning = true) }
                    if (pendingLocal && operationRunning && failedLocal.isEmpty() && route.page in setOf(Page.TODAY, Page.CARD, Page.OPERATE, Page.CRITERIA,
                        Page.SOURCE_DETAIL, Page.PROMPT_EDIT, Page.TRACK, Page.TRACK_DETAIL)) Small(R.string.r3_pending)
                    val data = state
                    when {
                        readError -> Notice(stringResource(R.string.error), warning = true)
                        data == null -> Text(stringResource(R.string.loading))
                        else -> when (route.page) {
                            Page.TODAY -> {
                                val status = if (sample) null else rememberRunStatus(app, running, current, data)
                                if (!sample) TodayRunStatus(app, data, status, running, open,
                                    blocked = { runBlockedReason = it })
                                val noToday = when {
                                    status?.next != null -> stringResource(R.string.no_today, scheduleTime(status.next))
                                    status != null -> stringResource(R.string.no_today_disabled)
                                    else -> stringResource(R.string.before_sync)
                                }
                                TodayContent(data, syncState, open, send, retry, undo, { tab = 1 }, noToday) {
                                    if (sample) SyncStatusNotice(syncState?.status, { go(Route(Page.OPERATE)) }) { operation { sync() } }
                                }
                                if (confirmRun) AlertDialog(onDismissRequest = { confirmRun = false },
                                    title = { Text(stringResource(R.string.confirm_run)) },
                                    confirmButton = { Primary(R.string.run_now) {
                                        confirmRun = false
                                        operation { check(CollectorRunService.startDaily(app)) }
                                    } }, dismissButton = { Secondary(R.string.cancel) { confirmRun = false } },
                                    text = { Text(stringResource(R.string.r3_run_mail_rule)) })
                            }
                            Page.TRACK -> Tracks(data, open, send, retry)
                            Page.ARCHIVE -> Archive(dao, repo, data, open, send, retry, undo)
                            Page.CANDIDATES -> CandidatesScreen(route.id, data.daily, app, sample, open)
                            Page.CARD -> CardDetail(data, route.id, open, send, retry, undo)
                            Page.TRACK_DETAIL -> TrackDetail(data, route.id, open, send, retry)
                            Page.SETTINGS -> {
                                listOf(Page.OPERATE, Page.PROMPTS, Page.SOURCES, Page.CRITERIA, Page.RUNS, Page.ANALYSIS, Page.ABOUT).forEach {
                                    HorizontalDivider()
                                    ListRow(stringResource(it.title)) { go(Route(it)) }
                                }
                                DebugEntry { activity.startActivity(android.content.Intent().setClassName(activity, it)) }
                            }
                            Page.OPERATE -> PhoneOperations(app, current, data, ::operation, route.id,
                                syncState?.issue?.lineSequence()?.any { it.startsWith("settings/") } == true) {
                                if (operationRunning) scope.launch {
                                    snackbar.currentSnackbarData?.dismiss()
                                    if (operationRunning && failedLocal.isEmpty()) snackbar.showSnackbar(pendingMessage)
                                }
                            }
                            Page.SOURCES, Page.SOURCE_DETAIL -> SourceSettings(app, data, route.id, open, ::operation)
                            Page.CRITERIA -> CriteriaFields(app, ::operation)
                            Page.RUNS -> {
                                app.schedule.unpublishedBatchDates().forEach { Small(R.string.run_batch_date_unapplied, it.toString()) }
                                Runs(data, open)
                            }
                            Page.ANALYSIS -> {
                                val trend = data.latestTrend
                                if (trend != null && trend.themes.any { it.candidate }) AnalysisControls(app, viewModel, trend, data.file("settings/budget.json"))
                                else Small(R.string.r3_analysis_empty)
                                data.analyses.forEach { result ->
                                    ListRow(runTime(result.createdAt), result.judgment) { open(Page.ANALYSIS_RESULT, result.id) }
                                }
                            }
                            Page.ANALYSIS_RESULT -> {
                                val result = data.analyses.firstOrNull { it.id == route.id }
                                if (result == null) Small(R.string.missing)
                                else {
                                    Small(R.string.meta_cost, result.actualUsd)
                                    Text(result.judgment)
                                    result.opportunities.forEach { opportunity ->
                                        Panel {
                                            Text(opportunity.validated.name, style = MaterialTheme.typography.titleMedium)
                                            Text(opportunity.validated.problem)
                                        }
                                    }
                                }
                            }
                            Page.RUN_DETAIL -> RunDetail(data, route.id)
                            Page.PROMPTS -> Prompts(data, open, send)
                            Page.PROMPT_EDIT -> PromptEditor(data, route.id, send, retry, ::back,
                                { request, saved -> operation { enqueue(listOf(request)); saved() } },
                                trial = if (sample) null else { body -> PromptTrial(app, viewModel, data, route.id, body) }
                            ) { leaveEditor = it }
                            Page.ABOUT -> About()
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun ListRow(title: String, subtitle: String = "", onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle.isNotBlank()) Small(subtitle)
        }
        Text("›", modifier = Modifier.clearAndSetSemantics { })
    }
}

@Composable internal fun Small(id: Int, vararg args: Any) = Small(stringResource(id, *args))
@Composable internal fun Small(text: String) = Text(text, style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable internal fun Primary(id: Int, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, Modifier.heightIn(min = 48.dp), enabled = enabled, shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(containerColor = strongAccent)) {
        Text(stringResource(id))
    }
}

@Composable internal fun Secondary(id: Int, onClick: () -> Unit) = Secondary(id, false, onClick)

@Composable internal fun Secondary(id: Int, alignStart: Boolean, onClick: () -> Unit) {
    TextButton(onClick, Modifier.heightIn(min = 48.dp),
        contentPadding = if (alignStart) PaddingValues(end = 12.dp) else ButtonDefaults.TextButtonContentPadding,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.tertiary)) {
        Text(stringResource(id))
    }
}

@Composable internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable internal fun Nav(id: Int, subtitle: String = "", onClick: () -> Unit) =
    Nav(stringResource(id), subtitle, onClick)

@Composable internal fun Nav(title: String, subtitle: String = "", onClick: () -> Unit) {
    OutlinedCard(onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle.isNotBlank()) Small(subtitle)
        }
    }
}

@Composable internal fun Notice(text: String, warning: Boolean = false, onClick: (() -> Unit)? = null) {
    val bg = if (warning) warningBackground else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (warning) warningContent else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick),
        color = bg, contentColor = fg, shape = MaterialTheme.shapes.medium) {
        Text(text, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun SyncStatusNotice(status: String?, token: () -> Unit, retry: () -> Unit) {
    when (status) {
        "APP_UPDATE_REQUIRED" -> Notice(stringResource(R.string.update_required), warning = true)
        "NEEDS_AUTH" -> Notice(stringResource(R.string.sync_auth_action), warning = true, onClick = token)
        "ERROR", "PARTIAL" -> Notice(stringResource(R.string.sync_retry_action), warning = true, onClick = retry)
    }
}

@Composable internal fun RequestChips(rows: List<RequestRow>, retry: (RequestRow) -> Unit) {
    rows.forEach { row ->
        val status = if (row.reason == "분석 요청 상태 확인 필요") RequestStatus.NEEDS_CONFIRMATION
            else RequestStatus.from(row.status)
        val label = stringResource(status.label)
        val reason = when (status) {
            RequestStatus.INTERNAL_ERROR -> stringResource(R.string.reason_internal)
            RequestStatus.REJECTED -> row.reason ?: stringResource(R.string.reason_invalid)
            RequestStatus.FAILED -> row.reason
            else -> null
        }
        Notice(if (reason == null) label else stringResource(R.string.reason_row, label, reason),
            warning = reason != null)
        if (status in setOf(RequestStatus.FAILED, RequestStatus.NEEDS_CONFIRMATION, RequestStatus.INTERNAL_ERROR))
            Secondary(R.string.retry) { retry(row) }
    }
}

internal fun InboxRequest.renew(): InboxRequest {
    val id = requestId()
    val at = java.time.Instant.now().toString()
    return when (this) {
        is InboxRequest.Reaction -> copy(requestId = id, createdAt = at)
        is InboxRequest.FeedbackUndo -> copy(requestId = id, createdAt = at)
        is InboxRequest.TrackStart -> copy(requestId = id, createdAt = at)
        is InboxRequest.TrackStop -> copy(requestId = id, createdAt = at)
        is InboxRequest.TrackKeywords -> copy(requestId = id, createdAt = at)
        is InboxRequest.PromptSet -> copy(requestId = id, createdAt = at)
        is InboxRequest.PromptReset -> copy(requestId = id, createdAt = at)
        is InboxRequest.SettingsSet -> copy(requestId = id, createdAt = at)
        is InboxRequest.Analysis -> copy(requestId = id, createdAt = at)
    }
}

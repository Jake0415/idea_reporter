package app.ideacollector

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import app.ideacollector.github.validRepository
import collector.data.InboxRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

internal enum class Page(val title: Int) {
    TODAY(R.string.today_title), TRACK(R.string.track), ARCHIVE(R.string.archive), SETTINGS(R.string.settings),
    CONNECT(R.string.connect), CARD(R.string.card), TRACK_DETAIL(R.string.track_detail), RUNS(R.string.runs),
    RUN_DETAIL(R.string.run_detail), PROMPTS(R.string.prompts), PROMPT_EDIT(R.string.prompt_edit),
    OPERATE(R.string.operate), SOURCES(R.string.sources), CRITERIA(R.string.criteria), ABOUT(R.string.about)
}

internal data class Route(val page: Page, val id: String = "")
private val routeStackSaver = listSaver<List<Route>, String>(
    save = { routes -> routes.flatMap { listOf(it.page.name, it.id) } },
    restore = { items -> items.chunked(2).map { Route(Page.valueOf(it[0]), it[1]) } }
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
    val activity = LocalContext.current as Activity
    val dao = app.database.dao()
    var settings by remember { mutableStateOf<AppSettings?>(null) }
    LaunchedEffect(dao) { dao.observeSettings().collect { settings = it ?: AppSettings() } }
    val current = settings
    if (current == null) {
        Scaffold { Text(stringResource(R.string.loading), Modifier.padding(it).padding(16.dp)) }
        return
    }
    val repo = current.repository
    val sample = repo == SAMPLE_REPO
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var stack by rememberSaveable(stateSaver = routeStackSaver) { mutableStateOf(emptyList<Route>()) }
    var leaveEditor by remember { mutableStateOf<((() -> Unit) -> Unit)?>(null) }
    val route = stack.lastOrNull() ?: Route(tabs[tab].page)
    fun go(next: Route) { stack = stack + next }
    fun back() { stack = stack.dropLast(1) }
    LaunchedEffect(Unit) {
        val preferences = app.getSharedPreferences("onboarding", android.content.Context.MODE_PRIVATE)
        if (!preferences.getBoolean("shown", false)) {
            preferences.edit().putBoolean("shown", true).apply()
            if (repo.isEmpty() && stack.isEmpty()) go(Route(Page.CONNECT))
        }
    }
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
    val dispatch by remember(repo) { dao.observeDispatch(repo) }.collectAsState(null)
    val scope = rememberCoroutineScope()
    var error by remember(repo) { mutableStateOf<Int?>(null) }
    var syncing by remember(repo) { mutableStateOf(false) }
    val running = dispatch?.active == true
    var actionsKnown by remember(repo) { mutableStateOf(false) }
    var acting by remember(repo) { mutableStateOf(false) }
    var tokenAvailable by remember(repo) { mutableStateOf(false) }
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
        if (!validRepository(repo) || syncing) return
        syncing = true
        try {
            val news = app.sync.sync(repo, app.secrets.get(current.tokenName))
            if (current.notifications) app.notifications.publish(news)
            app.schedule.outbox()
            reload++
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { app.sync.recordSyncFailure(repo, (e as? app.ideacollector.github.GitHubFailure)?.status == 401) }
        finally { syncing = false }
    }
    LaunchedEffect(repo, current.tokenName, route.page) {
        if (route.page != Page.TODAY || !validRepository(repo)) return@LaunchedEffect
        while (true) {
            val token = app.secrets.get(current.tokenName)
            tokenAvailable = token != null
            if (token != null) {
                try {
                    val previouslyActive = dao.dispatch(repo)?.active == true
                    val active = app.sync.dailyActive(repo, token)
                    if (previouslyActive && !active) sync()
                    actionsKnown = true
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { actionsKnown = false }
            } else actionsKnown = false
            delay(15_000)
        }
    }
    val enqueue: suspend (List<InboxRequest>) -> Unit = { requests ->
        if (sample) app.sync.enqueue(repo, requests)
        else app.submit(requests)
    }
    val send: (List<InboxRequest>) -> Unit = { requests -> operation { enqueue(requests) } }
    val retry: (RequestRow) -> Unit = { row -> state?.payload(row)?.let { send(listOf(it.renew())) } }
    val undo: (RequestRow) -> Unit = { row ->
        operation {
            check(app.sync.undoFeedback(repo, row.requestId))
            if (!sample) app.schedule.outbox()
        }
    }
    val open: (Page, String) -> Unit = { page, id -> go(Route(page, id)) }

    Scaffold(topBar = {
        if (route.page != Page.CONNECT && route.page != Page.PROMPT_EDIT) {
            val backLabel = stringResource(R.string.back)
            val runLabel = stringResource(R.string.run_now)
            TopAppBar(title = {
                if (route.page != Page.CARD) Text(stringResource(route.page.title))
            }, navigationIcon = {
                if (stack.isNotEmpty()) IconButton(onClick = ::back, modifier = Modifier.size(48.dp)) {
                    Text("←", modifier = Modifier.clearAndSetSemantics { contentDescription = backLabel })
                }
            }, actions = {
                if (route.page == Page.CARD) {
                    val data = state
                    val card = data?.cards?.firstOrNull { it.id == route.id }
                    if (data != null && card != null) Star(data, card, send)
                }
                if (route.page == Page.TODAY) IconButton(
                    onClick = { confirmRun = true },
                    enabled = actionsKnown && !running && !acting,
                    modifier = Modifier.size(48.dp)
                ) {
                    if (running || acting) CircularProgressIndicator(Modifier.size(20.dp)
                        .clearAndSetSemantics { contentDescription = runLabel }, strokeWidth = 2.dp)
                    else Text("↻", modifier = Modifier.clearAndSetSemantics { contentDescription = runLabel })
                }
            })
        }
    }, bottomBar = {
        if (route.page != Page.CONNECT) {
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
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer
                ))
            }
        }
        }
    }) { padding ->
        PullToRefreshBox(isRefreshing = syncing, onRefresh = {
            if (route.page == Page.TODAY) operation { sync() }
        }, modifier = Modifier.padding(padding).fillMaxSize()) {
            key(route.page.name, route.id) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    error?.let { Notice(stringResource(it), warning = true) }
                    val data = state
                    when {
                        route.page == Page.CONNECT -> Connection(app, current, ::operation) {
                            tab = 0
                            stack = emptyList()
                        }
                        route.page == Page.TODAY && repo.isEmpty() -> Nav(R.string.connect_today_empty) { go(Route(Page.CONNECT)) }
                        readError -> Notice(stringResource(R.string.error), warning = true)
                        data == null -> Text(stringResource(R.string.loading))
                        else -> when (route.page) {
                            Page.TODAY -> {
                                TodayContent(data, syncState, open, send, retry, undo, { tab = 1 }, running) {
                                    SyncStatusNotice(syncState?.status, { go(Route(Page.OPERATE)) }) { operation { sync() } }
                                    if (!tokenAvailable && validRepository(repo)) Nav(R.string.run_token_needed) { go(Route(Page.OPERATE)) }
                                    else if (!actionsKnown && !running && validRepository(repo)) Small(R.string.actions_error)
                                }
                                if (confirmRun) AlertDialog(onDismissRequest = { confirmRun = false },
                                    title = { Text(stringResource(R.string.confirm_run)) },
                                    confirmButton = { Primary(R.string.run_now) {
                                        confirmRun = false
                                        acting = true
                                        operation {
                                            try {
                                                val token = checkNotNull(app.secrets.get(current.tokenName))
                                                app.sync.dispatchDaily(repo, token)
                                            } catch (e: Exception) {
                                                actionsKnown = false
                                                throw e
                                            } finally { acting = false }
                                        }
                                    } }, dismissButton = { Secondary(R.string.cancel) { confirmRun = false } })
                            }
                            Page.TRACK -> Tracks(data, open, send, retry)
                            Page.ARCHIVE -> Archive(dao, repo, data, open, send, retry, undo)
                            Page.CARD -> CardDetail(data, route.id, open, send, retry, undo)
                            Page.TRACK_DETAIL -> TrackDetail(data, route.id, open, send, retry)
                            Page.SETTINGS -> {
                                ListRow(stringResource(R.string.connect),
                                    if (repo.isEmpty()) stringResource(R.string.repository_disconnected) else repo) { go(Route(Page.CONNECT)) }
                                Primary(R.string.sync, validRepository(repo) && !syncing) { operation { sync() } }
                                if (repo.isEmpty()) Nav(R.string.sync_connect_needed) { go(Route(Page.CONNECT)) }
                                syncState?.let {
                                    if (it.syncedAt.isNotBlank()) Small(R.string.synced, runTime(it.syncedAt))
                                    SyncStatusNotice(it.status, { go(Route(Page.OPERATE)) }) { operation { sync() } }
                                }
                                listOf(Page.OPERATE, Page.PROMPTS, Page.SOURCES, Page.CRITERIA, Page.RUNS, Page.ABOUT).forEach {
                                    HorizontalDivider()
                                    ListRow(stringResource(it.title)) { go(Route(it)) }
                                }
                                DebugEntry { activity.startActivity(android.content.Intent().setClassName(activity, it)) }
                            }
                            Page.OPERATE -> Operations(app, current, data, send, retry, ::operation,
                                syncState?.issue?.lineSequence()?.any { it.startsWith("settings/") } == true)
                            Page.SOURCES -> ReadOnlySources()
                            Page.CRITERIA -> ReadOnlyCriteria(data)
                            Page.RUNS -> Runs(data, open)
                            Page.RUN_DETAIL -> RunDetail(data, route.id)
                            Page.PROMPTS -> Prompts(data, open, send)
                            Page.PROMPT_EDIT -> PromptEditor(data, route.id, send, retry, ::back,
                                { request, saved -> operation { enqueue(listOf(request)); saved() } }) { leaveEditor = it }
                            Page.ABOUT -> About()
                            Page.CONNECT -> Unit
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

@Composable internal fun Secondary(id: Int, onClick: () -> Unit) {
    TextButton(onClick, Modifier.heightIn(min = 48.dp),
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

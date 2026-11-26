@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.ideacollector

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ideacollector.data.AppDao
import app.ideacollector.data.RequestRow
import app.ideacollector.data.SyncState
import collector.data.DataBranch.VERSION
import collector.data.InboxRequest
import collector.model.*
import collector.stage.ideaCategories
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun today() = LocalDate.now(ZoneId.of("Asia/Seoul")).toString()
private fun now() = Instant.now().toString()
internal fun displayDate(value: String): String = runCatching {
    LocalDate.parse(value).format(DateTimeFormatter.ofPattern("M월 d일 (E)", java.util.Locale.KOREAN))
}.getOrDefault(value)
internal fun runTime(value: String, withDate: Boolean = true): String = runCatching {
    val pattern = if (withDate) "M월 d일 (E) HH:mm" else "HH:mm"
    DateTimeFormatter.ofPattern(pattern, java.util.Locale.KOREAN).withZone(ZoneId.of("Asia/Seoul")).format(Instant.parse(value))
}.getOrDefault(value)

@Composable
internal fun TodayContent(data: ScreenState, sync: SyncState?, open: (Page, String) -> Unit,
    send: (List<InboxRequest>) -> Unit, retry: (RequestRow) -> Unit, undo: (RequestRow) -> Unit,
    showTracks: () -> Unit,
    noToday: String,
    runAction: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val starGuide = remember { context.getSharedPreferences("tracking-guide", 0) }
    var firstStarUsed by remember { mutableStateOf(starGuide.getBoolean("used", false)) }
    DisposableEffect(starGuide) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
            firstStarUsed = prefs.getBoolean("used", false)
        }
        starGuide.registerOnSharedPreferenceChangeListener(listener)
        onDispose { starGuide.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val tracks = data.tracks.filter(data::isTracking)
    val fresh = data.updates.filter { it.runDate == today() }.groupBy { it.trackedId }
    if (tracks.isNotEmpty() || data.startingCards.isNotEmpty()) Panel {
        val count = tracks.count { it.id in fresh }
        val total = tracks.size + data.startingCards.size
        ListRow(if (count == 0) stringResource(R.string.track_none, total).removeSuffix(" ›")
            else stringResource(R.string.track_summary, total, count).removeSuffix(" ›"), onClick = showTracks)
        tracks.filter { it.id in fresh }.sortedByDescending { fresh[it.id]?.size }.forEach { track ->
            HorizontalDivider()
            FlowRow(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open(Page.TRACK_DETAIL, track.id) }
                .padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Tag(stringResource(R.string.news, fresh[track.id]?.size ?: 0), true)
                TrendLabel(track.trend)
            }
        }
        data.startingCards.forEach { card ->
            HorizontalDivider()
            ListRow(card.title, stringResource(R.string.tracking_starting)) { open(Page.CARD, card.id) }
        }
    }
    if (data.hasBatch) Notice(stringResource(R.string.classify_waiting))
    val run = data.daily.firstOrNull()
    if (run == null) {
        if (sync == null || sync.status == "OK") Small(noToday)
    }
    else {
        if (run.report.runDate != today()) Notice(noToday)
        Small(R.string.r3_today_meta, displayDate(run.report.runDate), run.report.costUsd)
        RunCounts(run.report) { open(Page.CANDIDATES, run.report.runDate) }
        if (run.report.failedSources.isNotEmpty()) {
            val failure = stringResource(R.string.failed_sources, run.report.failedSources.joinToString { it.source })
            val history = data.runs.firstOrNull { it.second.startedAt == run.report.startedAt }
            val reddit = run.report.failedSources.any { it.source.equals("reddit", ignoreCase = true) }
            Notice(if (reddit) stringResource(R.string.reddit_partial) else failure, warning = true,
                onClick = { open(Page.RUN_DETAIL, history?.first ?: "out/${run.report.runDate}.json") })
        }
    }
    runAction()
    if (run != null) {
        val cards = run.cards + data.requested.filter { it.report.runDate == run.report.runDate }.flatMap { it.cards }
        cards.forEachIndexed { index, card -> FeedCard(data, card, open, send, retry, undo, index + 1) }
        if (cards.isEmpty()) Small(R.string.no_results)
        else if (!firstStarUsed && data.tracks.isEmpty() && data.startingCards.isEmpty()) Small(R.string.track_empty)
    }
}

@Composable internal fun RunCounts(report: RunReport, onCandidates: (() -> Unit)? = null) {
    val candidates = report.stages.firstOrNull { it.stage == "select" }?.output ?: 0
    if (onCandidates == null) {
        Small(R.string.counts, report.fetchedTotal, candidates, report.cardCount)
    } else {
        FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Small(R.string.candidates_collected, report.fetchedTotal)
            Text(stringResource(R.string.candidates_count, candidates),
                modifier = Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onCandidates)
                    .padding(horizontal = 4.dp, vertical = 16.dp),
                color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelMedium)
            Small(R.string.candidates_generated, report.cardCount)
        }
    }
}

private fun direction(trend: TrackedIdea.Trend) = when (trend) {
    TrackedIdea.Trend.UP -> R.string.up
    TrackedIdea.Trend.FLAT -> R.string.flat
    TrackedIdea.Trend.DOWN -> R.string.down
}

@Composable private fun TrendLabel(trend: TrackedIdea.Trend) {
    val color = when (trend) {
        TrackedIdea.Trend.UP -> successContent
        TrackedIdea.Trend.DOWN -> MaterialTheme.colorScheme.error
        TrackedIdea.Trend.FLAT -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(stringResource(direction(trend)), color = color, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun FeedCard(data: ScreenState, card: IdeaCard, open: (Page, String) -> Unit,
    send: (List<InboxRequest>) -> Unit, retry: (RequestRow) -> Unit, undo: (RequestRow) -> Unit,
    number: Int? = null) {
    Panel {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable { open(Page.CARD, card.id) }
                .padding(vertical = 8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    number?.let { Small("$it.") }
                    Text(card.title, style = MaterialTheme.typography.titleMedium)
                }
                if (data.dismissed(card)) Small(R.string.not_interested) else Small(card.oneLiner)
            }
            if (!data.dismissed(card)) Star(data, card, send)
        }
        data.undoableDismissal(card)?.let { dismissal ->
            TextButton(onClick = { undo(dismissal) }) { Text(stringResource(R.string.undo_dismissal)) }
        }
        if (!data.dismissed(card)) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Tag(ideaCategories.firstOrNull { it.id == card.category }?.name ?: card.category)
            ExpansionBadge(card)
            card.badges.forEach {
                if (it == "요청" || it == "내가 고른 카드") CandidateBadge(stringResource(R.string.candidates_requested))
                else Tag(it, true)
            }
        }
        RequestChips(data.cardRequests(card.id), retry)
    }
}

@Composable
internal fun CandidateBadge(text: String) {
    Surface(shape = androidx.compose.foundation.shape.CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary),
        color = MaterialTheme.colorScheme.surface) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun ExpansionBadge(card: IdeaCard) {
    if (card.kind != "expanded") return
    val label = lensLabels[card.lens]?.first ?: return
    Tag(stringResource(R.string.expansion_badge, stringResource(label)), outline = true)
}

@Composable
private fun Tag(text: String, highlight: Boolean = false, outline: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = when {
            outline -> androidx.compose.ui.graphics.Color.Transparent
            highlight -> colors.secondaryContainer
            else -> colors.surfaceVariant
        },
        contentColor = if (highlight) colors.onSecondaryContainer else colors.onSurfaceVariant,
        border = if (outline) androidx.compose.foundation.BorderStroke(1.dp, colors.outline) else null,
        shape = MaterialTheme.shapes.extraSmall
    ) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun Star(data: ScreenState, card: IdeaCard, send: (List<InboxRequest>) -> Unit) {
    val starred = data.starred(card)
    val track = data.activeTrack(card)
    val label = stringResource(if (starred) R.string.tracking else R.string.track_start)
    val active = data.tracks.filter(data::isTracking)
    var limit by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    fun start(replace: TrackedIdea? = null) {
        context.getSharedPreferences("tracking-guide", 0).edit().putBoolean("used", true).apply()
        val at = Instant.ofEpochMilli(Instant.now().toEpochMilli())
        val startAt = if (replace == null) at else at.plusMillis(1)
        send(listOfNotNull(replace?.let { InboxRequest.TrackStop(VERSION, requestId(), at.toString(), it.id) },
            InboxRequest.TrackStart(VERSION, requestId(), startAt.toString(), card.id),
            InboxRequest.Reaction(VERSION, requestId(), startAt.plusMillis(1).toString(), card.id, Feedback.Kind.STAR, "star")))
    }
    IconButton(onClick = {
        if (track != null) send(listOf(InboxRequest.TrackStop(VERSION, requestId(), now(), track.id)))
        else if (active.size >= 10) limit = true
        else start()
    }, enabled = !starred || track != null, modifier = Modifier.size(48.dp)) {
        Text(if (starred) "★" else "☆", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.clearAndSetSemantics { contentDescription = label },
            color = if (starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (limit) {
        val quiet = active.filter { it.status == TrackedIdea.Status.QUIET }.minByOrNull { it.lastUpdateAt ?: it.startedAt }
        AlertDialog(onDismissRequest = { limit = false }, title = { Text(stringResource(R.string.track_limit)) },
            text = { Text(if (quiet == null) stringResource(R.string.track_limit_full)
                else stringResource(R.string.replace_quiet, quiet.title)) },
            confirmButton = {
                if (quiet != null) Primary(R.string.track_start) { limit = false; start(quiet) }
                else Secondary(R.string.close) { limit = false }
            }, dismissButton = { if (quiet != null) Secondary(R.string.cancel) { limit = false } })
    }
}

@Composable internal fun CardDetail(data: ScreenState, id: String, open: (Page, String) -> Unit,
    send: (List<InboxRequest>) -> Unit, retry: (RequestRow) -> Unit, undo: (RequestRow) -> Unit) {
    val card = data.cards.firstOrNull { it.id == id }
    if (card == null) { Small(R.string.missing); return }
    var sheet by rememberSaveable { mutableStateOf(false) }
    Text(card.title, style = MaterialTheme.typography.titleLarge)
    Small(card.oneLiner)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Tag(ideaCategories.firstOrNull { it.id == card.category }?.name ?: card.category)
        ExpansionBadge(card)
        card.badges.forEach {
            if (it == "요청" || it == "내가 고른 카드") CandidateBadge(stringResource(R.string.candidates_requested))
            else Tag(it, true)
        }
        card.tags.forEach { Tag("#" + it) }
    }
    data.activeTrack(card)?.let { Nav(R.string.tracking) { open(Page.TRACK_DETAIL, it.id) } }
    Block(R.string.why, card.whyToday)
    Block(R.string.problem, card.problem)
    val daily = (data.daily + data.requested).firstOrNull { card in it.cards }
    val items = daily?.items.orEmpty()
    val runAt = daily?.report?.startedAt
    Evidence(R.string.tech, card.techSignals, items, runAt)
    Evidence(R.string.demand, card.demandSignals, items, runAt)
    Evidence(R.string.evidence, (card.techSignals + card.demandSignals + card.competitors).distinctBy { it.url }, items, runAt)
    if (card.supplementRefs.isNotEmpty()) {
        Text(stringResource(R.string.supplement), style = MaterialTheme.typography.titleMedium)
        card.supplementRefs.forEach { Link(it.url, it.title, it.note) }
    }
    Evidence(R.string.products, card.competitors, items, runAt)
    if (card.kind == "expanded") {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.expansion_speculation), style = MaterialTheme.typography.titleMedium)
                Small(R.string.expansion_speculation_help)
                card.speculation.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
    Block(R.string.history, card.history.joinToString("\n"))
    Text(stringResource(R.string.judgment), style = MaterialTheme.typography.titleMedium)
    Panel {
        Block(R.string.effort, card.effort)
        Block(R.string.difference, card.differentiation)
        Block(R.string.risk, card.biggestRisk)
        Block(R.string.validation, card.validationPlan)
    }
    Block(R.string.caveats, card.caveats.joinToString("\n"))
    val dismissal = data.undoableDismissal(card)
    if (dismissal == null) Secondary(R.string.not_interested) { sheet = true }
    else TextButton(onClick = { undo(dismissal) }) { Text(stringResource(R.string.undo_dismissal)) }
    RequestChips(data.cardRequests(card.id), retry)
    if (sheet) FeedbackSheet({ sheet = false }) { reason, memo ->
        send(listOf(InboxRequest.Reaction(VERSION, requestId(), now(), card.id,
            Feedback.Kind.NOT_INTERESTED, reason, memo.ifBlank { null })))
        sheet = false
    }
}

@Composable private fun FeedbackSheet(close: () -> Unit, save: (String, String) -> Unit) {
    var reason by rememberSaveable { mutableStateOf("") }
    var memo by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = close, shape = MaterialTheme.shapes.large) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.choose_reason), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.exists, R.string.too_big, R.string.not_my_area, R.string.weak_evidence, R.string.other).forEach {
                    val label = stringResource(it)
                    FilterChip(reason == label, { reason = label }, { Text(label) }, Modifier.heightIn(min = 48.dp))
                }
            }
            Field(memo, { if (it.length <= 500) memo = it }, R.string.memo)
            Primary(R.string.save, reason.isNotBlank()) { save(reason, memo) }
        }
    }
}

@Composable internal fun Block(title: Int, body: String) {
    if (body.isNotBlank()) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun Evidence(title: Int, refs: List<SignalRef>, items: List<Item>, runAt: String?) {
    if (refs.isNotEmpty()) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        refs.forEach { ref ->
            val item = items.firstOrNull { it.key == ref.itemId || it.url == ref.url }
            Text(ref.note.ifBlank { item?.title ?: ref.url }, style = MaterialTheme.typography.bodyMedium)
            val source = item?.source ?: android.net.Uri.parse(ref.url).host.orEmpty()
            val timestamp = item?.extra?.get("collectedAt")?.let { runTime(it) }
                ?: runAt?.let { stringResource(R.string.evidence_run, runTime(it)) }
            Small(listOfNotNull(source, timestamp).joinToString(" · "))
            Link(ref.url, stringResource(R.string.open_original))
        }
    }
}

@Composable internal fun Link(url: String, label: String, note: String = "") {
    val handler = LocalUriHandler.current
    if (url.startsWith("http://") || url.startsWith("https://")) {
        Nav(label, note) { runCatching { handler.openUri(url) } }
    } else if (note.isNotBlank()) Text(note)
}

@Composable internal fun Archive(dao: AppDao, repo: String, data: ScreenState, open: (Page, String) -> Unit,
    send: (List<InboxRequest>) -> Unit, retry: (RequestRow) -> Unit, undo: (RequestRow) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var star by remember { mutableStateOf(false) }
    var hide by remember { mutableStateOf(true) }
    var category by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var tagMenu by remember { mutableStateOf(false) }
    var matches by remember(query) { mutableStateOf<Set<String>?>(if (query.isBlank()) null else emptySet()) }
    var searching by remember(query) { mutableStateOf(query.isNotBlank()) }
    var failed by remember(query) { mutableStateOf(false) }
    LaunchedEffect(query, data) {
        try { matches = ftsQuery(query)?.let { dao.search(repo, it).map { row -> row.id }.toSet() } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { failed = true }
        finally { searching = false }
    }
    Field(query, { query = it }, R.string.search, singleLine = true)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(star, { star = !star }, { Text(stringResource(R.string.star_only)) }, Modifier.heightIn(min = 48.dp))
        FilterChip(hide, { hide = !hide }, { Text(stringResource(R.string.hide_hidden)) }, Modifier.heightIn(min = 48.dp))
        Box {
            val title = stringResource(R.string.all_categories)
            FilterChip(category != null, { menu = true }, { Text(category ?: title) }, Modifier.heightIn(min = 48.dp))
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(title) }, { category = null; menu = false })
                data.cards.map { it.category }.distinct().sorted().forEach {
                    DropdownMenuItem({ Text(it) }, { category = it; menu = false })
                }
            }
        }
        Box {
            val allTags = stringResource(R.string.all_tags)
            FilterChip(tag != null, { tagMenu = true }, { Text(tag ?: allTags) }, Modifier.heightIn(min = 48.dp))
            DropdownMenu(tagMenu, { tagMenu = false }) {
                DropdownMenuItem({ Text(allTags) }, { tag = null; tagMenu = false })
                data.cards.flatMap { it.tags }.distinct().sorted().forEach { choice ->
                    DropdownMenuItem({ Text(choice) }, { tag = choice; tagMenu = false })
                }
            }
        }
    }
    val cards = data.filtered(star, hide, category, matches).filter { tag == null || tag in it.tags }
    val groups = cards.groupBy { it.runDate }
    val emptyDays = if (query.isBlank() && !star && category == null && tag == null)
        data.daily.filter { it.cards.isEmpty() }.map { it.report.runDate } else emptyList()
    val dates = (groups.keys + emptyDays).sortedDescending()
    when {
        failed -> Notice(stringResource(R.string.error), warning = true)
        searching -> Small(R.string.loading)
        dates.isEmpty() -> Small(R.string.no_results)
        else -> dates.forEach { date ->
            Text(displayDate(date), style = MaterialTheme.typography.titleMedium)
            data.daily.firstOrNull { it.report.runDate == date }?.let { RunCounts(it.report) }
            TextButton(onClick = { open(Page.CANDIDATES, date) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.candidates_open))
            }
            groups[date].orEmpty().forEach { FeedCard(data, it, open, send, retry, undo) }
        }
    }
}

@Composable internal fun Tracks(data: ScreenState, open: (Page, String) -> Unit,
    send: (List<InboxRequest>) -> Unit, retry: (RequestRow) -> Unit) {
    val tracks = data.tracks.filter(data::isTracking).sortedByDescending { it.lastUpdateAt }
    var stop by remember { mutableStateOf<TrackedIdea?>(null) }
    Small(R.string.track_count, tracks.size, data.updates.count { it.runDate == today() })
    if (tracks.isEmpty() && data.startingCards.isEmpty()) Small(R.string.track_empty)
    data.startingCards.forEach { card ->
        Nav(card.title, stringResource(R.string.tracking_starting)) { open(Page.CARD, card.id) }
        RequestChips(data.cardRequests(card.id), retry)
        HorizontalDivider()
    }
    tracks.forEach { track ->
        val updates = data.updates.filter { it.trackedId == track.id }
        val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) stop = track
            false
        })
        SwipeToDismissBox(swipe, enableDismissFromStartToEnd = false, backgroundContent = {
            Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = androidx.compose.ui.Alignment.CenterEnd) {
                Text(stringResource(R.string.track_stop))
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Nav(track.title, updates.firstOrNull()?.summary.orEmpty()) { open(Page.TRACK_DETAIL, track.id) }
                TrackMeta(track, updates.flatMap { it.itemKeys }.distinct().size)
                val count = updates.count { it.runDate == today() }
                if (count > 0) Tag(stringResource(R.string.today_news_count, count), true)
                RequestChips(data.trackRequests(track), retry)
                HorizontalDivider()
            }
        }
    }
    val stopped = data.tracks.filterNot(data::isTracking)
    if (stopped.isNotEmpty()) {
        Text(stringResource(R.string.stopped), style = MaterialTheme.typography.titleMedium)
        stopped.forEach { track ->
            Nav(track.title) { open(Page.TRACK_DETAIL, track.id) }
            RequestChips(data.trackRequests(track), retry)
        }
    }
    stop?.let { track ->
        AlertDialog(onDismissRequest = { stop = null }, title = { Text(stringResource(R.string.confirm_stop)) },
            confirmButton = { Primary(R.string.track_stop) {
                stop = null
                send(listOf(InboxRequest.TrackStop(VERSION, requestId(), now(), track.id)))
            } }, dismissButton = { Secondary(R.string.cancel) { stop = null } })
    }
}

@Composable private fun TrackMeta(track: TrackedIdea, related: Int) {
    val started = DateTimeFormatter.ofPattern("M/d").withZone(ZoneId.of("Asia/Seoul")).format(track.startedAt)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Small(track.category)
        TrendLabel(track.trend)
        Small(R.string.related_since, related, started)
        if (track.status == TrackedIdea.Status.QUIET) Small(R.string.quiet)
    }
}

@Composable internal fun TrackDetail(data: ScreenState, id: String, open: (Page, String) -> Unit,
    send: (List<InboxRequest>) -> Unit, retry: (RequestRow) -> Unit) {
    val track = data.tracks.firstOrNull { it.id == id }
    if (track == null) { Small(R.string.missing); return }
    val requests = data.trackRequests(track)
    val currentKeywords = data.keywords(track)
    val currentText = currentKeywords.joinToString("\n")
    var initialKeywords by rememberSaveable(id) { mutableStateOf(currentText) }
    var keywords by rememberSaveable(id) { mutableStateOf(currentText) }
    var editingKeywords by rememberSaveable(id) { mutableStateOf(false) }
    LaunchedEffect(currentText, editingKeywords) {
        if (!editingKeywords) {
            initialKeywords = currentText
            keywords = currentText
        }
    }
    var confirming by remember { mutableStateOf(false) }
    Text(track.title, style = MaterialTheme.typography.titleLarge)
    val updates = data.updates.filter { it.trackedId == id }
    Panel {
        TrackMeta(track, updates.flatMap { it.itemKeys }.distinct().size)
        val end = LocalDate.parse(today())
        val byDate = updates.groupBy { it.runDate }
        val points = (6L downTo 0L).map { offset ->
            byDate[end.minusDays(offset).toString()].orEmpty().flatMap { it.itemKeys }.distinct().size
        }
        val maximum = points.max().coerceAtLeast(1)
        val label = points.mapIndexed { index, value ->
            stringResource(R.string.trend_point, end.minusDays(6L - index).toString(),
                value.toString())
        }.joinToString(", ")
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = label },
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
            points.forEachIndexed { index, value ->
                Column(Modifier.weight(1f), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Box(Modifier.height(36.dp), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                        if (value == 0) Small("0")
                        else Surface(Modifier.width(12.dp).height((32f * value / maximum).dp),
                            color = MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.extraSmall) {}
                    }
                    Small(end.minusDays(6L - index).format(DateTimeFormatter.ofPattern("M/d")))
                }
            }
        }
        Small(R.string.seven_days)
    }
    Text(stringResource(R.string.changes), style = MaterialTheme.typography.titleMedium)
    if (updates.isEmpty()) Small(R.string.empty)
    updates.forEach { update ->
        Panel {
            Text(displayDate(update.runDate), style = MaterialTheme.typography.titleMedium)
            Text(update.summary)
            update.changes.forEach { Tag(it.note, true) }
            val items = data.daily.flatMap { it.items }.filter { it.key in update.itemKeys }
            items.forEach { Link(it.url, it.title) }
        }
    }
    Block(R.string.products, track.competitors.joinToString("\n"))
    val active = data.isTracking(track)
    if (active) {
        Field(keywords, { keywords = it; editingKeywords = it != initialKeywords }, R.string.keywords)
        Small(R.string.keywords_help)
        val list = keywords.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val canSave = list.size <= 10 && list.all { it.length <= 50 } && list != currentKeywords
        Primary(R.string.save, canSave) {
            send(listOf(InboxRequest.TrackKeywords(VERSION, requestId(), now(), id, list)))
            editingKeywords = false
        }
        if (!canSave) Small(R.string.keyword_save_hint)
    } else Small(R.string.stopped)
    Nav(R.string.track_prompt) { open(Page.PROMPT_EDIT, "track") }
    Secondary(R.string.original_card) { open(Page.CARD, track.cardId) }
    if (active) Secondary(R.string.track_stop) { confirming = true }
    RequestChips(requests, retry)
    if (confirming) AlertDialog(onDismissRequest = { confirming = false },
        title = { Text(stringResource(R.string.confirm_stop)) },
        confirmButton = { Primary(R.string.track_stop) {
            confirming = false
            send(listOf(InboxRequest.TrackStop(VERSION, requestId(), now(), id)))
        } }, dismissButton = { Secondary(R.string.cancel) { confirming = false } })
}

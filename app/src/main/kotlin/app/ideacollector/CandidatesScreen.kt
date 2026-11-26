@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.ideacollector

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import collector.model.DropReason
import collector.pipeline.PipelineResult
import collector.runtime.CandidateEntry
import collector.runtime.CandidateView
import collector.runtime.candidateView
import collector.runtime.RequestedCardEstimate
import collector.runtime.RequestedCardBlock
import app.ideacollector.work.CollectorRunService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

internal enum class CandidateFilter(val label: Int, val profile: String? = null) {
    ALL(R.string.candidates_all),
    TECH(R.string.candidates_tech, "tech-signal"),
    DEMAND(R.string.candidates_demand, "demand-signal"),
    MARKET(R.string.candidates_market, "market-signal"),
    UNUSED(R.string.candidates_unused);

    fun matches(entry: CandidateEntry): Boolean = when (this) {
        ALL -> true
        UNUSED -> !entry.usedInCard
        else -> entry.signal.classification.profile == profile
    }
}

internal fun toggleCandidate(keys: List<String>, key: String) = when {
    key in keys -> keys - key
    keys.size < 3 -> keys + key
    else -> keys
}

@Composable
internal fun CandidatesScreen(date: String, daily: List<PipelineResult>, app: CollectorApp, sample: Boolean,
                              open: (Page, String) -> Unit) {
    val view = remember(date, daily) {
        daily.firstOrNull { it.report.runDate == date }?.let(::candidateView) ?: CandidateView()
    }
    var filter by rememberSaveable(date) { mutableStateOf(CandidateFilter.ALL) }
    var showDropped by rememberSaveable(date) { mutableStateOf(false) }
    var selected by rememberSaveable(date) { mutableStateOf(arrayListOf<String>()) }
    var confirm by rememberSaveable(date) { mutableStateOf(false) }
    var submitted by rememberSaveable(date) { mutableStateOf(false) }
    var startFailed by remember { mutableStateOf(false) }
    val busy by app.operationRunning.collectAsState()
    val recovery by app.recovery.collectAsState()
    val outcome by CollectorRunService.requestedOutcome.collectAsState()
    var research by rememberSaveable(date) { mutableStateOf(false) }
    var mail by rememberSaveable(date) { mutableStateOf(false) }
    var requote by remember { mutableStateOf<RequestedCardEstimate?>(null) }
    fun select(keys: List<String>) {
        if (keys == selected) return
        selected = ArrayList(keys)
        submitted = false
        startFailed = false
        requote = null
        CollectorRunService.requestedOutcome.value = null
    }
    fun toggle(key: String) { select(toggleCandidate(selected, key)) }
    LaunchedEffect(outcome, submitted) {
        if (submitted && !outcome?.cardIds.isNullOrEmpty()) selected = arrayListOf()
        if (submitted) outcome?.requote?.let {
            requote = it
            submitted = false
            confirm = true
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(displayDate(date), style = MaterialTheme.typography.titleLarge)
            if (startFailed) Notice(stringResource(R.string.run_failed), warning = true)
            if (submitted) outcome?.let {
                it.failure?.let { reason -> Notice(reason, warning = true) }
                if (it.cardIds.isNotEmpty()) {
                    Notice(stringResource(if (it.mailFailed) R.string.request_card_mail_failed else R.string.request_card_done),
                        warning = it.mailFailed)
                }
            }
            if (view.candidates.isEmpty() && view.belowStandard.isEmpty()) {
                Small(R.string.candidates_empty)
                TextButton(onClick = { open(Page.ARCHIVE, "") }) { Text(stringResource(R.string.candidates_archive)) }
            } else {
                Small(R.string.candidates_summary, view.candidates.size, view.candidates.count { it.usedInCard })
                if (!sample) Small(R.string.candidates_select_hint)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CandidateFilter.entries.forEach { choice ->
                        FilterChip(choice == filter, { filter = choice }, { Text(stringResource(choice.label)) },
                            modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                view.candidates.filter(filter::matches).forEach { entry ->
                    key(entry.key) {
                        CandidateRow(entry, open, selected = entry.key in selected,
                            toggle = if (!sample) ({ toggle(entry.key) }) else null,
                            enabled = !busy && (selected.size < 3 || entry.key in selected))
                    }
                }
                if (view.belowStandard.isNotEmpty()) {
                    TextButton(onClick = { showDropped = !showDropped }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(if (showDropped) R.string.candidates_hide_dropped else R.string.candidates_dropped,
                            view.belowStandard.size))
                    }
                    if (showDropped) view.belowStandard.forEach { entry ->
                        key(entry.key) { CandidateRow(entry, open) }
                    }
                }
            }
        }
        if (selected.isNotEmpty()) {
            HorizontalDivider()
            if (recovery != BackupRecovery.READY) Small(R.string.backup_recovery_pending)
            else if (busy) Small(R.string.run_already_running)
            val failed = submitted && outcome?.failure != null
            val action = if (failed && outcome?.retryAllowed == true) R.string.request_card_retry else R.string.request_card_create
            val canCreate = !busy && recovery == BackupRecovery.READY && (!failed || outcome?.retryAllowed == true)
            BoxWithConstraints {
                val narrow = maxWidth < 400.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            if (selected.size == 3) Small(R.string.request_card_selected_max)
                            else Small(R.string.request_card_selected, selected.size)
                        }
                        TextButton(onClick = { select(emptyList()) }, enabled = !busy) {
                            Text(stringResource(R.string.candidates_clear))
                        }
                        if (!narrow) Primary(action, canCreate) { confirm = true }
                    }
                    if (narrow) Button(onClick = { confirm = true }, enabled = canCreate, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(action))
                    }
                }
            }
        }
    }
    if (confirm) RequestedCardDialog(app, date, selected, busy, recovery == BackupRecovery.READY,
        research, mail, requote, onResearch = { research = it; requote = null }, onMail = { mail = it },
        close = { confirm = false; requote = null }) { limit ->
        confirm = false
        requote = null
        submitted = CollectorRunService.startRequested(app, date, selected, research, limit, mail)
        startFailed = !submitted
    }
}

@Composable
private fun RequestedCardDialog(app: CollectorApp, date: String, keys: List<String>, busy: Boolean, recoveryReady: Boolean,
                                research: Boolean, mail: Boolean, requote: RequestedCardEstimate?,
                                onResearch: (Boolean) -> Unit, onMail: (Boolean) -> Unit,
                                close: () -> Unit, start: (Double) -> Unit) {
    var estimate by remember { mutableStateOf(requote) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(date, keys, research, requote) {
        estimate = requote
        if (requote != null) return@LaunchedEffect
        failed = false
        try {
            estimate = withContext(Dispatchers.IO) { app.runtime().estimateRequestedCards(LocalDate.parse(date), keys, research) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    val researchEnabled = estimate?.let { it.sufficient && it.researchSupported && !it.researchBudgetBlocked } ?: true
    LaunchedEffect(research, researchEnabled) {
        if (research && !researchEnabled) onResearch(false)
    }
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.request_card_quote)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val quote = estimate
                quote?.estimatedUsd?.let {
                    Text(stringResource(R.string.request_card_cost, it), style = MaterialTheme.typography.titleMedium)
                }
                when {
                    failed -> Notice(stringResource(R.string.run_failed), warning = true)
                    quote == null -> Small(R.string.loading)
                    else -> {
                        // 월 차단 중에는 남은 금액을 0으로 보여 하루 예산까지 다 쓴 것처럼 읽히므로 첫 줄만 보인다.
                        if (quote.block == RequestedCardBlock.MONTHLY_BLOCK)
                            Small(R.string.request_card_scope_short, keys.size, quote.added.size)
                        else Small(R.string.request_card_scope, keys.size, quote.added.size, quote.requestLimitUsd, quote.dailyRemainingUsd)
                        if (quote.added.isNotEmpty()) {
                            Text(stringResource(R.string.request_card_evidence))
                            quote.added.forEach { Small(it.classification.summary.ifBlank { it.item.title }) }
                        }
                        val reason = when (quote.block) {
                            RequestedCardBlock.INSUFFICIENT_EVIDENCE -> stringResource(R.string.request_card_insufficient)
                            RequestedCardBlock.REQUEST_LIMIT -> stringResource(
                                if (research) R.string.request_card_budget else R.string.request_card_budget_no_research,
                                quote.requestLimitUsd, checkNotNull(quote.estimatedUsd))
                            // 추가 조사를 켠 견적만 하루 남은 예산을 넘으면 "다 썼다"가 아니라 끄면 된다고 안내한다.
                            RequestedCardBlock.DAILY_LIMIT -> quote.estimatedUsd.let { cost ->
                                if (research && cost != null && quote.dailyRemainingUsd > 0.0)
                                    stringResource(R.string.request_card_daily_research, quote.dailyRemainingUsd, cost)
                                else stringResource(R.string.request_card_daily_block, quote.dailyLimitUsd)
                            }
                            RequestedCardBlock.MONTHLY_LIMIT -> stringResource(R.string.request_card_monthly_block)
                            RequestedCardBlock.MONTHLY_BLOCK -> stringResource(R.string.request_card_settlement_block)
                            RequestedCardBlock.PRIVATE_URL -> stringResource(R.string.request_card_private_url)
                            RequestedCardBlock.RECOVERY_PENDING -> stringResource(R.string.request_card_recovery)
                            RequestedCardBlock.RESEARCH_UNSUPPORTED, null -> null
                        }
                        reason?.let { Notice(it, warning = true) }
                        if (!quote.researchSupported)
                            Notice(stringResource(R.string.request_card_research_unsupported), warning = true)
                    }
                }
                if (!recoveryReady && quote?.block != RequestedCardBlock.RECOVERY_PENDING)
                    Small(R.string.request_card_recovery)
                else if (busy) Small(R.string.run_already_running)
                RunOption(R.string.request_card_research, research, interactive = researchEnabled) {
                    estimate = null
                    onResearch(it)
                }
                Small(R.string.request_card_search_limit)
                if (research) quote?.researchMaxUsd?.let { Small(R.string.request_card_research_max, it) }
                RunOption(R.string.request_card_mail, mail, change = onMail)
            }
        },
        confirmButton = {
            Primary(R.string.request_card_create, !busy && recoveryReady && estimate?.allowed == true) {
                // 추가 조사는 화면에 보인 "최대 약 $X"까지만 승인한다(넘게 정산되면 월 차단 규칙이 적용된다).
                estimate?.let { q -> start(if (research) minOf(q.maxUsd, q.researchMaxUsd ?: q.maxUsd) else q.maxUsd) }
            }
        },
        dismissButton = { Secondary(R.string.cancel, onClick = close) })
}

@Composable
private fun CandidateRow(entry: CandidateEntry, open: (Page, String) -> Unit, selected: Boolean = false,
                         toggle: (() -> Unit)? = null, enabled: Boolean = true) {
    val (item, classification) = entry.signal
    var expanded by rememberSaveable { mutableStateOf(false) }
    val profile = CandidateFilter.entries.firstOrNull { it.profile == classification.profile }
    HorizontalDivider()
    val summary = classification.summary.ifBlank { item.title }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).heightIn(min = 48.dp)
            .combinedClickable(onClick = { expanded = !expanded }, onLongClick = toggle?.takeIf { enabled })
            .padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(summary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            val source = stringResource(sourceName(item.source))
            val metadata = listOfNotNull(profile?.let { stringResource(it.label) },
                classification.score?.let { stringResource(R.string.candidates_score, it) },
                source, classification.topicLabel.takeIf { it.isNotBlank() })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                metadata.forEachIndexed { index, label ->
                    Text(if (index == metadata.lastIndex) label else "$label ·",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        softWrap = label != source)
                }
            }
            if (entry.usedInCard) CandidateBadge(stringResource(R.string.candidates_used))
            val dropLabel = when (entry.dropReason) {
                DropReason.BELOW_PROFILE_THRESHOLD -> R.string.candidates_low
                DropReason.SAME_TOPIC -> R.string.candidates_same_topic
                DropReason.OVER_QUOTA -> R.string.candidates_over_quota
                else -> null
            }
            dropLabel?.let { Small(it) }
        }
        if (toggle != null) {
            val label = stringResource(R.string.request_card_select, summary)
            Checkbox(selected, onCheckedChange = { toggle() }, enabled = enabled,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = label })
        }
    }
    if (expanded) {
        Small(classification.reason)
        Link(item.url, stringResource(R.string.open_original))
        entry.cardIds.forEach { id ->
            TextButton(onClick = { open(Page.CARD, id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.candidates_view_card))
            }
        }
    }
}

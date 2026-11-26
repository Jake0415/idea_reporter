@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.ideacollector

import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import collector.config.ConfigLoader
import collector.config.SourceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.time.LocalDate

@Composable
internal fun SourceSettings(app: CollectorApp, data: ScreenState, id: String, open: (Page, String) -> Unit,
    operation: (suspend () -> Unit) -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    val loaded by produceState<Result<collector.config.SourcesFile>?>(null, app, revision) {
        value = runCatching { app.sourceSettings() }
    }
    val config = (loaded ?: return).getOrNull()
    if (config == null) {
        Notice(stringResource(R.string.settings_read_error), true)
        return
    }
    if (id.isEmpty()) {
        val start = LocalDate.now().minusDays(29).toString()
        val history = data.daily.filter { it.report.runDate >= start }
        config.sources.forEach { source ->
            val fetched = history.sumOf { day -> day.report.sources.filter { it.source == source.sourceId }.sumOf { it.fetched } }
            val items = history.flatMap { it.items }.filter { it.source == source.sourceId }
            ListRow(stringResource(sourceName(source.sourceId)), stringResource(if (source.enabled) R.string.r3_on else R.string.r3_off)) {
                open(Page.SOURCE_DETAIL, source.sourceId)
            }
            if (history.none { day -> day.report.sources.any { it.source == source.sourceId } }) Small(R.string.r3_no_history)
            else Small(R.string.r3_source_stats, java.text.DecimalFormat("0.#").format(fetched / 30.0), if (fetched == 0) 0.0 else items.size * 100.0 / fetched,
                if (items.isEmpty()) 0.0 else items.map { it.score }.average())
            HorizontalDivider()
        }
        return
    }
    val source = config.sources.firstOrNull { it.sourceId == id } ?: return
    key(source) {
        val json = ConfigLoader.json.encodeToJsonElement(SourceConfig.serializer(), source).jsonObject
        val queryKey = listOf("queries", "subreddits", "topic").firstOrNull { it in json }
        val scoreKey = listOf("minRawScore", "minUpvotes", "minVotes").firstOrNull { it in json }
        val windowKey = listOf("windowHours", "createdWithinDays").firstOrNull { it in json }
        var enabled by rememberSaveable { mutableStateOf(source.enabled) }
        var query by rememberSaveable { mutableStateOf(when (val value = queryKey?.let(json::get)) {
            is JsonArray -> value.joinToString("\n") { it.jsonPrimitive.content }
            is JsonPrimitive -> value.content
            else -> ""
        }) }
        var score by rememberSaveable { mutableStateOf(source.minRawScore.toString()) }
        var max by rememberSaveable { mutableStateOf(source.maxItems.toString()) }
        var period by rememberSaveable { mutableStateOf(windowKey?.let(json::get)?.jsonPrimitive?.content.orEmpty()) }
        var profiles by rememberSaveable { mutableStateOf(ArrayList(source.profile)) }
        val choices = mapOf("tech-signal" to R.string.r3_source_tech, "demand-signal" to R.string.r3_source_demand,
            "market-signal" to R.string.r3_source_market)
        var pendingTest by remember { mutableStateOf<SourceConfig?>(null) }
        var testing by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf<Pair<Int, List<String>>?>(null) }
        val valid = profiles.isNotEmpty() && (scoreKey == null || (score.toIntOrNull() ?: -1) >= 0) &&
            ("maxItems" !in json || (max.toIntOrNull() ?: -1) >= 0) &&
            (windowKey == null || (period.toIntOrNull() ?: 0) > 0)
        fun edited(): SourceConfig {
            val changes = buildJsonObject {
                put("enabled", enabled)
                if (queryKey != null) put(queryKey, if (queryKey == "topic") JsonPrimitive(query.trim())
                    else JsonArray(lines(query).map(::JsonPrimitive)))
                if (scoreKey != null) put(scoreKey, score.toInt())
                if ("maxItems" in json) put("maxItems", max.toInt())
                if (windowKey != null) put(windowKey, period.toInt())
                put("profile", JsonArray(profiles.map(::JsonPrimitive)))
            }
            return ConfigLoader.json.decodeFromJsonElement(SourceConfig.serializer(), JsonObject(json + changes))
        }
        // 비교 기준: 방금 저장(또는 대기열에 넣은) 값. 화면이 다시 읽히기 전에 연속 저장해도 두 번째 변경을 놓치지 않는다.
        var baseline by remember { mutableStateOf(source) }
        var saving by remember { mutableStateOf(false) }
        suspend fun save(replacement: SourceConfig) {
            if (saving) return
            saving = true
            try {
                app.saveSource(baseline, replacement)
                baseline = replacement
                revision++
            } finally { saving = false }
        }
        Column(Modifier.fillMaxSize().imePadding()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RunOption(R.string.r3_source_enabled, enabled) { enabled = it }
                if (id == "reddit") {
                    var redditKeys by remember { mutableStateOf(true) }
                    LaunchedEffect(app) { redditKeys = app.redditKeysStored() }
                    if (!redditKeys) Notice(stringResource(R.string.reddit_keys_needed), true)
                }
                if (queryKey != null) Field(query, { query = it }, R.string.r3_source_query)
                if (scoreKey != null) Field(score, { score = it }, R.string.r3_source_min, true)
                if ("maxItems" in json) Field(max, { max = it }, R.string.r3_source_max, true)
                if (windowKey != null) Field(period, { period = it },
                    if (windowKey == "windowHours") R.string.r3_source_hours else R.string.r3_source_days, true)
                Text(stringResource(R.string.r3_source_profile))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { (profile, label) ->
                        val selected = profile in profiles
                        FilterChip(selected = selected, onClick = {
                            profiles = ArrayList(if (selected) profiles - profile else profiles + profile)
                        }, label = { Text(stringResource(label)) }, leadingIcon = if (selected) {{ Text("✓") }} else null)
                    }
                }
                if (!valid) Small(R.string.r3_invalid)
                Secondary(R.string.r3_source_reset, alignStart = true) { operation {
                    save(ConfigLoader.loadDefault().sources.first { it.sourceId == id })
                } }
                OutlinedButton(onClick = { pendingTest = edited() }, enabled = valid && !testing) {
                    Text(stringResource(R.string.r3_source_test))
                }
                if (failed) Notice(stringResource(R.string.r3_source_failed), true)
                pendingTest?.let { candidate -> AlertDialog(onDismissRequest = { pendingTest = null },
                    title = { Text(stringResource(R.string.r3_source_test)) },
                    text = {
                        val message = if (candidate is collector.config.GitHubIssuesConfig) R.string.r3_source_requests_issues
                            else if (candidate is collector.config.RssConfig) R.string.r3_source_requests
                            else R.string.r3_source_requests_max
                        Text(stringResource(message, sourceTestRequests(candidate)))
                    },
                    dismissButton = { Secondary(R.string.cancel) { pendingTest = null } },
                    confirmButton = { Primary(R.string.r3_check) {
                        pendingTest = null
                        testing = true
                        result = null
                        failed = false
                        operation {
                            try {
                                val items = withContext(Dispatchers.IO) { app.runtime().testSource(candidate) }
                                result = items.size to items.take(3).map { it.title }
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { failed = true }
                            finally { testing = false }
                        }
                    } }) }
                result?.let { (count, titles) ->
                    Small(R.string.r3_source_result, count)
                    titles.forEach { Small(it) }
                }
            }
            HorizontalDivider()
            Box(Modifier.padding(top = 8.dp)) {
                Primary(R.string.save, valid && !saving) { operation { save(edited()) } }
            }
        }
    }
}

internal fun sourcePatch(original: SourceConfig, edited: SourceConfig): JsonObject {
    val baseline = ConfigLoader.json.encodeToJsonElement(SourceConfig.serializer(), original).jsonObject
    val updated = ConfigLoader.json.encodeToJsonElement(SourceConfig.serializer(), edited).jsonObject
    return JsonObject(updated.filter { (field, value) -> baseline[field] != value })
}

internal fun mergeSourcePatch(source: SourceConfig, fields: JsonObject): SourceConfig {
    val latest = ConfigLoader.json.encodeToJsonElement(SourceConfig.serializer(), source).jsonObject
    return ConfigLoader.json.decodeFromJsonElement(SourceConfig.serializer(), JsonObject(latest + fields))
}

private fun sourceTestRequests(source: SourceConfig): Int = when (source) {
    is collector.config.HnConfig -> source.queries.size
    is collector.config.GitHubConfig -> source.queries.size
    is collector.config.GitHubIssuesConfig -> minOf(24, source.queries.size * 2) +
        if (source.includeDiscussions) minOf(3, source.queries.size) else 0
    is collector.config.RedditConfig -> source.subreddits.size + 1
    is collector.config.RssConfig -> source.feeds.size
    is collector.config.HfConfig -> 2
    is collector.config.ProductHuntConfig -> 1
}

private fun lines(value: String) = value.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

internal fun sourceName(id: String) = when (id) {
    "hn" -> R.string.source_hn
    "github" -> R.string.source_github
    "github-issues" -> R.string.source_issues
    "hf" -> R.string.source_hf
    "reddit" -> R.string.source_reddit
    "ph" -> R.string.source_ph
    else -> R.string.source_rss
}

@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.ideacollector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import collector.config.PipelineConfig
import collector.llm.ModelCatalog
import collector.llm.ProviderId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

private val criteriaNames = listOf("개발 범위", "대상 고객", "관심 분야", "제외 분야", "수익화 방식")
private val criteriaLine = Regex("""^(- (?:\*\*)?(개발 범위|대상 고객|관심 분야|제외 분야|수익화(?: 방식)?):(?:\*\*)?[ \t]*)([^\r\n]*)""", RegexOption.MULTILINE)

private fun criteriaName(name: String) = if (name == "수익화") "수익화 방식" else name

internal fun criteriaFields(body: String): List<String> {
    val values = criteriaLine.findAll(body).associate { criteriaName(it.groupValues[2]) to it.groupValues[3] }
    return criteriaNames.map { values[it].orEmpty() }
}

internal fun effectiveCriteria(body: String, queued: List<app.ideacollector.data.PendingLocalChange>): String =
    queued.filter { it.configName == "criteria" }.fold(body) { text, change ->
        mergeCriteria(text, Json.decodeFromString<Map<String, String>>(change.json))
    }

internal fun mergeCriteria(body: String, changes: Map<String, String>): String {
    val remaining = changes.toMutableMap()
    val merged = criteriaLine.replace(body) { line ->
        val name = criteriaName(line.groupValues[2])
        val value = changes[name] ?: return@replace line.value
        remaining.remove(name)
        line.groupValues[1] + value
    }
    if (remaining.isEmpty()) return merged
    val separator = if ("\r\n" in body) "\r\n" else "\n"
    val prefix = if (merged.isEmpty() || merged.endsWith("\n")) merged else merged + separator
    return prefix + remaining.entries.joinToString(separator, postfix = separator) { (name, value) -> "- **$name:** $value" }
}

@Composable
internal fun CriteriaFields(app: CollectorApp, operation: (suspend () -> Unit) -> Unit,
    onboarding: Boolean = false, next: (((() -> Unit)?) -> Unit)? = null, done: () -> Unit = {}) {
    val queued by app.database.dao().observeQueuedChanges().collectAsState(initial = null)
    val changes = queued ?: return
    var revision by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    val pipeline = remember(app, changes, revision) { runCatching { effectivePipeline(app, changes) }.getOrNull() }
    if (pipeline == null) {
        Notice(stringResource(R.string.settings_read_error), true)
        return
    }
    val labels = listOf(R.string.r3_scope, R.string.r3_customer, R.string.r3_interests,
        R.string.r3_exclusions, R.string.r3_revenue)
    var original by rememberSaveable { mutableStateOf(ArrayList(criteriaFields(effectiveCriteria(app.currentCriteria(), changes)))) }
    var fields by rememberSaveable { mutableStateOf(ArrayList(original)) }
    fun change(index: Int, value: String) {
        fields = ArrayList(fields).apply { set(index, value) }
    }
    labels.forEachIndexed { index, label ->
        if (label == R.string.r3_customer) {
            val categories = listOf(stringResource(R.string.r3_customer_developer), stringResource(R.string.r3_customer_b2b),
                stringResource(R.string.r3_customer_consumer))
            val entries = fields[index].split(" · ").filter { it.isNotBlank() }
            val unrestricted = fields[index].startsWith(stringResource(R.string.r3_customer_any))
            val selected = if (unrestricted) categories else entries.filter { it in categories }
            val memo = if (unrestricted) fields[index].substringAfter(" — ", "")
                else entries.filterNot { it in categories }.joinToString(" · ")
            Text(stringResource(label))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { category ->
                    FilterChip(category in selected, {
                        val updated = if (category in selected) selected - category else selected + category
                        change(index, (updated + memo).filter { it.isNotBlank() }.joinToString(" · "))
                    }, label = { Text(category) }, leadingIcon = if (category in selected) {
                        { Text("✓") }
                    } else null)
                }
            }
            Field(memo, { value -> change(index, (selected + value).filter { it.isNotBlank() }.joinToString(" · ")) },
                R.string.r3_customer_note, minLines = 2)
        } else Field(fields[index], { value -> change(index, value) }, label,
            singleLine = label !in setOf(R.string.r3_exclusions, R.string.r3_revenue),
            minLines = if (label in setOf(R.string.r3_exclusions, R.string.r3_revenue)) 2 else 1)
    }
    var cards by rememberSaveable { mutableStateOf(pipeline.ideaCount.toString()) }
    var category by rememberSaveable { mutableStateOf(pipeline.maxCardsPerCategory.toString()) }
    var tag by rememberSaveable { mutableStateOf(pipeline.maxCandidatesPerTag.toString()) }
    var feedback by rememberSaveable { mutableStateOf(pipeline.feedbackExamples.toString()) }
    var originalNumbers by rememberSaveable { mutableStateOf(arrayListOf(cards, category, tag, feedback)) }
    run {
        HorizontalDivider()
        Text(stringResource(R.string.card_defaults), style = MaterialTheme.typography.titleMedium)
        Field(cards, { cards = it }, R.string.r3_card_count, true, hint = PipelineConfig.defaults.ideaCount.toString(), help = R.string.r3_card_count_help)
        Field(category, { category = it }, R.string.r3_category_cap, true, hint = PipelineConfig.defaults.maxCardsPerCategory.toString(), help = R.string.r3_category_cap_help)
        Field(tag, { tag = it }, R.string.r3_tag_cap, true, hint = PipelineConfig.defaults.maxCandidatesPerTag.toString(), help = R.string.r3_tag_cap_help)
        Field(feedback, { feedback = it }, R.string.r3_feedback_count, true, hint = PipelineConfig.defaults.feedbackExamples.toString(), help = R.string.r3_feedback_count_help)
    }
    val valid = ((cards.ifBlank { PipelineConfig.defaults.ideaCount.toString() }.toIntOrNull() ?: 0) in 3..7 && (category.ifBlank { PipelineConfig.defaults.maxCardsPerCategory.toString() }.toIntOrNull() ?: 0) > 0 &&
        (tag.ifBlank { PipelineConfig.defaults.maxCandidatesPerTag.toString() }.toIntOrNull() ?: 0) > 0 && (feedback.ifBlank { PipelineConfig.defaults.feedbackExamples.toString() }.toIntOrNull() ?: -1) >= 0)
    if (!valid) Small(R.string.r3_invalid)
    val save: () -> Unit = {
        if (!saving) {
            val values = fields
            val numbers = arrayListOf(cards, category, tag, feedback)
            val changes = criteriaNames.indices.filter { values[it] != original[it] }.associate {
                criteriaNames[it] to values[it].lineSequence().joinToString(" ") { line -> line.trim() }.trim()
            }
            val defaults = PipelineConfig.defaults
            val cardChanges = buildJsonObject {
                if (numbers[0] != originalNumbers[0]) put("ideaCount", numbers[0].ifBlank { defaults.ideaCount.toString() }.toInt())
                if (numbers[1] != originalNumbers[1]) put("maxCardsPerCategory", numbers[1].ifBlank { defaults.maxCardsPerCategory.toString() }.toInt())
                if (numbers[2] != originalNumbers[2]) put("maxCandidatesPerTag", numbers[2].ifBlank { defaults.maxCandidatesPerTag.toString() }.toInt())
                if (numbers[3] != originalNumbers[3]) put("feedbackExamples", numbers[3].ifBlank { defaults.feedbackExamples.toString() }.toInt())
            }
            saving = true
            operation {
                try {
                    if (changes.isNotEmpty()) app.patchCriteria(changes)
                    if (cardChanges.isNotEmpty()) {
                        app.patchLocalConfig("pipeline.local.json", cardChanges)
                        revision++
                    }
                    val (savedFields, savedPipeline) = app.criteriaSettings()
                    fields = ArrayList(fields.mapIndexed { index, value ->
                        if (value == values[index]) savedFields[index] else value
                    })
                    original = ArrayList(savedFields)
                    originalNumbers = arrayListOf(savedPipeline.ideaCount.toString(), savedPipeline.maxCardsPerCategory.toString(),
                        savedPipeline.maxCandidatesPerTag.toString(), savedPipeline.feedbackExamples.toString())
                    if (cards == numbers[0]) cards = originalNumbers[0]
                    if (category == numbers[1]) category = originalNumbers[1]
                    if (tag == numbers[2]) tag = originalNumbers[2]
                    if (feedback == numbers[3]) feedback = originalNumbers[3]
                    done()
                } finally {
                    saving = false
                }
            }
        }
    }
    if (onboarding && next != null) WizardNext(valid && !saving, save, next)
    else Primary(R.string.save, valid && !saving, save)
}

internal fun mergePipelinePatch(original: JsonObject, patch: JsonObject): JsonObject {
    val models = patch["models"] as? JsonObject ?: return JsonObject(original + patch)
    val previous = (original["models"] as? JsonObject).orEmpty()
    return JsonObject(original + patch + ("models" to JsonObject(previous + models)))
}

internal fun effectivePipeline(app: CollectorApp, queued: List<app.ideacollector.data.PendingLocalChange>): PipelineConfig {
    val current = PipelineConfig.load(app.paths.pipelineConfig)
    val base = Json.encodeToJsonElement(PipelineConfig.serializer(), current).jsonObject
    val effective = queued.filter { it.configName == "patch:pipeline.local.json" }.fold(base) { values, change ->
        mergePipelinePatch(values, Json.parseToJsonElement(change.json).jsonObject)
    }
    return Json.decodeFromJsonElement(PipelineConfig.serializer(), effective)
}

@Composable
internal fun ModelSettings(app: CollectorApp, data: ScreenState, queued: List<app.ideacollector.data.PendingLocalChange>,
    checkedModels: Map<String, List<String>>, operation: (suspend () -> Unit) -> Unit, onSaved: () -> Unit) {
    val config = remember(app, data, queued) { runCatching {
        effectivePipeline(app, queued).models
    }.getOrNull() }
    if (config == null) {
        Notice(stringResource(R.string.settings_read_error), true)
        return
    }
    var available by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var classifyProvider by rememberSaveable(config.classify.provider.name) { mutableStateOf(config.classify.provider.name) }
    var classifyModel by rememberSaveable(config.classify.model) { mutableStateOf(config.classify.model) }
    var generateProvider by rememberSaveable(config.generate.provider.name) { mutableStateOf(config.generate.provider.name) }
    var generateModel by rememberSaveable(config.generate.model) { mutableStateOf(config.generate.model) }
    var fallback by rememberSaveable(config.fallback) { mutableStateOf(config.fallback) }
    HorizontalDivider()
    Text(stringResource(R.string.r3_models), style = MaterialTheme.typography.titleMedium)
    Primary(R.string.r3_model_load, !busy) {
        busy = true
        failed = false
        operation {
            try {
                available = buildMap {
                    for ((key, _) in aiKeys) {
                        val secret = app.secrets.get(key) ?: continue
                        try { put(key, app.modelsForKey(key, secret)) }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { failed = true }
                    }
                }
            } finally { busy = false }
        }
    }
    if (failed) Notice(stringResource(R.string.r3_key_error), true)
    val choices = (available + checkedModels).mapKeys { (key, _) ->
        if (key == aiKeys[0].first) ProviderId.ANTHROPIC.name else ProviderId.OPENAI.name
    }.mapValues { (provider, models) ->
        models.filter { ModelCatalog.defaults.supports(ProviderId.valueOf(provider), it) }
    }.filterValues { it.isNotEmpty() }
    ModelPicker(R.string.r3_classify_model, classifyProvider, classifyModel, choices) { provider, model ->
        classifyProvider = provider
        classifyModel = model
    }
    ModelPicker(R.string.r3_generate_model, generateProvider, generateModel, choices) { provider, model ->
        generateProvider = provider
        generateModel = model
    }
    RunOption(R.string.r3_fallback, fallback) { fallback = it }
    Primary(R.string.save) {
        operation {
            val models = PipelineConfig.Models(
                collector.llm.StageModel(ProviderId.valueOf(classifyProvider), classifyModel),
                collector.llm.StageModel(ProviderId.valueOf(generateProvider), generateModel), fallback)
            val changes = buildJsonObject {
                if (models.classify != config.classify) put("classify", Json.encodeToJsonElement(collector.llm.StageModel.serializer(), models.classify))
                if (models.generate != config.generate) put("generate", Json.encodeToJsonElement(collector.llm.StageModel.serializer(), models.generate))
                if (models.fallback != config.fallback) put("fallback", models.fallback)
            }
            if (changes.isNotEmpty()) {
                app.patchLocalConfig("pipeline.local.json", buildJsonObject { put("models", changes) })
                onSaved()
            }
        }
    }
}

@Composable
private fun ModelPicker(label: Int, provider: String, model: String, choices: Map<String, List<String>>,
    select: (String, String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val providerLabel = stringResource(if (provider == ProviderId.ANTHROPIC.name) R.string.provider_claude else R.string.provider_openai)
    ListRow(stringResource(label), "$providerLabel · $model") { open = choices.isNotEmpty() }
    DropdownMenu(open, { open = false }) {
        choices.forEach { (name, models) -> models.forEach { id ->
            val nameLabel = stringResource(if (name == ProviderId.ANTHROPIC.name) R.string.provider_claude else R.string.provider_openai)
            DropdownMenuItem(text = { Text("$nameLabel · $id") }, onClick = { select(name, id); open = false })
        } }
    }
}

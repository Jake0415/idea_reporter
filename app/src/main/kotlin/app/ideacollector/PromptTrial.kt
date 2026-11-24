package app.ideacollector

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import collector.budget.BudgetSettings
import app.ideacollector.work.CollectorRunService
import java.time.LocalDate
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun trialValues(value: JsonElement): String = when (value) {
    is JsonObject -> value.values.joinToString("\n", transform = ::trialValues)
    is JsonArray -> value.joinToString("\n", transform = ::trialValues)
    is JsonPrimitive -> value.content
}

internal class PromptTrialState {
    var draft by mutableStateOf<String?>(null)
    var itemKeys by mutableStateOf(arrayListOf<String>())
    var estimate by mutableStateOf<Double?>(null)
    var remaining by mutableDoubleStateOf(0.0)
    var result by mutableStateOf<String?>(null)
    var failed by mutableStateOf(false)
    var budgetRefused by mutableStateOf(false)
    var paidStarted by mutableStateOf(false)
}

@Composable
internal fun PromptTrial(app: CollectorApp, viewModel: ScreensViewModel, data: ScreenState, id: String, body: String) {
    val token = rememberSaveable(id) { UUID.randomUUID().toString() }
    val state = rememberSaveable(token, saver = listSaver<PromptTrialState, Any>(
        save = { listOf(it.draft.orEmpty(), it.itemKeys, it.estimate ?: -1.0, it.remaining,
            it.result.orEmpty(), it.failed, it.paidStarted, it.budgetRefused) },
        restore = { saved ->
            viewModel.promptTrials[token] ?: PromptTrialState().also { restored ->
                restored.draft = (saved[0] as String).ifEmpty { null }
                restored.itemKeys = ArrayList((saved[1] as List<*>).filterIsInstance<String>())
                restored.estimate = (saved[2] as Double).takeIf { it >= 0 }
                restored.remaining = saved[3] as Double
                restored.result = (saved[4] as String).ifEmpty { null }
                restored.failed = saved[5] as Boolean
                restored.paidStarted = saved[6] as Boolean
                restored.budgetRefused = saved.getOrNull(7) == true
                viewModel.promptTrials[token] = restored
            }
        }
    )) { viewModel.promptTrials.getOrPut(token, ::PromptTrialState) }
    val busy by app.operationRunning.collectAsState()
    val running by CollectorRunService.running.collectAsState()
    LaunchedEffect(busy, state.paidStarted) {
        if (!busy && state.paidStarted) {
            state.paidStarted = false
            state.failed = true
        }
    }
    val items = remember(data) { data.daily.asSequence().flatMap { it.items.asSequence() }
        .distinctBy { it.key }.take(5).toList() }
    if (running) Small(R.string.r3_action_running)
    Primary(R.string.r3_prompt_trial, !running && !busy && items.size == 5 && body.isNotBlank() && body.length <= 20_000) {
        app.appScope.launch {
            if (!app.operationRunning.compareAndSet(expect = false, update = true)) return@launch
            state.failed = false
            state.budgetRefused = false
            try {
                val answer = withContext(Dispatchers.IO) {
                    val runtime = app.runtime()
                    val limit = BudgetSettings.load(app.paths.outDir) {}.dailyCardUsd
                    runtime.estimatePromptTrial(id, body, items) to (limit - runtime.dailyCost(LocalDate.now())).coerceAtLeast(0.0)
                }
                state.draft = body
                state.itemKeys = ArrayList(items.map { it.key })
                state.remaining = answer.second
                state.estimate = answer.first
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { state.failed = true }
            finally { app.finishOperation() }
        }
    }
    if (items.size < 5) Small(R.string.r3_prompt_trial_samples)
    val samples = state.itemKeys.mapNotNull { key -> data.daily.asSequence().flatMap { it.items.asSequence() }.firstOrNull { it.key == key } }
    val text = state.draft
    val cost = state.estimate
    if (text != null && cost != null) {
        AlertDialog(onDismissRequest = { state.estimate = null },
            title = { Text(stringResource(R.string.r3_prompt_trial)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(stringResource(R.string.r3_prompt_trial_cost, cost))
                    Text(stringResource(R.string.r3_trial_remaining, state.remaining))
                    if (cost > state.remaining) Text(stringResource(R.string.r3_trial_budget))
                }
            },
            dismissButton = { Secondary(R.string.cancel) { state.estimate = null } },
            confirmButton = { Primary(R.string.r3_check, !running && !busy && samples.size == 5 && cost <= state.remaining) {
                app.appScope.launch {
                    if (!app.operationRunning.compareAndSet(expect = false, update = true)) return@launch
                    state.estimate = null
                    state.paidStarted = true
                    state.result = null
                    state.failed = false
                    try {
                        val answer = withContext(Dispatchers.IO) { app.runtime().runPromptTrial(id, text, samples, cost) }
                        check(!answer.refused)
                        state.result = answer.json
                    } catch (_: collector.llm.BudgetExceededException) { state.budgetRefused = true }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { state.failed = true }
                    finally {
                        state.paidStarted = false
                        app.finishOperation()
                    }
                }
            } })
    }
    if (state.budgetRefused) Notice(stringResource(R.string.r3_trial_budget), true)
    if (state.failed) Notice(stringResource(R.string.r3_trial_interrupted), true)
    state.result?.let { raw ->
        val parsed = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) }.getOrNull()
        Text(parsed?.let(::trialValues) ?: raw, style = MaterialTheme.typography.bodyMedium)
    } ?: run { if (!state.failed && !state.budgetRefused && !busy) Small(R.string.prompt_test_empty) }
}

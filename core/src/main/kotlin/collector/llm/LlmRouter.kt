package collector.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.math.ceil

internal fun estimateInputTokens(vararg texts: String): Int {
    val ascii = texts.sumOf { text -> text.count { it.code < 128 } }
    val nonAscii = texts.sumOf { it.codePointCount(0, it.length) } - ascii
    return ceil(ascii / 4.0 + nonAscii / 1.5).toInt()
}

@Serializable
data class StageModel(val provider: ProviderId, val model: String)

class LlmRouter(
    providers: Collection<LlmProvider>,
    private val costGuard: CostGuard,
    private val stageModels: Map<String, StageModel> = emptyMap(),
    private val fallback: Boolean = true,
    private val catalog: ModelCatalog = ModelCatalog.defaults,
    private val timeoutMillis: Long? = null,
    private val stageFallback: Map<String, Boolean> = emptyMap(),
    private val stageTimeoutMillis: Map<String, Long> = emptyMap(),
    private val preflightBudget: Boolean = false
) {
    private val providers = providers.associateBy { it.id }

    suspend fun complete(stage: String, request: LlmRequest, promptId: String, promptVersion: String,
                         promptVersions: Map<String, String> = emptyMap(), fallbackOnInvalidJson: Boolean = true,
                         allowFallback: Boolean = true): LlmResult {
        val defaultTier = when (stage) {
            "classify", "track" -> ModelTier.FAST
            "generate" -> ModelTier.STRONG
            "test" -> if (promptId == "ideas" || promptId == "criteria") ModelTier.STRONG else ModelTier.FAST
            else -> throw IllegalArgumentException("알 수 없는 LLM 단계입니다.")
        }
        val preferred = if (ProviderId.ANTHROPIC in providers) ProviderId.ANTHROPIC else ProviderId.OPENAI
        val primary = stageModels[stage] ?: checkNotNull(catalog.defaultModel(preferred, defaultTier))
        val call = LlmCall(stage, primary.provider, primary.model, promptId, promptVersion, promptVersions = promptVersions)
        if (primary.provider !in providers) {
            costGuard.record(call.copy(error = "key_missing"))
            throw LlmKeyMissingException(primary.provider)
        }
        val tier = catalog.requirePrice(primary.provider, primary.model).tier
        val alternate = providers.keys.firstOrNull { it != primary.provider }
            ?.takeIf { allowFallback && fallback && stageFallback[stage] != false }
            ?.let { catalog.defaultModel(it, tier) }

        val first = try {
            attempt(request, call)
        } catch (e: LlmAuthException) {
            throw e
        } catch (e: LlmKeyMissingException) {
            throw e
        } catch (e: LlmConfigException) {
            throw e
        } catch (e: BudgetExceededException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (alternate == null || !fallbackOnInvalidJson && e is LlmServiceException &&
                e.reason in setOf("invalid_json", "truncated")) throw e
            null
        }
        if (first != null && (!first.refused || alternate == null)) return first
        checkNotNull(alternate)
        return attempt(request, call.copy(provider = alternate.provider, model = alternate.model, fallbackUsed = true))
    }

    private suspend fun attempt(request: LlmRequest, call: LlmCall): LlmResult {
        val timeout = stageTimeoutMillis[call.stage] ?: timeoutMillis ?: if (call.stage == "generate") 600_000 else 60_000
        val projected = if (preflightBudget) {
            val price = catalog.requirePrice(call.provider, call.model)
            val input = estimateInputTokens(request.system, request.user, request.jsonSchema.toString()) + 1024
            val long = price.longContext?.takeIf { input > it.thresholdInputTokens }
            val inputRate = long?.input ?: price.inputUsd
            val maxInputRate = if (call.provider == ProviderId.ANTHROPIC)
                maxOf(inputRate, price.cacheWriteUsd ?: inputRate * 1.25) else inputRate
            val outputRate = long?.output ?: price.outputUsd
            (input * maxInputRate + request.maxOutputTokens * outputRate) / 1_000_000
        } else 0.0
        val reservation = costGuard.reserve(projected)
        val result = try {
            withTimeoutOrNull(timeout) {
                providers.getValue(call.provider).complete(request.copy(model = call.model))
            } ?: throw LlmServiceException(reason = "timeout")
        } catch (e: Exception) {
            val error = when (e) {
                is LlmAuthException -> "auth"
                is CancellationException -> "cancelled"
                is LlmServiceException -> e.reason
                else -> "provider_error"
            }
            val failure = e as? LlmServiceException
            val usage = failure?.usage
            costGuard.record(call.copy(
                    model = failure?.model ?: call.model,
                    inputTokens = usage?.inputTokens ?: 0,
                    outputTokens = usage?.outputTokens ?: 0,
                    cachedInputTokens = usage?.cachedInputTokens ?: 0,
                    cacheWriteTokens = usage?.cacheWriteTokens ?: 0,
                    costUsd = failure?.costUsd ?: 0.0,
                    error = error,
                    priceFallback = failure?.priceFallback ?: false,
                    priceUnverified = failure?.priceUnverified ?: false
                ), reservation, settled = usage != null || e is LlmAuthException || e is LlmConfigException)
            throw e
        }
        costGuard.record(call.copy(
            model = result.model,
            inputTokens = result.inputTokens,
            outputTokens = result.outputTokens,
            cachedInputTokens = result.cachedInputTokens,
            cacheWriteTokens = result.cacheWriteTokens,
            costUsd = result.costUsd,
            error = if (result.refused) "refused" else null,
            priceFallback = result.priceFallback,
            priceUnverified = result.priceUnverified
        ), reservation)
        return result
    }
}


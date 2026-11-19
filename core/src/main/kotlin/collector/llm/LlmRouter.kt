package collector.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

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
    private val stageTimeoutMillis: Map<String, Long> = emptyMap()
) {
    private val providers = providers.associateBy { it.id }

    suspend fun complete(stage: String, request: LlmRequest, promptId: String, promptVersion: String): LlmResult {
        val defaultTier = when (stage) {
            "classify", "track" -> ModelTier.FAST
            "generate" -> ModelTier.STRONG
            "test" -> if (promptId == "ideas" || promptId == "criteria") ModelTier.STRONG else ModelTier.FAST
            else -> throw IllegalArgumentException("알 수 없는 LLM 단계입니다.")
        }
        val preferred = if (ProviderId.ANTHROPIC in providers) ProviderId.ANTHROPIC else ProviderId.OPENAI
        val primary = stageModels[stage] ?: checkNotNull(catalog.defaultModel(preferred, defaultTier))
        val call = LlmCall(stage, primary.provider, primary.model, promptId, promptVersion)
        if (primary.provider !in providers) {
            costGuard.record(call.copy(error = "key_missing"))
            throw LlmKeyMissingException(primary.provider)
        }
        val tier = catalog.requirePrice(primary.provider, primary.model).tier
        val alternate = providers.keys.firstOrNull { it != primary.provider }
            ?.takeIf { fallback && stageFallback[stage] != false }
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
            if (alternate == null) throw e
            null
        }
        if (first != null && (!first.refused || alternate == null)) return first
        checkNotNull(alternate)
        return try {
            attempt(request, call.copy(provider = alternate.provider, model = alternate.model, fallbackUsed = true))
        } catch (e: BudgetExceededException) {
            first ?: throw e
        }
    }

    private suspend fun attempt(request: LlmRequest, call: LlmCall): LlmResult {
        val timeout = stageTimeoutMillis[call.stage] ?: timeoutMillis ?: if (call.stage == "generate") 600_000 else 60_000
        costGuard.checkBudget()
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
            if (e !is LlmConfigException) {
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
                ))
            }
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
        ))
        return result
    }
}


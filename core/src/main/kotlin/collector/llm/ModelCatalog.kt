package collector.llm

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
enum class ModelTier {
    @SerialName("fast") FAST,
    @SerialName("strong") STRONG
}

@Serializable
data class ModelPrice(
    val provider: ProviderId,
    val model: String,
    val tier: ModelTier,
    val inputUsd: Double,
    val outputUsd: Double,
    val cachedInputUsd: Double,
    val priceVerified: Boolean = true,
    val cacheWriteUsd: Double? = null,
    val longContext: LongContextPrice? = null
)

@Serializable
data class LongContextPrice(
    val thresholdInputTokens: Int, val input: Double, val output: Double, val cachedInput: Double? = null
)

class ModelCatalog(private val prices: List<ModelPrice>) {
    fun find(provider: ProviderId, model: String): ModelPrice? {
        prices.firstOrNull { it.provider == provider && it.model == model }?.let { return it }
        val base = snapshotSuffix.replace(model, "")
        if (base == model) return null
        return prices.firstOrNull { it.provider == provider && it.model == base }
    }

    fun defaultModel(provider: ProviderId, tier: ModelTier): StageModel? =
        prices.firstOrNull { it.provider == provider && it.tier == tier }?.let { StageModel(provider, it.model) }

    internal fun requirePrice(provider: ProviderId, model: String): ModelPrice =
        find(provider, model) ?: throw LlmConfigException(model)

    fun costUsd(provider: ProviderId, model: String, input: Int, output: Int, cached: Int): Double =
        costUsd(requirePrice(provider, model), LlmUsage(input, output, cached))

    internal fun response(provider: ProviderId, model: String, usage: LlmUsage, content: () -> String?): LlmResult {
        val price = find(provider, model)
        val appliedPrice = price ?: prices.filter { it.provider == provider }.maxBy { costUsd(it, usage) }
        val cost = costUsd(appliedPrice, usage)
        val fallback = price == null
        val unverified = !appliedPrice.priceVerified
        val text = try {
            content()?.also {
                if (it.isBlank()) throw LlmServiceException(reason = "empty")
                if (Json.parseToJsonElement(it) !is JsonObject) throw LlmServiceException(reason = "invalid_json")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = (e as? LlmServiceException)?.reason ?: "invalid_json"
            throw LlmServiceException(usage = usage, costUsd = cost, model = model, reason = reason,
                priceFallback = fallback, priceUnverified = unverified)
        }
        return LlmResult(text.orEmpty(), provider, model, usage.inputTokens, usage.outputTokens,
            usage.cachedInputTokens, cost, text == null, usage.cacheWriteTokens, fallback, unverified)
    }

    private fun costUsd(price: ModelPrice, usage: LlmUsage): Double {
        val long = price.longContext?.takeIf { usage.inputTokens > it.thresholdInputTokens }
        val inputPrice = long?.input ?: price.inputUsd
        val outputPrice = long?.output ?: price.outputUsd
        val uncached = usage.inputTokens - usage.cachedInputTokens - usage.cacheWriteTokens
        return (uncached * inputPrice + usage.outputTokens * outputPrice +
            usage.cachedInputTokens * (long?.cachedInput ?: price.cachedInputUsd) +
            usage.cacheWriteTokens * (price.cacheWriteUsd ?: inputPrice * 1.25)) / 1_000_000
    }

    companion object {
        private val snapshotSuffix = Regex("""-(?:\d{4}-\d{2}-\d{2}|\d{8})$""")

        val defaults: ModelCatalog by lazy {
            val resource = checkNotNull(ModelCatalog::class.java.getResourceAsStream("/models.default.json"))
            ModelCatalog(Json.decodeFromString(resource.bufferedReader().use { it.readText() }))
        }
    }
}

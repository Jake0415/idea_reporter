package collector.llm

import collector.config.PipelineConfig
import collector.model.SupplementRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant

@Serializable
enum class ProviderId { ANTHROPIC, OPENAI }

enum class Effort { LOW, MEDIUM, HIGH }

@Serializable
data class LlmRequest(
    val model: String,
    val system: String,
    val user: String,
    val jsonSchema: JsonObject,
    val schemaName: String,
    val maxOutputTokens: Int,
    val effort: Effort = Effort.MEDIUM
)

@Serializable
data class LlmResult(
    val json: String,
    val provider: ProviderId,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val cachedInputTokens: Int,
    val costUsd: Double,
    val refused: Boolean = false,
    val cacheWriteTokens: Int = 0,
    val priceFallback: Boolean = false,
    val priceUnverified: Boolean = false
)

data class ModelInfo(val id: String, val name: String = id)

interface BatchCapableProvider : LlmProvider {
    fun prepareBatch(requests: Map<String, LlmRequest>): suspend () -> BatchState
    suspend fun findBatches(since: Instant): List<BatchState>
    suspend fun retrieveBatch(id: String): BatchState
    suspend fun cancelBatch(id: String): BatchState
    suspend fun batchResults(id: String, receive: (String, Result<LlmResult>?) -> Unit)
}

data class BatchState(val id: String, val ended: Boolean, val canceling: Boolean)

interface WebSearchProvider : LlmProvider {
    suspend fun search(req: LlmRequest, config: PipelineConfig.Search): SearchResult
}

data class SearchResult(
    val refs: List<SupplementRef>, val usage: LlmUsage, val requests: Int,
    val model: String, val error: String?
)

interface LlmProvider {
    val id: ProviderId
    suspend fun listModels(): List<ModelInfo>
    suspend fun complete(req: LlmRequest): LlmResult
}

class LlmAuthException : RuntimeException("인증에 실패했습니다. 키를 확인하세요.")

class LlmKeyMissingException(provider: ProviderId) : RuntimeException("키 미등록: ${provider.name}_API_KEY")

class LlmConfigException(model: String) :
    RuntimeException("가격표에 없는 모델: $model — models 설정에 가격을 등록하세요")

data class LlmUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val cachedInputTokens: Int = 0,
    val cacheWriteTokens: Int = 0
)

internal class LlmServiceException(
    val status: Int? = null,
    val usage: LlmUsage? = null,
    val costUsd: Double = 0.0,
    val model: String? = null,
    val reason: String = when (status) {
        in 400..499 -> "http_4xx"
        in 500..599 -> "http_5xx"
        else -> "provider_error"
    },
    val priceFallback: Boolean = false,
    val priceUnverified: Boolean = false
) :
    RuntimeException(if (status == null) "공급자 요청 또는 응답 처리 실패" else "공급자 요청 실패 (HTTP $status)")

// SDK의 JsonValue.from은 Kotlin JSON 타입이 아닌 일반 컬렉션과 스칼라를 받는다.
internal fun JsonElement.schemaValue(): Any? = when (this) {
    JsonNull -> null
    is JsonObject -> mapValues { it.value.schemaValue() }
    is JsonArray -> map { it.schemaValue() }
    is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: content.toBigDecimal()
}

internal fun Exception.toLlmTransportFailure(): LlmServiceException {
    val timedOut = generateSequence<Throwable>(this) { it.cause }
        .any { it is java.io.InterruptedIOException || it is java.util.concurrent.TimeoutException }
    return LlmServiceException(reason = if (timedOut) "timeout" else "provider_error")
}

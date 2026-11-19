package collector.dev

import collector.llm.*
import collector.port.SecretProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.Locale

internal suspend fun llmSmoke(secrets: SecretProvider): Boolean {
    val schema = Json.parseToJsonElement(
        """{"type":"object","properties":{"ok":{"type":"boolean"}},"required":["ok"],"additionalProperties":false}"""
    ).jsonObject
    val request = LlmRequest("", "지정된 JSON 형식으로 응답하세요.", "ok를 true로 설정하세요.", schema, "smoke", 128, Effort.LOW)
    val anthropic = smokeProvider(ProviderId.ANTHROPIC, secrets.get("ANTHROPIC_API_KEY"), ModelTier.STRONG, request)
    val openai = smokeProvider(ProviderId.OPENAI, secrets.get("OPENAI_API_KEY"), ModelTier.FAST, request)
    return anthropic && openai
}

private suspend fun smokeProvider(id: ProviderId, key: String?, tier: ModelTier, request: LlmRequest): Boolean {
    val model = checkNotNull(ModelCatalog.defaults.defaultModel(id, tier)).model
    if (key == null) {
        println("$id · $model · 실패 · 키 없음")
        return false
    }
    return try {
        val result = withTimeoutOrNull(60_000) {
            when (id) {
                ProviderId.ANTHROPIC -> AnthropicProvider(key).use { it.complete(request.copy(model = model)) }
                ProviderId.OPENAI -> OpenAiProvider(key).use { it.complete(request.copy(model = model)) }
            }
        }
        if (result == null) {
            println("$id · $model · 실패 · 시간 초과")
            return false
        }
        val cost = String.format(Locale.ROOT, "%.8f", result.costUsd)
        val refused = if (result.refused) "예" else "아니요"
        println("${result.provider} · ${result.model} · 입력 ${result.inputTokens} · 출력 ${result.outputTokens} · " +
            "캐시 읽기 ${result.cachedInputTokens} · 캐시 쓰기 ${result.cacheWriteTokens} · 추정 USD $cost · " +
            "priceFallback=${result.priceFallback} · priceUnverified=${result.priceUnverified} · 거절 $refused")
        !result.refused && Json.parseToJsonElement(result.json).jsonObject["ok"].toString() == "true"
    } catch (e: CancellationException) {
        throw e
    } catch (e: LlmAuthException) {
        println("$id · $model · 실패 · 인증 오류")
        false
    } catch (e: LlmServiceException) {
        println("$id · $model · 실패 · ${e.reason}")
        false
    } catch (_: Exception) {
        println("$id · $model · 실패 · 응답 처리 오류")
        false
    }
}

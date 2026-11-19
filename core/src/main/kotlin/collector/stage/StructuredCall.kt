package collector.stage

import collector.llm.*
import collector.prompt.ResolvedPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

internal fun schema(vararg fields: Pair<String, JsonObject>): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", JsonObject(fields.toMap()))
    put("required", JsonArray(fields.map { JsonPrimitive(it.first) }))
    put("additionalProperties", false)
}

internal fun type(name: String) = buildJsonObject { put("type", name) }
internal fun array(element: JsonObject) = buildJsonObject {
    put("type", "array")
    put("items", element)
}
internal val string = type("string")
internal val strings = array(string)
internal const val TRUNCATED_RESPONSE = "모델 호출 실패 (truncated)"

internal sealed interface Response<out T> {
    data class Ok<T>(val value: T) : Response<T>
    data class Failed(val reason: String) : Response<Nothing>
}

internal suspend inline fun <reified T> structuredCall(
    router: LlmRouter, stage: String, prompts: List<ResolvedPrompt>, system: String,
    user: JsonObject, schema: JsonObject, tokens: Int, retryInvalid: Boolean = false
): Response<T> {
    repeat(if (retryInvalid) 2 else 1) {
        try {
            val response = router.complete(stage, LlmRequest("", system, user.toString(), schema, stage, tokens, Effort.LOW),
                prompts.first().promptId, prompts.first().version, prompts.associate { it.promptId to it.version },
                fallbackOnInvalidJson = false)
            if (response.refused) return Response.Failed("응답 거절")
            return Response.Ok(Json.decodeFromString<T>(response.json))
        } catch (e: CancellationException) {
            throw e
        } catch (_: BudgetExceededException) {
            return Response.Failed("비용 상한")
        } catch (e: LlmServiceException) {
            if (e.reason == "truncated") return Response.Failed(TRUNCATED_RESPONSE)
            if (e.reason != "invalid_json") return Response.Failed("모델 호출 실패 (${e.reason})")
        } catch (_: SerializationException) {
            // 잘못된 JSON은 허용된 횟수만큼 다시 요청한다.
        } catch (_: LlmAuthException) {
            return Response.Failed("인증 오류")
        } catch (_: LlmKeyMissingException) {
            return Response.Failed("공급자 키 없음")
        } catch (_: Exception) {
            return Response.Failed("모델 호출 실패")
        }
    }
    return Response.Failed("JSON 응답 오류")
}

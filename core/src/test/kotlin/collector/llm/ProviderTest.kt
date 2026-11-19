package collector.llm

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import kotlin.test.*

class ProviderTest {
    private val schema = Json.parseToJsonElement(
        """{"type":"object","properties":{"ok":{"type":"boolean"}},"required":["ok"],"additionalProperties":false}"""
    ).jsonObject
    private val request = LlmRequest("", "시스템 지시", "사용자 데이터", schema, "test", 128, Effort.LOW)

    @Test fun anthropicUsesSchemaCacheAndOpusBetaFallback() = runTest {
        MockWebServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                for ((model, beta) in listOf(
                    "claude-haiku-5-5" to false,
                    "claude-opus-5-5" to true,
                    "claude-opus-5-5-20261101" to true
                )) {
                    val stop = if (beta) "refusal" else "end_turn"
                    server.enqueue(json("""{"id":"msg_1","type":"message","role":"assistant","model":"$model",
                      "content":[{"type":"text","text":"{\"ok\":true}"}],"stop_reason":"$stop","stop_sequence":null,
                      "usage":{"input_tokens":10,"output_tokens":4,"cache_read_input_tokens":20,"cache_creation_input_tokens":30}}"""))
                    val result = provider.complete(request.copy(model = model))
                    val call = server.takeRequest()
                    val body = Json.parseToJsonElement(call.body.readUtf8()).jsonObject
                    assertEquals("/v1/messages", call.path)
                    assertEquals(model, body["model"]?.jsonPrimitive?.content)
                    assertEquals(schema, body["output_config"]?.jsonObject?.get("format")?.jsonObject?.get("schema"))
                    assertEquals(JsonPrimitive("low"), body["output_config"]?.jsonObject?.get("effort"))
                    val system = body["system"]!!.jsonArray.single().jsonObject
                    assertEquals(JsonPrimitive(request.system), system["text"])
                    assertEquals(JsonPrimitive("ephemeral"), system["cache_control"]?.jsonObject?.get("type"))
                    val user = body["messages"]!!.jsonArray.single().jsonObject
                    assertEquals(JsonPrimitive("user"), user["role"])
                    assertEquals(JsonPrimitive(request.user), user["content"])
                    if (beta) {
                        assertTrue(call.getHeader("anthropic-beta").orEmpty().contains("server-side-fallback-2026-07-01"))
                        assertEquals(JsonPrimitive("default"), body["fallbacks"])
                    }
                    assertTrue(setOf("thinking", "budget_tokens", "temperature", "top_p", "top_k").none { it in body })
                    assertEquals(listOf(60, 4, 20, 30),
                        listOf(result.inputTokens, result.outputTokens, result.cachedInputTokens, result.cacheWriteTokens))
                    assertEquals(beta, result.refused)
                    assertEquals(if (beta) "" else """{"ok":true}""", result.json)
                    assertEquals(if (beta) 0.000274 else 0.00000695, result.costUsd, 1e-12)
                }
            }
        }
    }

    @Test fun openAiUsesStrictSchemaAndParsesUsageAndRefusal() = runTest {
        MockWebServer().use { server ->
            OpenAiProvider("mock", server.url("/v1").toString()).use { provider ->
                for (refused in listOf(false, true)) {
                    val message = if (refused) """ "refusal":"거절" """ else """ "content":"{\"ok\":true}" """
                    server.enqueue(json("""{"id":"chat_1","object":"chat.completion","created":1,"model":"gpt-5.4-mini",
                      "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant",$message}}],
                      "usage":{"prompt_tokens":100,"completion_tokens":5,"total_tokens":105,
                      "prompt_tokens_details":{"cached_tokens":40}}}"""))
                    val result = provider.complete(request.copy(model = "gpt-5.4-mini"))
                    val call = server.takeRequest()
                    assertEquals("/v1/chat/completions", call.path)
                    val body = Json.parseToJsonElement(call.body.readUtf8()).jsonObject
                    assertEquals(JsonPrimitive("gpt-5.4-mini"), body["model"])
                    val format = body["response_format"]!!.jsonObject
                    assertEquals(JsonPrimitive("json_schema"), format["type"])
                    val jsonSchema = format["json_schema"]!!.jsonObject
                    assertEquals(JsonPrimitive(true), jsonSchema["strict"])
                    assertEquals(JsonPrimitive("test"), jsonSchema["name"])
                    assertEquals(schema, jsonSchema["schema"])
                    assertEquals(JsonPrimitive("low"), body["reasoning_effort"])
                    assertEquals(listOf("system", "user"), body["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content })
                    assertEquals(listOf(request.system, request.user), body["messages"]!!.jsonArray.map { it.jsonObject["content"]!!.jsonPrimitive.content })
                    assertTrue(setOf("thinking", "budget_tokens", "temperature", "top_p", "top_k").none { it in body })
                    assertEquals(listOf(100, 5, 40), listOf(result.inputTokens, result.outputTokens, result.cachedInputTokens))
                    assertEquals(refused, result.refused)
                    assertEquals(if (refused) "" else """{"ok":true}""", result.json)
                    assertEquals(0.0000705, result.costUsd, 1e-10)
                }
            }
        }
    }

    @Test fun modelListAuthenticationErrorsDoNotExposeResponseBodies() = runTest {
        MockWebServer().use { server ->
            OpenAiProvider("mock", server.url("/v1").toString()).use { provider ->
                server.enqueue(json("""{"object":"list","data":[{"id":"gpt-5.4-mini","object":"model","created":1,"owned_by":"openai"}]}"""))
                assertEquals(listOf(ModelInfo("gpt-5.4-mini")), provider.listModels())
                assertEquals("/v1/models", server.takeRequest().path)
                server.enqueue(json("""{"error":{"message":"원본 오류"}}""").setResponseCode(401))
                val error = assertFailsWith<LlmAuthException> { provider.listModels() }
                assertFalse(error.message.orEmpty().contains("원본 오류"))
                assertNull(error.cause)
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun openAiPricesSnapshotUsingItsBaseModelAndMapsCompletionErrors() = runTest {
        MockWebServer().use { server ->
            OpenAiProvider("mock", server.url("/v1").toString()).use { provider ->
                val req = request.copy(model = "gpt-5.4-mini")
                val snapshot = "gpt-5.4-mini-2026-03-17"
                server.enqueue(json("""{"id":"chat_1","object":"chat.completion","created":1,"model":"$snapshot",
                    "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"{}"}}],
                    "usage":{"prompt_tokens":52,"completion_tokens":25,"total_tokens":77}}"""))
                val result = provider.complete(req)
                assertEquals(snapshot, result.model)
                assertEquals(0.0001515, result.costUsd, 1e-12)
                assertFalse(result.priceFallback)
                assertTrue(result.priceUnverified)

                for (status in listOf(401, 429, 503)) {
                    server.enqueue(json("""{"error":{"message":"원본 오류"}}""").setResponseCode(status))
                    val error = if (status == 401) assertFailsWith<LlmAuthException> { provider.complete(req) }
                    else assertFailsWith<LlmServiceException> { provider.complete(req) }
                    if (error is LlmServiceException) {
                        assertEquals(if (status == 503) "http_5xx" else "http_4xx", error.reason)
                    }
                    assertFalse(error.message.orEmpty().contains("원본 오류"))
                    assertNull(error.cause)
                }
            }
        }
    }

    @Test fun validatesPricesBeforeCallingAndEstimatesUnknownResponseModel() = runTest {
        MockWebServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { anthropic ->
                OpenAiProvider("mock", server.url("/v1").toString()).use { openAi ->
                    val guard = CostGuard(1.0)
                    for (provider in listOf(anthropic, openAi)) {
                        assertFailsWith<LlmConfigException> { provider.complete(request.copy(model = "unknown")) }
                        val router = LlmRouter(listOf(anthropic, openAi), guard,
                            stageModels = mapOf("classify" to StageModel(provider.id, "unknown")))
                        assertFailsWith<LlmConfigException> { router.complete("classify", request, "classify", "default") }
                    }
                    assertEquals(0, server.requestCount)
                    server.enqueue(anthropicResponse(model = "server-model"))
                    val result = anthropic.complete(request.copy(model = "claude-haiku-5-5"))
                    assertEquals("server-model", result.model)
                    assertTrue(result.priceFallback)
                    assertFalse(result.priceUnverified)
                    assertEquals(0.00012, result.costUsd, 1e-12)
                }
            }
        }
    }

    @Test fun recordsTruncationAndParsingCostsBeforeFallback() = runBlocking {
        MockWebServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { anthropic ->
                OpenAiProvider("mock", server.url("/v1").toString()).use { openAi ->
                    val failures = listOf(
                        ProviderId.ANTHROPIC to anthropicResponse(stop = "max_tokens"),
                        ProviderId.OPENAI to openAiResponse(stop = "length"),
                        ProviderId.ANTHROPIC to anthropicResponse(content = """[{"type":"text","text":"invalid"}]"""),
                        ProviderId.ANTHROPIC to anthropicResponse(content = "[]"),
                        ProviderId.OPENAI to openAiResponse(content = """"content":"invalid""""),
                        ProviderId.OPENAI to openAiResponse(content = """"content":null""")
                    )
                    val reasons = listOf("truncated", "truncated", "invalid_json", "empty", "invalid_json", "empty")
                    for ((failureCase, reason) in failures.zip(reasons)) {
                        val (primary, failure) = failureCase
                        val guard = CostGuard(1.0)
                        val model = if (primary == ProviderId.ANTHROPIC) "claude-haiku-5-5" else "gpt-5.4-mini"
                        val router = LlmRouter(listOf(anthropic, openAi), guard,
                            stageModels = mapOf("classify" to StageModel(primary, model)))
                        server.enqueue(failure)
                        server.enqueue(if (primary == ProviderId.ANTHROPIC) openAiResponse() else anthropicResponse())
                        val result = router.complete("classify", request, "classify", "default")
                        val (failed, recovered) = guard.calls
                        assertNotEquals(primary, result.provider)
                        assertEquals(primary, failed.provider)
                        assertEquals(10, failed.inputTokens)
                        assertEquals(4, failed.outputTokens)
                        assertEquals(if (primary == ProviderId.ANTHROPIC) 0.000003 else 0.0000255, failed.costUsd, 1e-12)
                        assertEquals(reason, failed.error)
                        assertFalse(failed.priceFallback)
                        assertTrue(failed.priceUnverified)
                        assertFalse(recovered.priceFallback)
                        assertTrue(recovered.priceUnverified)
                        assertTrue(recovered.fallbackUsed)
                    }
                }
            }
        }
    }

    @Test fun prefersExactPricesAndMatchesCompactSnapshotsWithinProvider() {
        val mini = ModelPrice(ProviderId.OPENAI, "gpt-5.4-mini", ModelTier.FAST, 1.0, 4.0, 0.1)
        val catalog = ModelCatalog(listOf(
            mini,
            mini.copy(model = "gpt-5.4", inputUsd = 2.0),
            mini.copy(model = "gpt-5.4-mini-20260317", inputUsd = 3.0),
            mini.copy(provider = ProviderId.ANTHROPIC, model = "gpt-5.4-mini-20260318", inputUsd = 9.0)
        ))
        assertEquals(3.0, catalog.find(ProviderId.OPENAI, "gpt-5.4-mini-20260317")?.inputUsd)
        assertEquals(mini, catalog.find(ProviderId.OPENAI, "gpt-5.4-mini-20260318"))
        val unknown = catalog.response(ProviderId.OPENAI, "gpt-5.4-mini-other-20260318", LlmUsage(1, 0)) { "{}" }
        assertTrue(unknown.priceFallback)
        assertEquals(0.000003, unknown.costUsd, 1e-12)
    }

    @Test fun usesOptionalLongContextCacheReadPriceOnlyAboveThreshold() {
        val price = ModelPrice(ProviderId.OPENAI, "model", ModelTier.FAST, 1.0, 2.0, 0.1,
            longContext = LongContextPrice(100, 3.0, 4.0, cachedInput = 0.5))
        val catalog = ModelCatalog(listOf(price))
        assertEquals(0.000012, catalog.costUsd(price.provider, price.model, 100, 1, 100), 1e-12)
        assertEquals(0.000057, catalog.costUsd(price.provider, price.model, 101, 1, 100), 1e-12)
        val noOverride = ModelCatalog(listOf(price.copy(longContext = price.longContext!!.copy(cachedInput = null))))
        assertEquals(0.000017, noOverride.costUsd(price.provider, price.model, 101, 1, 100), 1e-12)
    }

    @Test fun mapsAnthropicErrorsAndUsesLongContextPricingAboveThreshold() = runTest {
        MockWebServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val req = request.copy(model = "claude-haiku-5-5")
                server.enqueue(json("""{"error":{"message":"원본 오류"}}""").setResponseCode(401))
                assertNull(assertFailsWith<LlmAuthException> { provider.complete(req) }.cause)
                server.enqueue(json("""{"error":{"message":"원본 오류"}}""").setResponseCode(503))
                assertEquals("공급자 요청 실패 (HTTP 503)", assertFailsWith<LlmServiceException> { provider.complete(req) }.message)
                server.enqueue(anthropicResponse(input = 100_000))
                assertEquals(0.010002, provider.complete(req).costUsd, 1e-12)
                server.enqueue(anthropicResponse(input = 100_001))
                assertEquals(0.0500105, provider.complete(req).costUsd, 1e-12)
            }
        }
    }

    private fun anthropicResponse(
        model: String = "claude-haiku-5-5", stop: String = "end_turn", input: Int = 10,
        content: String = """[{"type":"text","text":"{}"}]"""
    ) = json("""{"id":"msg_1","type":"message","role":"assistant","model":"$model",
        "content":$content,"stop_reason":"$stop","stop_sequence":null,
        "usage":{"input_tokens":$input,"output_tokens":4}}""")

    private fun openAiResponse(stop: String = "stop", content: String = """"content":"{}"""") =
        json("""{"id":"chat_1","object":"chat.completion","created":1,"model":"gpt-5.4-mini",
            "choices":[{"index":0,"finish_reason":"$stop","message":{"role":"assistant",$content}}],
            "usage":{"prompt_tokens":10,"completion_tokens":4,"total_tokens":14}}""")

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}

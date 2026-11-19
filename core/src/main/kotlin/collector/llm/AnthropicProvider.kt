package collector.llm

import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicInvalidDataException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams as BetaMessageCreateParams
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await

class AnthropicProvider(
    apiKey: String,
    baseUrl: String = "https://api.anthropic.com",
    private val catalog: ModelCatalog = ModelCatalog.defaults
) : LlmProvider, AutoCloseable {
    override val id = ProviderId.ANTHROPIC
    private val client = AnthropicOkHttpClientAsync.builder().apiKey(apiKey).baseUrl(baseUrl).maxRetries(0).build()

    override fun close() = client.close()

    override suspend fun listModels(): List<ModelInfo> = sdk {
        buildList {
            var page = client.models().list().await()
            while (true) {
                addAll(page.items().map { ModelInfo(it.id(), it.displayName()) })
                if (!page.hasNextPage()) break
                page = page.nextPage().await()
            }
        }
    }

    override suspend fun complete(req: LlmRequest): LlmResult = sdk {
        val matchedModel = catalog.requirePrice(id, req.model).model
        val schema = req.jsonSchema.mapValues { JsonValue.from(it.value.schemaValue()) }
        val effort = req.effort.name.lowercase()
        if (matchedModel == "claude-opus-5-5") {
            val params = BetaMessageCreateParams.builder()
                .model(req.model)
                .maxTokens(req.maxOutputTokens.toLong())
                .systemOfBetaTextBlockParams(listOf(BetaTextBlockParam.builder()
                    .text(req.system).cacheControl(BetaCacheControlEphemeral.builder().build()).build()))
                .addUserMessage(req.user)
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(effort))
                    .format(BetaJsonOutputFormat.builder()
                        .schema(BetaJsonOutputFormat.Schema.builder().additionalProperties(schema).build()).build())
                    .build())
                .addBeta("server-side-fallback-2026-07-01")
                .fallbacks(BetaFallbacksParam.ofDefault())
                .build()
            val message = client.beta().messages().create(params).await()
            val usage = message.usage()
            catalog.response(id, message.model().asString(), tokens(usage.inputTokens(), usage.outputTokens(),
                usage.cacheReadInputTokens().orElse(0), usage.cacheCreationInputTokens().orElse(0))) {
                when (message.stopReason().orElse(null)) {
                    BetaStopReason.MAX_TOKENS -> throw LlmServiceException(reason = "truncated")
                    BetaStopReason.REFUSAL -> null
                    else -> message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
                }
            }
        } else {
            val params = MessageCreateParams.builder()
                .model(req.model)
                .maxTokens(req.maxOutputTokens.toLong())
                .systemOfTextBlockParams(listOf(TextBlockParam.builder()
                    .text(req.system).cacheControl(CacheControlEphemeral.builder().build()).build()))
                .addUserMessage(req.user)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(effort))
                    .format(JsonOutputFormat.builder()
                        .schema(JsonOutputFormat.Schema.builder().additionalProperties(schema).build()).build())
                    .build())
                .build()
            val message = client.messages().create(params).await()
            val usage = message.usage()
            catalog.response(id, message.model().asString(), tokens(usage.inputTokens(), usage.outputTokens(),
                usage.cacheReadInputTokens().orElse(0), usage.cacheCreationInputTokens().orElse(0))) {
                when (message.stopReason().orElse(null)) {
                    StopReason.MAX_TOKENS -> throw LlmServiceException(reason = "truncated")
                    StopReason.REFUSAL -> null
                    else -> message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
                }
            }
        }
    }

    private fun tokens(input: Long, output: Long, cached: Long, cacheWrites: Long) =
        LlmUsage((input + cached + cacheWrites).toInt(), output.toInt(), cached.toInt(), cacheWrites.toInt())

    private suspend fun <T> sdk(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: LlmConfigException) {
        throw e
    } catch (e: LlmServiceException) {
        throw e
    } catch (e: AnthropicServiceException) {
        if (e.statusCode() in listOf(401, 403)) throw LlmAuthException()
        throw LlmServiceException(e.statusCode())
    } catch (_: AnthropicInvalidDataException) {
        throw LlmServiceException(reason = "invalid_json")
    } catch (e: Exception) {
        throw e.toLlmTransportFailure()
    }
}

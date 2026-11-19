package collector.llm

import com.openai.client.okhttp.OpenAIOkHttpClientAsync
import com.openai.core.JsonValue
import com.openai.errors.OpenAIInvalidDataException
import com.openai.errors.OpenAIServiceException
import com.openai.models.ReasoningEffort
import com.openai.models.ResponseFormatJsonSchema.JsonSchema
import com.openai.models.chat.completions.ChatCompletionCreateParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await

class OpenAiProvider(
    apiKey: String,
    baseUrl: String = "https://api.openai.com/v1",
    private val catalog: ModelCatalog = ModelCatalog.defaults
) : LlmProvider, AutoCloseable {
    override val id = ProviderId.OPENAI
    private val client = OpenAIOkHttpClientAsync.builder().apiKey(apiKey).baseUrl(baseUrl).maxRetries(0).build()

    override fun close() = client.close()

    override suspend fun listModels(): List<ModelInfo> = sdk {
        buildList {
            var page = client.models().list().await()
            while (true) {
                addAll(page.items().map { ModelInfo(it.id()) })
                if (!page.hasNextPage()) break
                page = page.nextPage().await()
            }
        }
    }

    override suspend fun complete(req: LlmRequest): LlmResult = sdk {
        catalog.requirePrice(id, req.model)
        val schema = JsonSchema.Schema.builder()
            .additionalProperties(req.jsonSchema.mapValues { JsonValue.from(it.value.schemaValue()) }).build()
        val params = ChatCompletionCreateParams.builder()
            .model(req.model)
            .addSystemMessage(req.system)
            .addUserMessage(req.user)
            .maxCompletionTokens(req.maxOutputTokens.toLong())
            .jsonSchemaResponseFormat(JsonSchema.builder().name(req.schemaName).schema(schema).strict(true).build())
        if (req.model.startsWith("gpt-5") || Regex("^o[134](?:-|$)").containsMatchIn(req.model)) {
            params.reasoningEffort(ReasoningEffort.of(req.effort.name.lowercase()))
        }

        val response = client.chat().completions().create(params.build()).await()
        val usage = response.usage().orElseThrow()
        val input = usage.promptTokens().toInt()
        val output = usage.completionTokens().toInt()
        val cached = usage.promptTokensDetails().orElse(null)?.cachedTokens()?.orElse(0)?.toInt() ?: 0
        catalog.response(id, response.model(), LlmUsage(input, output, cached)) {
            val choice = response.choices().firstOrNull() ?: throw LlmServiceException(reason = "empty")
            val message = choice.message()
            val reason = choice.finishReason().toString()
            when {
                reason == "length" || reason == "incomplete" -> throw LlmServiceException(reason = "truncated")
                message.refusal().isPresent || reason == "content_filter" -> null
                else -> message.content().orElse("")
            }
        }
    }

    private suspend fun <T> sdk(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: LlmConfigException) {
        throw e
    } catch (e: LlmServiceException) {
        throw e
    } catch (e: OpenAIServiceException) {
        if (e.statusCode() in listOf(401, 403)) throw LlmAuthException()
        throw LlmServiceException(e.statusCode())
    } catch (_: OpenAIInvalidDataException) {
        throw LlmServiceException(reason = "invalid_json")
    } catch (e: Exception) {
        throw e.toLlmTransportFailure()
    }
}

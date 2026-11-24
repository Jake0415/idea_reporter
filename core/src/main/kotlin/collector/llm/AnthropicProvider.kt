package collector.llm

import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync
import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.WebSearchTool20260209
import com.anthropic.models.messages.batches.BatchCreateParams
import com.anthropic.models.messages.batches.MessageBatch
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse
import collector.config.PipelineConfig
import collector.model.SupplementRef
import collector.refine.UrlNormalizer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
import java.time.Instant

class AnthropicProvider(
    apiKey: String,
    baseUrl: String = "https://api.anthropic.com",
    private val catalog: ModelCatalog = ModelCatalog.defaults
) : BatchCapableProvider, WebSearchProvider, AutoCloseable {
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
        if (matchedModel == "claude-opus-5-5") {
            val schema = req.jsonSchema.mapValues { JsonValue.from(it.value.schemaValue()) }
            val effort = req.effort.name.lowercase()
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
            decode(client.messages().create(params(req)).await())
        }
    }

    private fun baseParams(req: LlmRequest) = MessageCreateParams.builder()
        .model(req.model)
        .maxTokens(req.maxOutputTokens.toLong())
        .systemOfTextBlockParams(listOf(TextBlockParam.builder()
            .text(req.system).cacheControl(CacheControlEphemeral.builder().build()).build()))
        .addUserMessage(req.user)

    private fun params(req: LlmRequest): MessageCreateParams = baseParams(req)
        .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(req.effort.name.lowercase()))
            .format(JsonOutputFormat.builder().schema(JsonOutputFormat.Schema.builder()
                .additionalProperties(req.jsonSchema.mapValues { JsonValue.from(it.value.schemaValue()) })
                .build()).build()).build())
        .build()

    private fun usage(message: Message): LlmUsage = message.usage().let {
        tokens(it.inputTokens(), it.outputTokens(), it.cacheReadInputTokens().orElse(0), it.cacheCreationInputTokens().orElse(0))
    }

    private fun decode(message: Message, batch: Boolean = false): LlmResult =
        catalog.response(id, message.model().asString(), usage(message), batch) {
            when (message.stopReason().orElse(null)) {
                StopReason.MAX_TOKENS -> throw LlmServiceException(reason = "truncated")
                StopReason.REFUSAL -> null
                else -> message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
            }
        }

    override fun prepareBatch(requests: Map<String, LlmRequest>): suspend () -> BatchState {
        val opus = catalog.requirePrice(id, requests.values.first().model).model == "claude-opus-5-5"
        val params = BatchCreateParams.builder().requests(requests.map { (customId, req) ->
            val body = params(req)._body()
            val batchParams = BatchCreateParams.Request.Params.builder()
                .model(body._model()).maxTokens(body._maxTokens()).messages(body._messages())
                .systemOfTextBlockParams(body.system().get().asTextBlockParams())
                .outputConfig(body._outputConfig())
            if (opus) batchParams.putAdditionalProperty("fallbacks", JsonValue.from("default"))
            BatchCreateParams.Request.builder().customId(customId).params(batchParams.build()).build()
        })
        if (opus) params.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
        val prepared = params.build().also { jsonMapper().writeValueAsBytes(it._body().validate()) }
        return { sdk { client.messages().batches().create(prepared).await().toBatchState() } }
    }

    private fun MessageBatch.toBatchState() = BatchState(id(),
        processingStatus() == MessageBatch.ProcessingStatus.ENDED,
        processingStatus() == MessageBatch.ProcessingStatus.CANCELING)

    override suspend fun findBatches(since: Instant): List<BatchState> = sdk {
        buildList {
            var page = client.messages().batches().list().await()
            while (true) {
                for (batch in page.items()) {
                    if (batch.createdAt().toInstant().isBefore(since)) return@buildList
                    add(batch.toBatchState())
                }
                if (!page.hasNextPage()) break
                page = page.nextPage().await()
            }
        }
    }

    override suspend fun retrieveBatch(id: String): BatchState = sdk { client.messages().batches().retrieve(id).await().toBatchState() }
    override suspend fun cancelBatch(id: String): BatchState = sdk { client.messages().batches().cancel(id).await().toBatchState() }

    override suspend fun batchResults(id: String, receive: (String, Result<LlmResult>?) -> Unit): Unit = sdk {
        val stream = client.messages().batches().resultsStreaming(id)
        try {
            stream.subscribe { receive(it.customId(), decodeBatch(it)) }.onCompleteFuture().await()
        } finally {
            stream.close()
        }
    }

    private fun decodeBatch(row: MessageBatchIndividualResponse): Result<LlmResult>? =
        row.result().succeeded().orElse(null)?.let { runCatching { decode(it.message(), batch = true) } }

    override suspend fun search(req: LlmRequest, config: PipelineConfig.Search): SearchResult = sdk {
        val tool = WebSearchTool20260209.builder().maxUses(config.maxUses.toLong())
        if (config.allowedDomains.isNotEmpty()) tool.allowedDomains(config.allowedDomains)
        if (config.blockedDomains.isNotEmpty()) tool.blockedDomains(config.blockedDomains)
        val message = client.messages().create(baseParams(req).addTool(tool.build()).build()).await()
        val blocks = message.content().mapNotNull { it.webSearchToolResult().orElse(null)?.content() }
        val error = when {
            message.stopReason().orElse(null) in setOf(StopReason.REFUSAL, StopReason.MAX_TOKENS, StopReason.PAUSE_TURN) -> "incomplete"
            blocks.isEmpty() || blocks.any { it.error().isPresent } -> "tool_error"
            else -> null
        }
        val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        val notes = try {
            Json.decodeFromString<List<SupplementRef>>(text).associate { UrlNormalizer.normalize(it.url) to it.note }
        } catch (_: SerializationException) {
            emptyMap()
        }
        val refs = blocks.flatMap { it.resultBlocks().orElse(emptyList()) }.mapNotNull {
            it.url().toHttpUrlOrNull()?.takeIf { url -> url.username.isEmpty() && url.password.isEmpty() }
                ?: return@mapNotNull null
            SupplementRef(it.url(), it.title(), notes[UrlNormalizer.normalize(it.url())].orEmpty())
        }
        SearchResult(refs, usage(message), message.usage().serverToolUse().orElse(null)?.webSearchRequests()?.toInt() ?: 0,
            message.model().asString(), error)
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

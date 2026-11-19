package collector.stage

import collector.config.PipelineConfig
import collector.llm.LlmRouter
import collector.model.*
import collector.profile.ProfileLoader
import collector.prompt.PromptResolver
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

data class ClassificationResult(val signals: List<Signal>, val dropped: List<DroppedItem>)

class Classifier(
    private val config: PipelineConfig, private val profiles: ProfileLoader.ProfileSet,
    private val prompts: PromptResolver, private val router: LlmRouter
) {
    suspend fun classify(items: List<Item>, topics: List<TopicHistory>, tracked: List<TrackedIdea>,
                         hnContexts: Map<String, HnContext> = emptyMap()): ClassificationResult {
        val resolved = listOf(prompts.resolve("classify")) + profiles.ids.flatMap {
            listOf(prompts.resolve("profile.$it.match"), prompts.resolve("profile.$it.analysis"))
        }
        val system = resolved.joinToString("\n\n") { "${it.promptId}\n${it.text}" }
        val signals = mutableListOf<Signal>()
        val dropped = mutableListOf<DroppedItem>()
        suspend fun classifyBatch(batch: List<Item>, splitDepth: Int) {
            val candidates = batch.associate { it.key to it.profileHint.ifEmpty { profiles.ids } }
            suspend fun request(batch: List<Item>, retryInvalid: Boolean): Response<Output> {
                val user = buildJsonObject {
                    put("items", JsonArray(batch.map { item ->
                        val hints = candidates.getValue(item.key)
                        buildJsonObject {
                            put("itemId", item.key)
                            put("source", item.source)
                            put("title", item.title)
                            put("snippet", item.snippet.take(minOf(300, maxSnippetChars(item))))
                            putHnContext(hnContexts[item.key])
                            put("rawScore", item.rawScore)
                            put("alsoSeenIn", Json.encodeToJsonElement(item.alsoSeenIn))
                            put("candidates", Json.encodeToJsonElement(hints))
                            if (item.trackHint.isNotEmpty()) put("trackHint", Json.encodeToJsonElement(item.trackHint))
                        }
                    }))
                    put("recentTopics", JsonArray(topics.distinctBy { it.topicKey }.map { topic ->
                        buildJsonObject { put("topicKey", topic.topicKey); put("topicLabel", topic.topicLabel) }
                    }))
                    put("tracked", JsonArray(tracked.map { idea ->
                        buildJsonObject {
                            put("trackedId", idea.id)
                            put("title", idea.title)
                            put("keywords", Json.encodeToJsonElement(idea.keywords))
                        }
                    }))
                }
                return structuredCall<Output>(router, "classify", resolved, system, user, outputSchema,
                    batch.size * 400 + 128, retryInvalid = retryInvalid)
            }
            val result = request(batch, retryInvalid = true)
            if (result == Response.Failed(TRUNCATED_RESPONSE) && batch.size > 1 && splitDepth < 2) {
                val midpoint = batch.size / 2
                classifyBatch(batch.subList(0, midpoint), splitDepth + 1)
                classifyBatch(batch.subList(midpoint, batch.size), splitDepth + 1)
                return
            }
            val byId = (result as? Response.Ok)?.value?.items.orEmpty().groupBy { it.externalId }
            val unresolved = if (result is Response.Ok) batch.filter { byId[it.key]?.size != 1 } else emptyList()
            val retried = if (unresolved.isEmpty()) emptyMap() else
                (request(unresolved, retryInvalid = false) as? Response.Ok)?.value?.items.orEmpty().groupBy { it.externalId }
            val trackedIds = tracked.map { it.id }.toSet()
            val recentLabels = topics.associate { it.topicKey to it.topicLabel }
            for (item in batch) {
                val row = (byId[item.key]?.singleOrNull() ?: retried[item.key]?.singleOrNull())?.let {
                    it.copy(trackedIdeaIds = it.trackedIdeaIds.filter(trackedIds::contains).distinct(),
                        topicLabel = recentLabels[it.topicKey] ?: it.topicLabel)
                }
                when {
                    row == null -> {
                        val detail = (result as? Response.Failed)?.reason ?: "분류 응답에 항목 누락 또는 중복"
                        val reason = if (detail == "비용 상한") DropReason.BUDGET_EXCEEDED else DropReason.CLASSIFY_FAILED
                        dropped += DroppedItem(item, reason, detail)
                    }
                    row.profile == "none" -> {
                        signals += Signal(item, row, hnContext = hnContexts[item.key])
                        dropped += DroppedItem(item, DropReason.NOT_RELEVANT, row.reason)
                    }
                    row.profile !in candidates.getValue(item.key) || row.profile !in config.profiles ||
                        row.score == null || row.score !in 0.0..10.0 || !topicKey.matches(row.topicKey) ->
                        dropped += DroppedItem(item, DropReason.CLASSIFY_FAILED, "프로필, 점수 또는 주제 키 오류")
                    else -> signals += Signal(item, row, hnContext = hnContexts[item.key])
                }
            }
        }
        for (batch in items.chunked(config.classifyBatchSize)) classifyBatch(batch, 0)
        return ClassificationResult(signals, dropped)
    }

    internal fun maxSnippetChars(item: Item): Int = item.profileHint.ifEmpty { profiles.ids }
        .mapNotNull { profiles[it]?.config?.content?.classifyMaxChars }
        .minOrNull() ?: profiles.default.config.content.classifyMaxChars

    private val topicKey = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

    @Serializable private data class Output(val items: List<ClassifiedItem>)

    private val outputSchema = schema("items" to array(schema(
        "itemId" to string, "profile" to string, "score" to buildJsonObject {
            put("type", JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("null"))))
        }, "reason" to string, "summary" to string, "tags" to strings, "topicKey" to string,
        "topicLabel" to string, "trackedIdeaIds" to strings
    )))
}

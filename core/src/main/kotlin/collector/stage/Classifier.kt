package collector.stage

import collector.config.PipelineConfig
import collector.llm.LlmRouter
import collector.model.*
import collector.profile.ProfileLoader
import collector.prompt.PromptResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

data class ClassificationResult(
    val signals: List<Signal>, val dropped: List<DroppedItem>, val scoreClampWarnings: Int = 0
)

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
        suspend fun classifyBatch(batch: List<Item>, splitDepth: Int): ClassificationResult {
            val candidates = batch.associate { it.key to it.profileHint.ifEmpty { profiles.ids } }
            fun outsideCandidates(row: ClassifiedItem, key: String): Boolean =
                row.profile != "none" && row.profile !in candidates.getValue(key)

            fun profileIssue(row: ClassifiedItem, key: String): String? = when {
                row.profile == "none" -> null
                row.profile !in config.profiles -> "알 수 없는 분류(${row.profile})"
                outsideCandidates(row, key) -> "허용된 분류가 아님(${row.profile})"
                else -> null
            }

            suspend fun request(batch: List<Item>, retry: Boolean): Response<Output> {
                val user = buildJsonObject {
                    put("items", JsonArray(batch.map { item ->
                        val hints = candidates.getValue(item.key)
                        buildJsonObject {
                            put("itemId", item.key)
                            put("source", if (item.source == "github-issues") "GitHub 이슈" else item.source)
                            put("title", item.title)
                            put("snippet", item.snippet.take(minOf(300, maxSnippetChars(item))))
                            putHnContext(hnContexts[item.key])
                            put("rawScore", item.rawScore)
                            put("alsoSeenIn", Json.encodeToJsonElement(item.alsoSeenIn))
                            put("candidates", Json.encodeToJsonElement(if (retry) (hints + "none").distinct() else hints))
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
                    batch.size * 400 + 128, retryInvalid = !retry, allowFallback = !retry)
            }
            val result = request(batch, retry = false)
            if (result == Response.Failed(TRUNCATED_RESPONSE) && batch.size > 1 && splitDepth < 2) {
                val midpoint = batch.size / 2
                val left = classifyBatch(batch.subList(0, midpoint), splitDepth + 1)
                val right = classifyBatch(batch.subList(midpoint, batch.size), splitDepth + 1)
                return ClassificationResult(left.signals + right.signals, left.dropped + right.dropped,
                    left.scoreClampWarnings + right.scoreClampWarnings)
            }
            val byId = (result as? Response.Ok)?.value?.items.orEmpty().groupBy { it.externalId }
            val accepted = byId.mapValues { (key, rows) ->
                rows.singleOrNull()?.takeIf {
                    key in candidates && !outsideCandidates(it, key) &&
                        (it.profile == "none" || it.score != null || it.profile !in config.profiles)
                }
            }
            val unresolved = if (result is Response.Ok) batch.filter { accepted[it.key] == null } else emptyList()
            val retryResult = if (unresolved.isEmpty()) null else request(unresolved, retry = true)
            val retried = (retryResult as? Response.Ok)?.value?.items.orEmpty().groupBy { it.externalId }
            val budgetBlocked = result == Response.Failed(BUDGET_LIMIT) || retryResult == Response.Failed(BUDGET_LIMIT)
            val trackedIds = tracked.map { it.id }.toSet()
            val recentLabels = topics.associate { it.topicKey to it.topicLabel }
            val signals = mutableListOf<Signal>()
            val dropped = mutableListOf<DroppedItem>()
            var scoreClampWarnings = 0
            for (item in batch) {
                val row = accepted[item.key] ?: retried[item.key]?.singleOrNull()
                val originalIssue = byId[item.key]?.singleOrNull()?.let { profileIssue(it, item.key) }
                if (row == null) {
                    val detail = if (budgetBlocked) BUDGET_LIMIT else
                        (retryResult as? Response.Failed)?.reason ?: (result as? Response.Failed)?.reason
                        ?: originalIssue ?: "분류 응답에 항목 누락 또는 중복"
                    val reason = if (budgetBlocked) DropReason.BUDGET_EXCEEDED else DropReason.CLASSIFY_FAILED
                    dropped += DroppedItem(item, reason, detail)
                    continue
                }
                val normalizedKey = if (row.profile == "none") row.topicKey else
                    row.topicKey.lowercase().replace(topicKeySeparators, "-").trim('-').take(60).trimEnd('-')
                val detail = if (row.profile == "none") null else profileIssue(row, item.key) ?: when {
                    row.score == null -> "점수 없음(null)"
                    normalizedKey.isEmpty() -> "주제 키 형식(${row.topicKey.take(40)})"
                    else -> null
                }
                if (detail != null) {
                    dropped += DroppedItem(item, DropReason.CLASSIFY_FAILED, originalIssue ?: detail)
                    continue
                }
                val score = row.score?.let {
                    if (it !in 0.0..10.0) scoreClampWarnings++
                    it.coerceIn(0.0, 10.0)
                }
                signals += Signal(item, row.copy(score = score, topicKey = normalizedKey,
                    topicLabel = recentLabels[normalizedKey] ?: row.topicLabel,
                    trackedIdeaIds = row.trackedIdeaIds.filter(trackedIds::contains).distinct()),
                    hnContext = hnContexts[item.key])
                if (row.profile == "none") dropped += DroppedItem(item, DropReason.NOT_RELEVANT, row.reason)
            }
            return ClassificationResult(signals, dropped, scoreClampWarnings)
        }

        val batches = items.chunked(config.classifyBatchSize)
        if (batches.isEmpty()) return ClassificationResult(emptyList(), emptyList())
        val first = classifyBatch(batches.first(), 0)
        val remaining = batches.drop(1)
        val results = if (config.classifyConcurrency == 1) {
            remaining.map { classifyBatch(it, 0) }
        } else {
            coroutineScope {
                val permits = Semaphore(config.classifyConcurrency)
                remaining.map { batch ->
                    async { permits.withPermit { classifyBatch(batch, 0) } }
                }.awaitAll()
            }
        }
        // awaitAll preserves batch order, and each batch preserves item order.
        val ordered = listOf(first) + results
        return ClassificationResult(ordered.flatMap { it.signals }, ordered.flatMap { it.dropped },
            ordered.sumOf { it.scoreClampWarnings })
    }

    internal fun maxSnippetChars(item: Item): Int = item.profileHint.ifEmpty { profiles.ids }
        .mapNotNull { profiles[it]?.config?.content?.classifyMaxChars }
        .minOrNull() ?: profiles.default.config.content.classifyMaxChars

    private val topicKeySeparators = Regex("[^a-z0-9]+")

    @Serializable private data class Output(val items: List<ClassifiedItem>)

    private val outputSchema = schema("items" to array(schema(
        "itemId" to string, "profile" to string, "score" to buildJsonObject {
            put("type", JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("null"))))
        }, "reason" to string, "summary" to string, "tags" to strings, "topicKey" to string,
        "topicLabel" to string, "trackedIdeaIds" to strings
    )))
}

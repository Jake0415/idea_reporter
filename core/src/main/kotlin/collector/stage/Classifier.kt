package collector.stage

import collector.config.PipelineConfig
import collector.llm.*
import java.io.File
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

@kotlinx.serialization.Serializable
data class ClassificationResult(
    val signals: List<Signal>, val dropped: List<DroppedItem>, val scoreClampWarnings: Int = 0,
    val issues: List<String> = emptyList(), val batchWaitMillis: Long = 0, val batchPending: Boolean = false
)

class Classifier(
    private val config: PipelineConfig, private val profiles: ProfileLoader.ProfileSet,
    private val prompts: PromptResolver, private val router: LlmRouter
) {
    suspend fun classify(items: List<Item>, topics: List<TopicHistory>, tracked: List<TrackedIdea>,
                         hnContexts: Map<String, HnContext> = emptyMap(), runDate: String? = null,
                         out: File? = null, backfill: Boolean = false,
                         quoteOnly: ((LlmRequest) -> Unit)? = null, singleAttempt: Boolean = false): ClassificationResult {
        val resolved = listOf(prompts.resolve("classify")) + profiles.ids.flatMap {
            listOf(prompts.resolve("profile.$it.match"), prompts.resolve("profile.$it.analysis"))
        }
        val system = resolved.joinToString("\n\n") { "${it.promptId}\n${it.text}" }
        fun buildRequest(batch: List<Item>, retry: Boolean): LlmRequest {
            val candidates = batch.associate { it.key to it.profileHint.ifEmpty { profiles.ids } }
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
            return LlmRequest("", system, user.toString(), outputSchema, "classify", batch.size * 400 + 128, Effort.LOW)
        }
        val batches = items.chunked(config.classifyBatchSize)
        if (quoteOnly != null) {
            batches.forEach { quoteOnly(buildRequest(it, false)) }
            return ClassificationResult(emptyList(), emptyList(), 0)
        }
        val stage = if (backfill) "backfill" else "classify"
        val byKey = items.associateBy { it.key }
        fun savedRows(responses: List<Pair<List<String>, LlmResult>>) = buildMap {
            for ((submittedKeys, result) in responses) {
                if (result.refused) continue
                val rows = try {
                    Json.decodeFromString<Output>(result.json).items.groupBy { it.externalId }
                } catch (_: kotlinx.serialization.SerializationException) {
                    continue
                }
                for (key in submittedKeys.ifEmpty { rows.keys.toList() }) {
                    val item = byKey[key] ?: continue
                    val row = rows[key]?.singleOrNull() ?: continue
                    val hints = item.profileHint.ifEmpty { profiles.ids }
                    if (row.profile == "none" || row.profile in hints && row.profile in config.profiles &&
                        row.score != null && normalizedTopicKey(row).isNotEmpty()) putIfAbsent(key, row)
                }
            }
        }
        val historical = if (!backfill && out != null && runDate != null)
            savedRows(priorBatchClassifications(out, runDate, config.recentTopicDays)) else emptyMap()
        val batchInputs = items.filterNot { it.key in historical }.chunked(config.classifyBatchSize)
        val batchOutcome = if (out != null && runDate != null &&
            ((if (backfill) config.batch.backfill else config.batch.classify) || hasSavedBatch(out, runDate, stage))) {
            router.batch(stage, runDate, out, config.batch,
                batchInputs.map { buildRequest(it, false) }, resolved.associate { it.promptId to it.version },
                itemKeys = batchInputs.map { chunk -> chunk.map { it.key } })
        } else null
        if (batchOutcome?.pending == true) return ClassificationResult(emptyList(), emptyList(),
            issues = batchOutcome.issues + CLASSIFICATION_BATCH_PENDING, batchWaitMillis = batchOutcome.waitMillis, batchPending = true)
        if (batches.isEmpty()) return ClassificationResult(emptyList(), emptyList(), issues = batchOutcome?.issues.orEmpty())

        val savedItems = historical + savedRows(batchOutcome?.itemResponses.orEmpty())
        val synchronousKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        suspend fun classifyBatch(batch: List<Item>, splitDepth: Int,
                                  saved: List<ClassifiedItem>? = null): ClassificationResult {
            val candidates = batch.associate { it.key to it.profileHint.ifEmpty { profiles.ids } }
            fun outsideCandidates(row: ClassifiedItem, key: String): Boolean =
                row.profile != "none" && row.profile !in candidates.getValue(key)

            fun profileIssue(row: ClassifiedItem, key: String): String? = when {
                row.profile == "none" -> null
                row.profile !in config.profiles -> "알 수 없는 분류(${row.profile})"
                outsideCandidates(row, key) -> "허용된 분류가 아님(${row.profile})"
                else -> null
            }

            suspend fun request(batch: List<Item>, retry: Boolean): Response<Output> =
                structuredCall(router, "classify", resolved, buildRequest(batch, retry),
                    retryInvalid = !singleAttempt && !retry, allowFallback = !singleAttempt && !retry,
                    onAttempt = { batch.mapTo(synchronousKeys) { it.key } })

            val result = if (saved == null) request(batch, retry = false) else Response.Ok(Output(saved))
            if (!singleAttempt && result == Response.Failed(TRUNCATED_RESPONSE) && batch.size > 1 && splitDepth < 2) {
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
            val retryResult = if (singleAttempt || unresolved.isEmpty()) null else request(unresolved, retry = true)
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
                    normalizedTopicKey(row)
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

        suspend fun classifyChunk(chunk: List<Item>): ClassificationResult {
            val reusable = chunk.filter { it.key in savedItems }
            if (reusable.isEmpty()) return classifyBatch(chunk, 0)
            val reused = classifyBatch(reusable, 0, saved = reusable.map { savedItems.getValue(it.key) })
            val missing = chunk.filterNot { it.key in savedItems }
            if (missing.isEmpty()) return reused
            val fresh = classifyBatch(missing, 0)
            val signals = (reused.signals + fresh.signals).associateBy { it.item.key }
            val dropped = (reused.dropped + fresh.dropped).associateBy { it.item.key }
            return ClassificationResult(chunk.mapNotNull { signals[it.key] }, chunk.mapNotNull { dropped[it.key] },
                reused.scoreClampWarnings + fresh.scoreClampWarnings)
        }

        val first = classifyChunk(batches.first())
        val remaining = batches.drop(1)
        val results = if (config.classifyConcurrency == 1) {
            remaining.map { classifyChunk(it) }
        } else {
            coroutineScope {
                val permits = Semaphore(config.classifyConcurrency)
                remaining.map { batch ->
                    async { permits.withPermit { classifyChunk(batch) } }
                }.awaitAll()
            }
        }
        // awaitAll preserves batch order, and each batch preserves item order.
        val ordered = listOf(first) + results
        val recovery = if (savedItems.isNotEmpty() || batchOutcome?.synchronousFallback == true) {
            val reused = savedItems.keys.count { it !in synchronousKeys }
            listOf("Batch classification: reused $reused, synchronous " + synchronousKeys.size)
        } else emptyList()
        return ClassificationResult(ordered.flatMap { it.signals }, ordered.flatMap { it.dropped },
            ordered.sumOf { it.scoreClampWarnings }, batchOutcome?.issues.orEmpty() + recovery, batchOutcome?.waitMillis ?: 0)
    }

    internal fun maxSnippetChars(item: Item): Int = item.profileHint.ifEmpty { profiles.ids }
        .mapNotNull { profiles[it]?.config?.content?.classifyMaxChars }
        .minOrNull() ?: profiles.default.config.content.classifyMaxChars

    private fun normalizedTopicKey(row: ClassifiedItem) =
        row.topicKey.lowercase().replace(topicKeySeparators, "-").trim('-').take(60).trimEnd('-')

    private val topicKeySeparators = Regex("[^a-z0-9]+")

    @Serializable private data class Output(val items: List<ClassifiedItem>)

    private val outputSchema = schema("items" to array(schema(
        "itemId" to string, "profile" to string, "score" to buildJsonObject {
            put("type", JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("null"))))
        }, "reason" to string, "summary" to string, "tags" to strings, "topicKey" to string,
        "topicLabel" to string, "trackedIdeaIds" to strings
    )))
}

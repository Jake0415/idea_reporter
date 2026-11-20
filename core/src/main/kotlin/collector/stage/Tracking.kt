package collector.stage

import collector.config.*
import collector.llm.LlmRouter
import collector.model.*
import collector.prompt.PromptResolver
import collector.refine.RuleFilter
import collector.refine.SpamClusterFilter
import collector.refine.UrlNormalizer
import collector.source.SourceAdapter
import collector.source.GitHubAdapter
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

interface TrackedStore {
    fun load(): List<TrackedIdea>
    fun save(ideas: List<TrackedIdea>)
}

object TrackingKeywords {
    fun extract(card: IdeaCard, signals: List<Signal>): List<String> {
        val refs = card.techSignals + card.demandSignals + card.competitors
        val related = signals.filter { signal -> refs.any { it.itemId == signal.item.key } }
        val products = card.competitors.mapNotNull { ref ->
            related.firstOrNull { it.item.key == ref.itemId }?.classification?.topicLabel?.takeIf(String::isNotBlank)
        }
        return (words(card.title).take(3) + card.tags + related.map { it.classification.topicKey } + products)
            .filter(String::isNotBlank).distinct()
    }
}

class TrackingSearch(private val config: PipelineConfig.Tracking) {
    suspend fun fetch(idea: TrackedIdea, source: SourceConfig, adapter: SourceAdapter, now: Instant,
                      excluded: List<String> = emptyList()): List<Item> {
        val keywords = idea.keywords.filter(String::isNotBlank)
            .sortedBy { keyword -> if (keyword.all { it.code < 128 } && keyword.any(Char::isLetter)) 0 else 1 }
        val queries = keywords.take(config.queriesPerSource).mapIndexed { index, keyword ->
            val companion = keywords.getOrNull(index + config.queriesPerSource)
            (if (source is HnConfig && companion != null) "$keyword $companion" else keyword).replace('-', ' ')
        }
        if (queries.isEmpty()) return emptyList()
        val minimum = (source.minRawScore * config.minRawScoreFactor).toInt()
        val search = when (source) {
            is HnConfig -> source.copy(queries = queries, minRawScore = minimum)
            is GitHubConfig -> source.copy(queries = queries.map { "\"${it.replace("\"", "")}\" in:name,description" },
                minRawScore = minimum)
            else -> return emptyList()
        }
        val items = if (adapter is GitHubAdapter) adapter.fetchTracking(search, now) else adapter.fetch(search, now)
        val filtered = RuleFilter(excluded).filter(items, mapOf(source.sourceId to search), now)
        return SpamClusterFilter().filter(filtered.kept).kept.sortedByDescending { it.rawScore }
            .distinctBy { UrlNormalizer.normalize(it.url) }.take(10).map {
            it.copy(trackHint = listOf(idea.id), profileHint = it.profileHint.ifEmpty { source.profile })
        }
    }
}

class StrengthCalculator(private val config: PipelineConfig.Tracking) {
    fun update(idea: TrackedIdea, signalsByDay: Map<LocalDate, List<Signal>>, today: LocalDate,
               now: Instant, changed: Boolean): TrackedIdea {
        if (idea.status == TrackedIdea.Status.STOPPED) return idea
        fun strength(offset: Long) = signalsByDay.filterKeys {
            it in today.minusDays(offset + 6)..today.minusDays(offset)
        }.entries.sortedByDescending { it.key }.flatMap { it.value }
            .filter { idea.id in it.classification.trackedIdeaIds }.distinctBy { it.item.key }
            .sumOf { (config.strengthWeights[it.classification.profile] ?: 0.0) * (it.classification.score ?: 0.0) / 10 }
        val current = strength(0)
        val prior = strength(7)
        val trend = when {
            current > prior && current >= prior * 1.2 -> TrackedIdea.Trend.UP
            current < prior && current <= prior * 0.8 -> TrackedIdea.Trend.DOWN
            else -> TrackedIdea.Trend.FLAT
        }
        val last = if (changed) now else idea.lastUpdateAt
        val quiet = (last ?: idea.startedAt).plus(config.quietDays.toLong(), ChronoUnit.DAYS) <= now
        return idea.copy(lastUpdateAt = last, strength = current, trend = trend,
            status = if (quiet) TrackedIdea.Status.QUIET else TrackedIdea.Status.TRACKING)
    }
}

class TrackingUpdater(private val prompts: PromptResolver, private val router: LlmRouter) {
    data class Result(val update: TrackingUpdate? = null, val failure: String? = null)

    suspend fun update(idea: TrackedIdea, card: IdeaCard?, signals: List<Signal>, previous: List<TrackingUpdate>,
                       today: String, previousItems: Map<String, Item> = emptyMap()): Result {
        val reported = previous.flatMap { it.itemKeys }.toSet()
        val related = signals.filter { (item, row) ->
            idea.id in row.trackedIdeaIds && (item.key !in reported || previousItems[item.key]?.let { prior ->
                val scoreIncreased = item.rawScore > prior.rawScore && item.rawScore * 2L >= prior.rawScore * 3L
                scoreIncreased || item.commentCount > prior.commentCount
            } == true)
        }
        if (related.isEmpty()) return Result()
        val prompt = prompts.resolve("track")
        val user = buildJsonObject {
            put("idea", buildJsonObject {
                put("title", idea.title)
                put("keywords", Json.encodeToJsonElement(idea.keywords))
                put("competitors", Json.encodeToJsonElement(idea.competitors))
                if (card != null) {
                    put("oneLiner", card.oneLiner)
                    put("problem", card.problem)
                    put("originalEvidence", Json.encodeToJsonElement(card.techSignals + card.demandSignals + card.competitors))
                }
            })
            put("items", JsonArray(related.map { (item, row, body, _, enrichedComments) ->
                buildJsonObject {
                    put("itemId", item.key)
                    put("source", item.source)
                    put("title", item.title)
                    put("snippet", body.ifBlank { item.snippet })
                    put("rawScore", item.rawScore)
                    put("commentCount", item.commentCount)
                    put("comments", Json.encodeToJsonElement(enrichedComments.ifEmpty { item.comments }))
                    put("profile", row.profile)
                }
            }))
            put("previous", JsonArray(previous.sortedByDescending { it.runDate }.take(3).map {
                buildJsonObject { put("runDate", it.runDate); put("summary", it.summary) }
            }))
        }
        val response = structuredCall<Output>(router, "track", listOf(prompt), prompt.text, user, outputSchema, 1024)
        if (response is Response.Failed) return Result(failure = response.reason)
        val output = (response as Response.Ok).value
        val keys = related.map { it.item.key }.toSet()
        val changes = output.changes.filter { it.itemKey in keys && it.kind in kinds && it.note.isNotBlank() }
        if (changes.isEmpty() || output.summary.isBlank()) return Result()
        return Result(TrackingUpdate("${idea.id}:$today", idea.id, today, output.summary, changes,
            changes.mapNotNull { it.itemKey }.distinct(), idea.strength, idea.trend))
    }

    @Serializable private data class Output(val summary: String, val changes: List<TrackingUpdate.Change>)
    private val kinds = setOf("NEW_COMPETITOR", "DEMAND_UP", "DEMAND_DOWN", "TECH_RELEASE", "RISK_CHANGE", "OTHER")
    private val outputSchema = schema("summary" to string, "changes" to array(schema(
        "kind" to string, "note" to string, "itemKey" to string
    )))
}

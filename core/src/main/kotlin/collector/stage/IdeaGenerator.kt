package collector.stage

import collector.config.PipelineConfig
import collector.llm.LlmRouter
import collector.model.*
import collector.prompt.PromptResolver
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

@Serializable data class Category(val id: String, val name: String)
@Serializable private data class Categories(val categories: List<Category>)

data class GeneratedCards(val cards: List<IdeaCard>, val failure: String? = null)

class IdeaGenerator(
    private val config: PipelineConfig, private val prompts: PromptResolver, private val router: LlmRouter
) {
    val categories: List<Category> = Json.decodeFromString<Categories>(
        checkNotNull(javaClass.getResource("/categories.json")).readText()
    ).categories

    suspend fun generate(today: String, signals: List<Signal>, history: Map<String, List<HistoryHit>>,
                         feedback: List<String>, recentCards: List<String>,
                         merged: Map<String, List<Signal>> = emptyMap(),
                         retryAfterTruncation: Boolean = false): GeneratedCards {
        val mergedEvidence = signals.associate { (item) ->
            item.key to merged[item.key].orEmpty().filter { it.item.url in item.mergedUrls }
        }
        val evidence = (signals + mergedEvidence.values.flatten()).distinctBy { it.item.key }
        if (evidence.size < 2 || evidence.none { it.classification.profile == "tech-signal" }) {
            return GeneratedCards(emptyList(), "기술 신호를 포함한 후보를 두 건 이상 확보하지 못했습니다.")
        }
        val ideas = prompts.resolve("ideas")
        val criteria = prompts.resolve("criteria")
        fun user(compact: Boolean) = buildJsonObject {
            put("today", today)
            put("ideaCount", config.ideaCount)
            put("maxCardsPerCategory", config.maxCardsPerCategory)
            put("categories", Json.encodeToJsonElement(categories))
            put("criteria", criteria.text)
            put("signals", JsonArray(signals.map { (item, row, body, hnContext, enrichedComments) -> buildJsonObject {
                put("itemId", item.key)
                put("profile", row.profile)
                put("title", item.title)
                put("source", item.source)
                put("score", JsonPrimitive(row.score))
                put("summary", row.summary)
                put("tags", Json.encodeToJsonElement(row.tags))
                put("url", item.url)
                put("body", if (compact) body.take(1000) else body)
                put("snippet", item.snippet.take(300))
                putHnContext(hnContext)
                val comments = enrichedComments.ifEmpty { item.comments }
                put("comments", Json.encodeToJsonElement(if (compact) comments.take(3) else comments))
                put("mergedUrls", Json.encodeToJsonElement(item.mergedUrls))
                item.extra["license"]?.let { put("license", it) }
                val members = mergedEvidence.getValue(item.key)
                if (members.isNotEmpty()) put("mergedSignals", JsonArray(members.map { (other, classification, _, otherContext) ->
                    buildJsonObject {
                        put("itemId", item.key)
                        put("url", other.url)
                        put("title", other.title)
                        put("source", other.source)
                        put("profile", classification.profile)
                        put("summary", classification.summary)
                        put("snippet", other.snippet.take(300))
                        putHnContext(otherContext)
                        other.extra["license"]?.let { put("license", it) }
                    }
                }))
            } }))
            put("history", Json.encodeToJsonElement(history))
            put("feedback", Json.encodeToJsonElement(feedback))
            put("recentCards", Json.encodeToJsonElement(recentCards))
        }
        val tokens = config.ideaCount * 1400 + 128
        suspend fun request(compact: Boolean, limit: Int) = structuredCall<Output>(router, "generate", listOf(ideas, criteria),
            ideas.text, user(compact), outputSchema, limit)
        val first = request(compact = false, limit = if (retryAfterTruncation) tokens * 3 / 2 else tokens)
        val response = if (first is Response.Failed && first.reason == "비용 상한") request(compact = true, limit = tokens) else first
        return when (response) {
            is Response.Ok -> GeneratedCards(response.value.cards.map {
                it.copy(id = UUID.randomUUID().toString(), runDate = today, badges = emptyList())
            }, if (response.value.cards.isEmpty()) "작성 모델이 근거 조건을 충족한 아이디어를 찾지 못했습니다." else null)
            is Response.Failed -> GeneratedCards(emptyList(), response.reason)
        }
    }

    @Serializable private data class Output(val cards: List<IdeaCard>)
    private val refs = array(schema("itemId" to string, "url" to string, "note" to string))
    private val outputSchema = schema("cards" to array(schema(
        "title" to string, "oneLiner" to string, "whyToday" to string, "problem" to string,
        "techSignals" to refs, "demandSignals" to refs, "competitors" to refs, "history" to strings,
        "effort" to string, "differentiation" to string, "biggestRisk" to string, "validationPlan" to string,
        "caveats" to strings, "category" to string, "tags" to strings
    )))
}

class CitationValidator(signals: List<Signal>, merged: Map<String, List<Signal>> = emptyMap()) {
    private val originalsByRef = buildMap {
        for (signal in signals) {
            val item = signal.item
            val members = merged[item.key].orEmpty()
            val membersByUrl = members.flatMap { member ->
                (listOf(member.item.url) + member.item.mergedUrls).map { it to member }
            }.toMap()
            put(item.key to item.url, signal)
            for (url in item.mergedUrls) put(item.key to url, membersByUrl[url] ?: signal)
        }
    }

    private fun original(ref: SignalRef): Signal? = originalsByRef[ref.itemId to ref.url]

    fun validate(card: IdeaCard): IdeaCard? {
        val used = mutableSetOf<String>()
        fun List<SignalRef>.valid(vararg profiles: String) = mapNotNull { ref ->
            val signal = original(ref) ?: return@mapNotNull null
            if (ref.note.isBlank() || signal.classification.profile !in profiles || !used.add(signal.item.key)) {
                return@mapNotNull null
            }
            ref.copy(license = if (signal.classification.profile == "tech-signal") signal.item.extra["license"] else null)
        }
        val tech = card.techSignals.valid("tech-signal")
        val demand = card.demandSignals.valid("demand-signal")
        val competitors = card.competitors.valid("market-signal", "tech-signal")
        if (tech.isEmpty() || demand.isEmpty() && competitors.isEmpty()) return null
        return card.copy(techSignals = tech, demandSignals = demand, competitors = competitors)
    }

    fun citedSignals(card: IdeaCard): List<Signal> =
        citedSignals(card.techSignals + card.demandSignals + card.competitors)

    internal fun citedSignals(refs: List<SignalRef>): List<Signal> =
        refs.mapNotNull(::original).distinctBy { it.item.key }
}

class CardValidator(private val config: PipelineConfig, private val categories: Set<String>) {
    private data class Evidence(val itemIds: Set<String>, val demandIds: Set<String>, val techTopics: Set<String>)

    fun validate(cards: List<IdeaCard>, citations: CitationValidator,
                 onDuplicate: (IdeaCard) -> Unit = {}): List<IdeaCard> {
        val counts = mutableMapOf<String, Int>()
        val acceptedEvidence = mutableListOf<Evidence>()
        return cards.mapNotNull { card ->
            if (card.category !in categories || card.tags.isEmpty() || card.tags.any(String::isBlank) ||
                listOf(card.title, card.oneLiner, card.whyToday, card.problem, card.effort,
                    card.differentiation, card.biggestRisk, card.validationPlan).any(String::isBlank)) return@mapNotNull null
            val validated = citations.validate(card) ?: return@mapNotNull null
            val evidence = Evidence(
                itemIds = citations.citedSignals(validated).map { it.item.key }.toSet(),
                demandIds = citations.citedSignals(validated.demandSignals).map { it.item.key }.toSet(),
                techTopics = citations.citedSignals(validated.techSignals).map { it.classification.topicKey }.toSet()
            )
            if (acceptedEvidence.any { prior ->
                evidence.itemIds == prior.itemIds ||
                    (evidence.demandIds.isNotEmpty() && evidence.demandIds == prior.demandIds &&
                        evidence.techTopics.any { it in prior.techTopics })
            }) {
                onDuplicate(card)
                return@mapNotNull null
            }
            val count = counts.getOrDefault(card.category, 0)
            if (count >= config.maxCardsPerCategory) return@mapNotNull null
            if (acceptedEvidence.size >= config.ideaCount) return@mapNotNull null
            counts[card.category] = count + 1
            acceptedEvidence += evidence
            validated
        }
    }
}

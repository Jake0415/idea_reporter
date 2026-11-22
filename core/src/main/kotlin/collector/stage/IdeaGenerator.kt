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
            return GeneratedCards(emptyList(), "쓸 수 있는 기술 항목을 포함한 카드 후보를 두 건 이상 확보하지 못했습니다.")
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

internal data class CitationCheck(
    val card: IdeaCard?,
    val detail: String?,
    val invalidByBlock: Map<String, Map<String, Int>>
)

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

    fun validate(card: IdeaCard): IdeaCard? = check(card).card

    internal fun check(card: IdeaCard): CitationCheck {
        val used = mutableSetOf<String>()
        fun List<SignalRef>.valid(invalid: MutableMap<String, Int>, vararg profiles: String) = mapNotNull { ref ->
            val signal = original(ref)
            val reason = when {
                signal == null -> "URL 불일치"
                ref.note.isBlank() -> "근거 링크 설명 비어 있음"
                signal.classification.profile !in profiles -> "근거 칸과 분류가 맞지 않음"
                !used.add(signal.item.key) -> "항목 중복 사용"
                else -> return@mapNotNull ref.copy(
                    license = if (signal.classification.profile == "tech-signal") signal.item.extra["license"] else null
                )
            }
            invalid[reason] = invalid.getOrDefault(reason, 0) + 1
            null
        }
        val invalidTech = mutableMapOf<String, Int>()
        val invalidDemand = mutableMapOf<String, Int>()
        val invalidCompetitors = mutableMapOf<String, Int>()
        val tech = card.techSignals.valid(invalidTech, "tech-signal")
        val demand = card.demandSignals.valid(invalidDemand, "demand-signal")
        val competitors = card.competitors.valid(invalidCompetitors, "market-signal", "tech-signal")
        val invalidByBlock = mapOf(
            "techSignals" to invalidTech,
            "demandSignals" to invalidDemand,
            "competitors" to invalidCompetitors
        )
        fun counts(vararg blocks: String) = blocks.flatMap { invalidByBlock.getValue(it).entries }
            .groupingBy { it.key }.fold(0) { count, entry -> count + entry.value }
        fun summary(invalid: Map<String, Int>) = invalid.entries.joinToString { (reason, count) -> "$reason $count" }
        fun missing(block: String, invalid: Map<String, Int>) = if (invalid.isEmpty()) "$block 근거 링크 없음"
            else "$block 근거 링크 없음(${invalid.values.sum()}건 무효: ${summary(invalid)})"
        val missingBlocks = listOfNotNull(
            if (tech.isEmpty()) missing("쓸 수 있는 기술", counts("techSignals")) else null,
            if (demand.isEmpty() && competitors.isEmpty()) missing("요청·불편 또는 이미 나온 제품", counts("demandSignals", "competitors")) else null
        )
        if (missingBlocks.isNotEmpty()) return CitationCheck(null, missingBlocks.joinToString(" · "), invalidByBlock)
        val invalid = counts("techSignals", "demandSignals", "competitors")
        return CitationCheck(
            card.copy(techSignals = tech, demandSignals = demand, competitors = competitors),
            if (invalid.isEmpty()) null else "근거 링크 ${invalid.values.sum()}건 제거: ${summary(invalid)}",
            invalidByBlock
        )
    }

    fun citedSignals(card: IdeaCard): List<Signal> =
        citedSignals(card.techSignals + card.demandSignals + card.competitors)

    internal fun citedSignals(refs: List<SignalRef>): List<Signal> =
        refs.mapNotNull(::original).distinctBy { it.item.key }
}

enum class CardRejectionReason {
    MISSING_FIELD, INVALID_CATEGORY, INVALID_TAGS, CITATION, DUPLICATE, CATEGORY_CAP, IDEA_COUNT_CAP
}

data class CardRejection(val reason: CardRejectionReason, val detail: String)

class CardValidator(private val config: PipelineConfig, private val categories: Set<String>) {
    private data class Evidence(val title: String, val itemIds: Set<String>, val demandIds: Set<String>, val techTopics: Set<String>)

    fun validate(cards: List<IdeaCard>, citations: CitationValidator,
                 onRejected: (IdeaCard, CardRejection) -> Unit = { _, _ -> },
                 onCitationsTrimmed: (IdeaCard, String) -> Unit = { _, _ -> }): List<IdeaCard> {
        val counts = mutableMapOf<String, Int>()
        val acceptedEvidence = mutableListOf<Evidence>()
        return cards.mapNotNull { card ->
            fun reject(reason: CardRejectionReason, detail: String): Nothing? {
                onRejected(card, CardRejection(reason, detail))
                return null
            }
            if (card.category !in categories) {
                return@mapNotNull reject(CardRejectionReason.INVALID_CATEGORY, "분류 id 오류: ${card.category}")
            }
            if (card.tags.isEmpty()) return@mapNotNull reject(CardRejectionReason.INVALID_TAGS, "태그 없음")
            if (card.tags.any(String::isBlank)) return@mapNotNull reject(CardRejectionReason.INVALID_TAGS, "빈 태그 포함")
            val missing = listOf(
                "title" to card.title, "oneLiner" to card.oneLiner, "whyToday" to card.whyToday,
                "problem" to card.problem, "effort" to card.effort, "differentiation" to card.differentiation,
                "biggestRisk" to card.biggestRisk, "validationPlan" to card.validationPlan
            ).filter { (_, value) -> value.isBlank() }.map { (field, _) -> field }
            if (missing.isNotEmpty()) {
                return@mapNotNull reject(CardRejectionReason.MISSING_FIELD, "필수 항목 비어 있음: ${missing.joinToString()}")
            }
            val citationCheck = citations.check(card)
            val validated = citationCheck.card
                ?: return@mapNotNull reject(CardRejectionReason.CITATION, checkNotNull(citationCheck.detail))
            val evidence = Evidence(
                title = card.title,
                itemIds = citations.citedSignals(validated).map { it.item.key }.toSet(),
                demandIds = citations.citedSignals(validated.demandSignals).map { it.item.key }.toSet(),
                techTopics = citations.citedSignals(validated.techSignals).map { it.classification.topicKey }.toSet()
            )
            val duplicate = acceptedEvidence.firstOrNull { prior ->
                evidence.itemIds == prior.itemIds ||
                    (evidence.demandIds.isNotEmpty() && evidence.demandIds == prior.demandIds &&
                        evidence.techTopics.any { it in prior.techTopics })
            }
            if (duplicate != null) {
                return@mapNotNull reject(CardRejectionReason.DUPLICATE, "중복 카드: ${duplicate.title}")
            }
            val count = counts.getOrDefault(card.category, 0)
            if (count >= config.maxCardsPerCategory) {
                return@mapNotNull reject(CardRejectionReason.CATEGORY_CAP,
                    "같은 분야 카드 수 초과: ${card.category} (최대 ${config.maxCardsPerCategory})")
            }
            if (acceptedEvidence.size >= config.ideaCount) {
                return@mapNotNull reject(CardRejectionReason.IDEA_COUNT_CAP, "카드 수 상한 초과 (최대 ${config.ideaCount})")
            }
            counts[card.category] = count + 1
            acceptedEvidence += evidence
            citationCheck.detail?.let { onCitationsTrimmed(card, it) }
            validated
        }
    }
}

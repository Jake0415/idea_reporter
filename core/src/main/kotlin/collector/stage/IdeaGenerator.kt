package collector.stage

import collector.config.PipelineConfig
import collector.llm.*
import collector.refine.UrlNormalizer
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import collector.model.*
import collector.prompt.PromptResolver
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID

@Serializable data class Category(val id: String, val name: String)
@Serializable private data class Categories(val categories: List<Category>)

val ideaCategories: List<Category> by lazy {
    Json.decodeFromString<Categories>(
        checkNotNull(Category::class.java.getResourceAsStream("/categories.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    ).categories
}

data class GeneratedCards(val cards: List<IdeaCard>, val failure: String? = null, val issues: List<String> = emptyList(),
                          val reusedPaidBatch: Boolean = false, val requiresExpansionFields: Boolean = true)

internal fun verifiedMergedEvidence(signals: List<Signal>, merged: Map<String, List<Signal>>) =
    signals.associate { (item) ->
        item.key to merged[item.key].orEmpty().filter { it.item.url in item.mergedUrls }
    }

internal fun hasGenerationEvidence(signals: List<Signal>): Boolean {
    val evidence = signals.filter { it.classification.profile in setOf("tech-signal", "demand-signal", "market-signal") }
    return evidence.distinctBy { it.item.key }.size >= 2 && evidence.any { it.classification.profile == "tech-signal" }
}

class IdeaGenerator(
    private val config: PipelineConfig, private val prompts: PromptResolver, private val router: LlmRouter
) {
    private fun verified(refs: List<SupplementRef>) = refs.filter { it.url.length <= supplementUrlChars }.distinctBy { UrlNormalizer.normalize(it.url) }
        .take(supplementCount).associate {
            UrlNormalizer.normalize(it.url) to it.copy(title = it.title.take(supplementTitleChars), note = it.note.take(supplementNoteChars))
        }

    val categories: List<Category> get() = ideaCategories

    suspend fun generate(today: String, signals: List<Signal>, history: Map<String, List<HistoryHit>>,
                         feedback: List<String>, recentCards: List<String>,
                         merged: Map<String, List<Signal>> = emptyMap(),
                         retryAfterTruncation: Boolean = false, out: File? = null, batchAllowed: Boolean = true,
                         snapshotBatchInputs: Boolean = false,
                         quoteOnly: ((LlmRequest) -> Unit)? = null,
                         singleAttempt: Boolean = false,
                         researchedRefs: List<SupplementRef>? = null,
                         expansionSeeds: List<ExpansionSeed> = emptyList()): GeneratedCards {
        val mergedEvidence = verifiedMergedEvidence(signals, merged)
        val resumingBatch = batchAllowed && out != null && hasSavedBatch(out, today, "generate")
        val sufficient = hasGenerationEvidence(signals + mergedEvidence.values.flatten())
        val insufficient = "카드 작성에 필요한 기술과 요청·불편 또는 이미 나온 제품 근거가 부족합니다."
        if (quoteOnly == null && !resumingBatch && !sufficient) return GeneratedCards(emptyList(), insufficient)
        val ideas = prompts.resolve("ideas")
        val criteria = prompts.resolve("criteria")
        val issues = mutableListOf<String>()
        val savedInput = if (resumingBatch) savedGenerationInput(out, today, snapshotBatchInputs) else null
        var reusedPaidBatch = false
        var requiresExpansionFields = true
        var verifiedRefs = verified(researchedRefs.orEmpty())
        fun user(compact: Boolean, includeSupplement: Boolean = true) = buildJsonObject {
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
                        if (expansionSeeds.isNotEmpty()) put("sourceItemId", other.key)
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
            put("카드 필드 지시", "targetCustomer는 대상 고객, coreTech는 핵심 기술을 짧은 명사구로, pain은 핵심 불편을 한 문장으로 쓴다. " +
                "매일 카드는 kind=daily, lens와 seedId는 null, speculation은 빈 배열로 쓴다.")
            if (expansionSeeds.isNotEmpty()) {
                put("확장 카드 지시", "expansionSeeds에서 씨앗 하나를 골라 ideaCount 안에서 cardsPerDay장까지 쓴다. " +
                    "kind=expanded, lens와 seedId는 씨앗의 값, targetCustomer는 씨앗의 고객을 쓴다. " +
                    "씨앗의 기술을 포함해 씨앗 자료만 인용한다. 병합 자료는 씨앗 itemIds와 sourceItemId를 비교하고, 인용에는 itemId와 URL을 쓴다. " +
                    "근거가 없는 추정은 speculation에 하나 이상 적고, " +
                    "whyToday에 출발 신호와 넓히는 방법을 적는다. 적합한 씨앗이 없으면 매일 카드로 채운다.")
                put("expansionSeeds", Json.encodeToJsonElement(expansionSeeds))
                put("cardsPerDay", config.expansion.cardsPerDay.coerceAtMost(config.ideaCount))
            }
            if (includeSupplement && verifiedRefs.isNotEmpty()) {
                put("보충 자료", Json.encodeToJsonElement(verifiedRefs.values.toList()))
                put("보충 자료 지시", "supplementRefs에 보충 자료 URL, 제목, 관련 설명을 최대 3개 넣는다. " +
                    "해당 자료가 없으면 빈 배열을 쓴다. 쓸 수 있는 기술과 사람들의 요청·불편 또는 이미 나온 제품의 필수 근거는 수집 항목을 인용한다.")
            }
        }
        val tokens = config.ideaCount * 1700 + 128
        if (quoteOnly != null) {
            quoteOnly(LlmRequest("", ideas.text, user(compact = false).toString(),
                outputSchema(config.supplementSearch.enabled), "generate", tokens, Effort.LOW))
            return GeneratedCards(emptyList())
        }
        if (researchedRefs == null && !resumingBatch && config.supplementSearch.enabled && config.supplementSearch.maxUses > 0) {
            try {
                val topics = signals.take(config.ideaCount * 2)
                    .map { it.classification.topicLabel.ifBlank { it.item.title } }.distinct()
                val standardRequest = LlmRequest("", ideas.text, user(compact = false).toString(), outputSchema(true),
                    "generate", tokens, Effort.LOW)
                verifiedRefs = verified(router.supplementSearch(Json.encodeToString(topics), config.supplementSearch, ideas.version,
                    standardRequest))
            } catch (e: CancellationException) {
                throw e
            } catch (_: BudgetExceededException) {
                if (singleAttempt) return GeneratedCards(emptyList(), BUDGET_LIMIT)
                issues += "예산 부족 · 보충 검색 생략"
            } catch (_: Exception) {
                if (singleAttempt) return GeneratedCards(emptyList(), "보충 검색 실패")
                issues += "보충 검색 실패 · 검색 없이 작성"
            }
        }
        suspend fun request(compact: Boolean, limit: Int, useBatch: Boolean = false): Response<Output> {
            val request = LlmRequest("", ideas.text, user(compact).toString(),
                outputSchema(verifiedRefs.isNotEmpty()), "generate", limit, Effort.LOW)
            val batch = if (useBatch && out != null && (config.batch.generate || resumingBatch)) {
                router.batch("generate", today, out, config.batch, listOf(request),
                    mapOf(ideas.promptId to ideas.version, criteria.promptId to criteria.version),
                    GenerationBatchResume(savedInput ?: request.copy(user = user(compact, includeSupplement = false).toString(),
                        jsonSchema = outputSchema(false)), verifiedRefs.values.toList(), snapshotBatchInputs,
                        GENERATION_SCHEMA_VERSION))
            } else null
            issues += batch?.issues.orEmpty()
            if (batch?.pending == true) return Response.Failed("Batch 결과 대기")
            if (resumingBatch && batch != null) verifiedRefs = verified(batch.supplementRefs)
            reusedPaidBatch = resumingBatch && batch?.responses?.get(0)?.isSuccess == true
            requiresExpansionFields = !reusedPaidBatch || batch?.generationSchemaVersion == GENERATION_SCHEMA_VERSION
            if (!reusedPaidBatch && !sufficient) return Response.Failed(insufficient)
            val current = request.copy(user = user(compact).toString(), jsonSchema = outputSchema(verifiedRefs.isNotEmpty()))
            return structuredCall(router, "generate", listOf(ideas, criteria), current,
                initial = batch?.responses?.get(0), allowFallback = !singleAttempt,
                retryInvalid = !requiresExpansionFields && sufficient && !singleAttempt,
                onAttempt = { requiresExpansionFields = true })
        }
        val first = request(compact = false, limit = if (retryAfterTruncation) tokens * 3 / 2 else tokens, useBatch = batchAllowed)
        val response = if (!singleAttempt && first is Response.Failed && first.reason == BUDGET_LIMIT && !reusedPaidBatch)
            request(compact = true, limit = tokens) else first
        return when (response) {
            is Response.Ok -> GeneratedCards(response.value.cards.map { card ->
                val refs = card.supplementRefs.mapNotNull { ref ->
                    val url = ref.url.toHttpUrlOrNull() ?: return@mapNotNull null
                    if (ref.note.isBlank() || url.username.isNotEmpty() || url.password.isNotEmpty()) return@mapNotNull null
                    verifiedRefs[UrlNormalizer.normalize(ref.url)]?.copy(note = ref.note)
                }.distinctBy { UrlNormalizer.normalize(it.url) }.take(3)
                val removed = card.supplementRefs.size - refs.size
                if (removed > 0) issues += "보충 근거 링크 ${removed}건 제거"
                card.copy(id = UUID.randomUUID().toString(), runDate = today, badges = emptyList(), supplementRefs = refs)
            }, if (response.value.cards.isEmpty()) "작성 모델이 근거 조건을 충족한 아이디어를 찾지 못했습니다." else null, issues, reusedPaidBatch, requiresExpansionFields)
            is Response.Failed -> GeneratedCards(emptyList(), response.reason, issues, reusedPaidBatch)
        }
    }

    @Serializable private data class Output(val cards: List<IdeaCard>)
    private val nullableString = buildJsonObject { put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null")))) }
    private val refs = array(schema("itemId" to string, "url" to string, "note" to string))
    private fun outputSchema(withSearch: Boolean) = schema("cards" to array(schema(*(
        listOf(
            "title" to string, "oneLiner" to string, "whyToday" to string, "problem" to string,
            "techSignals" to refs, "demandSignals" to refs, "competitors" to refs, "history" to strings,
            "effort" to string, "differentiation" to string, "biggestRisk" to string, "validationPlan" to string,
            "caveats" to strings, "category" to string, "tags" to strings,
            "targetCustomer" to string, "coreTech" to string, "pain" to string,
            "kind" to buildJsonObject {
                put("type", "string")
                put("enum", JsonArray(listOf(JsonPrimitive("daily"), JsonPrimitive("expanded"))))
            }, "lens" to nullableString, "seedId" to nullableString, "speculation" to strings
        ) + if (withSearch) listOf("supplementRefs" to array(schema("url" to string, "title" to string, "note" to string)))
        else emptyList()
    ).toTypedArray())))
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

    internal fun belongsTo(ref: SignalRef, itemIds: List<String>) = original(ref)?.item?.key?.let { it in itemIds } == true

    fun validate(card: IdeaCard): IdeaCard? = check(card).card

    internal fun check(card: IdeaCard, requireDemand: Boolean = true): CitationCheck {
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
            if (requireDemand && demand.isEmpty() && competitors.isEmpty()) missing("요청·불편 또는 이미 나온 제품", counts("demandSignals", "competitors")) else null
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
    MISSING_FIELD, INVALID_CATEGORY, INVALID_TAGS, CITATION, DUPLICATE, REPEAT, SEED_MISMATCH, EXPANSION_CAP, CATEGORY_CAP, IDEA_COUNT_CAP
}

data class CardRejection(val reason: CardRejectionReason, val detail: String)

class CardValidator(private val config: PipelineConfig, private val categories: Set<String>) {
    private data class Evidence(val card: IdeaCard, val itemIds: Set<String>, val demandIds: Set<String>, val techTopics: Set<String>)

    fun validate(cards: List<IdeaCard>, citations: CitationValidator,
                 recent: List<RecentCard> = emptyList(), seeds: List<ExpansionSeed> = emptyList(),
                 requiresExpansionFields: Boolean = true, onRepeatWarning: ((IdeaCard) -> Unit)? = null,
                 onRejected: (IdeaCard, CardRejection) -> Unit = { _, _ -> },
                 onCitationsTrimmed: (IdeaCard, String) -> Unit = { _, _ -> }): List<IdeaCard> {
        val counts = mutableMapOf<String, Int>()
        val acceptedEvidence = mutableListOf<Evidence>()
        val seedsById = seeds.associateBy { it.id }
        var expansions = 0
        return cards.map { card ->
            if (requiresExpansionFields && card.kind == "expanded" && !card.seedId.isNullOrBlank()) card
            else card.copy(kind = "daily", lens = null, seedId = null, speculation = emptyList())
        }.sortedBy { it.kind == "expanded" }.mapNotNull { card ->
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
            ).plus(if (requiresExpansionFields) listOf(
                "targetCustomer" to card.targetCustomer, "coreTech" to card.coreTech, "pain" to card.pain
            ) else emptyList()).filter { (_, value) -> value.isBlank() }.map { (field, _) -> field }
            if (missing.isNotEmpty()) {
                return@mapNotNull reject(CardRejectionReason.MISSING_FIELD, "필수 항목 비어 있음: ${missing.joinToString()}")
            }
            val expanded = card.kind == "expanded"
            val seed = if (expanded) seedsById[card.seedId] else null
            if (expanded && (seed == null || seed.lens != card.lens || !config.expansion.enabled ||
                    config.expansion.lenses[seed.lens] != true || card.speculation.isEmpty() || card.speculation.any(String::isBlank))) {
                return@mapNotNull reject(CardRejectionReason.MISSING_FIELD, "확장 카드의 씨앗·렌즈 또는 AI 제안 확인 필요")
            }
            if (seed != null) {
                val customer = cardKey(card.targetCustomer)
                val seedCustomer = cardKey(seed.targetCustomer)
                if (customer.isEmpty() || seedCustomer.isEmpty() || (customer !in seedCustomer && seedCustomer !in customer)) {
                    return@mapNotNull reject(CardRejectionReason.SEED_MISMATCH, "확장 씨앗과 대상 고객 불일치")
                }
            }
            if (seed != null && (card.techSignals + card.demandSignals + card.competitors).any { !citations.belongsTo(it, seed.itemIds) }) {
                return@mapNotNull reject(CardRejectionReason.CITATION, "확장 씨앗에 없는 근거 링크")
            }
            val citationCheck = citations.check(card, requireDemand = !expanded)
            val citedCard = citationCheck.card
                ?: return@mapNotNull reject(CardRejectionReason.CITATION, checkNotNull(citationCheck.detail))
            val validated = if (expanded) citedCard.copy(supplementRefs = emptyList()) else citedCard
            val evidence = Evidence(
                card = validated,
                itemIds = citations.citedSignals(validated).map { it.item.key }.toSet(),
                demandIds = citations.citedSignals(validated.demandSignals).map { it.item.key }.toSet(),
                techTopics = citations.citedSignals(validated.techSignals).map { it.classification.topicKey }.toSet()
            )
            if (seed != null && validated.techSignals.none { citations.belongsTo(it, seed.itemIds.take(1)) }) {
                return@mapNotNull reject(CardRejectionReason.CITATION, "확장 씨앗의 기술 근거 링크 없음")
            }
            val repeat = recent.firstOrNull { isRepeatedCard(validated, evidence.techTopics, it) }
            if (repeat != null && onRepeatWarning == null) {
                return@mapNotNull reject(CardRejectionReason.REPEAT,
                    "최근 카드와 같음: ${repeat.card.title} (${repeat.card.runDate})")
            }
            if (expanded && expansions >= config.expansion.cardsPerDay) {
                return@mapNotNull reject(CardRejectionReason.EXPANSION_CAP,
                    "확장 카드 수 상한 초과 (최대 ${config.expansion.cardsPerDay})")
            }
            val duplicate = acceptedEvidence.firstOrNull { prior ->
                evidence.itemIds == prior.itemIds ||
                    (evidence.demandIds.isNotEmpty() && evidence.demandIds == prior.demandIds &&
                        evidence.techTopics.any { it in prior.techTopics }) ||
                    (expanded && prior.card.kind == "daily" &&
                        isRepeatedCard(validated, evidence.techTopics, RecentCard(prior.card, prior.techTopics)))
            }
            if (duplicate != null) {
                return@mapNotNull reject(CardRejectionReason.DUPLICATE, "중복 카드: ${duplicate.card.title}")
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
            if (expanded) expansions++
            val caveats = buildList {
                addAll(validated.caveats)
                if (repeat != null && onRepeatWarning != null) {
                    onRepeatWarning(card)
                    add("$RECENT_CARD_WARNING${repeat.card.title} (${repeat.card.runDate})")
                }
                if (expanded && validated.demandSignals.isEmpty() && validated.competitors.isEmpty()) {
                    remove("직접 수요 신호 없음 — AI 추정")
                    add("사람들의 요청·불편 자료 없음 — AI 추정")
                }
            }.distinct()
            validated.copy(caveats = caveats)
        }
    }
}

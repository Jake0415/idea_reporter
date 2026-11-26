package collector.stage

import collector.config.PipelineConfig
import collector.dev.FileHistorySearch
import collector.dev.publishRun
import collector.pipeline.PipelineResult
import collector.pipeline.FullPipeline
import collector.dev.JsonFileTrackedStore
import collector.llm.*
import collector.profile.ProfileLoader
import collector.prompt.FilePromptStore
import collector.prompt.PromptResolver
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import collector.report.HtmlRenderer
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.io.TempDir
import java.io.File
import collector.model.*
import java.time.Instant
import java.time.LocalDate
import kotlin.test.*

class ExpansionTest {
    @TempDir lateinit var out: File
    private val day = LocalDate.parse("2026-11-26")
    private val config = PipelineConfig.defaults
    private val tech = signal("tech", "tech-signal", "speech")
    private val demand = signal("demand", "demand-signal", "notes")
    private val outside = signal("outside", "market-signal", "other")

    private fun signal(id: String, profile: String, topic: String): Signal {
        val item = Item("test", id, id, "https://example.test/$id", 10, publishedAt = Instant.EPOCH)
        return Signal(item, ClassifiedItem(item.key, profile, 9.0, "", "", emptyList(), topic, topic))
    }

    private fun ref(signal: Signal) = SignalRef(signal.item.key, signal.item.url, "근거")

    private fun daily() = IdeaCard(runDate = day.toString(), title = "회의 정리 도구", oneLiner = "요약",
        whyToday = "새 도구", problem = "기록", techSignals = listOf(ref(tech)), demandSignals = listOf(ref(demand)),
        competitors = emptyList(), history = emptyList(), effort = "2주", differentiation = "정리", biggestRisk = "정확도",
        validationPlan = "검토", caveats = emptyList(), category = "dev-tools", tags = listOf("speech"),
        targetCustomer = "회계 사무소", coreTech = "음성 인식", pain = "회의 기록 누락")

    @Test fun recentCardsMatchByCustomerPainTitleOrLegacyTechnology() {
        val current = daily()
        val historical = current.copy(title = "장부 감사", pain = "장부 확인")
        val topic = setOf("speech")
        assertTrue(isRepeatedCard(current, topic, RecentCard(historical.copy(targetCustomer = "회계-사무소"), topic)))
        assertTrue(isRepeatedCard(current, topic, RecentCard(historical.copy(targetCustomer = "교육팀", pain = "회의 누락"), topic)))
        assertTrue(isRepeatedCard(current, topic, RecentCard(historical.copy(title = "회의 정리 도구 개선"), setOf("other"))))
        val legacy = historical.copy(targetCustomer = "", coreTech = "", pain = "")
        assertFalse(isRepeatedCard(current, topic, RecentCard(legacy, topic)))
        assertTrue(isRepeatedCard(current, topic, RecentCard(legacy.copy(title = "회의 정리 제품"), topic)))
        assertFalse(isRepeatedCard(current, topic, RecentCard(historical.copy(coreTech = "문서 검색 AI"), topic)))
        assertFalse(isRepeatedCard(current, topic, RecentCard(historical.copy(targetCustomer = "교육팀"), topic)))
    }

    @Test fun repeatIsRecordedWithTitleAndDate() {
        val prior = daily().copy(runDate = "2026-11-12")
        val rejections = mutableListOf<CardRejection>()
        val cards = CardValidator(config, setOf("dev-tools")).validate(listOf(daily()), CitationValidator(listOf(tech, demand)),
            recent = listOf(RecentCard(prior, setOf("speech"))), onRejected = { _, reason -> rejections += reason })
        assertTrue(cards.isEmpty())
        assertEquals(CardRejection(CardRejectionReason.REPEAT, "최근 카드와 같음: 회의 정리 도구 (2026-11-12)"), rejections.single())
    }

    @Test fun sparseEvidenceSkipsGenerationAndSupplementCalls() = runTest {
        var calls = 0
        val provider = object : LlmProvider {
            override val id = ProviderId.ANTHROPIC
            override suspend fun listModels() = emptyList<ModelInfo>()
            override suspend fun complete(req: LlmRequest): LlmResult {
                calls++
                return LlmResult("""{"cards":[]}""", id, req.model, 0, 0, 0, 0.0)
            }
        }
        val generator = IdeaGenerator(config, PromptResolver(FilePromptStore(out.toPath())),
            LlmRouter(listOf(provider), CostGuard(1.0)))
        val seeds = makeExpansionSeeds(day, config.expansion, listOf(tech), "", emptyList(), emptyMap())
        assertTrue(seeds.isNotEmpty())
        for (evidence in listOf(listOf(tech), listOf(demand, outside))) {
            val result = generator.generate(day.toString(), evidence, emptyMap(), emptyList(), emptyList(),
                expansionSeeds = seeds)
            assertTrue(result.cards.isEmpty())
        }
        assertEquals(0, calls)
        generator.generate(day.toString(), listOf(tech, signal("tech2", "tech-signal", "vision")),
            emptyMap(), emptyList(), emptyList())
        assertEquals(1, calls)
    }

    @Test fun seedsUseMyFieldsAndRotateEnabledLensesWithHistoryAndFeedback() {
        val settings = config.expansion.copy(myFields = listOf("회계"), lenses = mapOf("invert" to true, "korea" to true))
        fun seeds(history: List<UsedExpansionSeed> = emptyList(), weights: Map<String, Int> = emptyMap()) =
            makeExpansionSeeds(day, settings, listOf(tech, demand), "• **대상 고객:** 교육", history, weights)
        val first = seeds()
        assertEquals(setOf("회계"), first.map { it.targetCustomer }.toSet())
        assertEquals(1, first.size)
        val used = first.single().usedOn(day.minusDays(30))
        val other = if (used.lens == "korea") "invert" else "korea"
        assertEquals(other, seeds(listOf(used)).single().lens)
        assertEquals(first, seeds(listOf(used.copy(date = "2026-10-32"))))
        assertEquals("invert", seeds(weights = mapOf("korea" to -20, "invert" to 1)).single().lens)
        assertNotEquals(first.single().lens, makeExpansionSeeds(day.plusDays(1), settings,
            listOf(tech, demand), "", emptyList(), emptyMap()).single().lens)
        assertTrue(makeExpansionSeeds(day, settings.copy(lenses = emptyMap()), listOf(tech), "", emptyList(), emptyMap()).isEmpty())
    }

    @Test fun dailyAndRequestedHistoryIncludesDayFourteen() {
        val previous = day.minusDays(14)
        val old = day.minusDays(15)
        val dailyRun = run(day.minusDays(1), listOf(daily()))
        val historical = run(previous, listOf(daily().copy(runDate = "")))
        out.resolve("$previous.json").writeText(Json.encodeToString(historical))
        out.resolve("requested").mkdir()
        out.resolve("requested/$old-requested.json").writeText(Json.encodeToString(historical))
        out.resolve("requested/$previous-older.json").writeText(Json.encodeToString(run(old, listOf(daily()))))
        out.resolve("requested/$day-today.json").writeText(Json.encodeToString(dailyRun))
        val history = FileHistorySearch(out, config.historyDays)
        val daily = history.runs(day, config.recentCardDays).single().recentCards().single()
        assertEquals(previous.toString(), daily.card.runDate)
        assertEquals(setOf("speech"), daily.techTopics)
        assertEquals(listOf(dailyRun, historical), history.requestedRuns(day, config.recentCardDays))
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun dailyPipelineRetriesOnlyIfSomeRejectionIsNotARepeat(mixed: Boolean) = runTest {
        val previous = day.minusDays(14)
        val historical = daily().copy(id = "prior", runDate = previous.toString(), title = "회의 내용을 담는 앱")
        out.resolve("requested").mkdir()
        out.resolve("requested/$previous-requested.json").writeText(Json.encodeToString(run(previous, listOf(historical))))
        val calls = mutableListOf<LlmRequest>()
        var generations = 0
        val provider = object : LlmProvider {
            override val id = ProviderId.ANTHROPIC
            override suspend fun listModels() = emptyList<ModelInfo>()
            override suspend fun complete(req: LlmRequest): LlmResult {
                calls += req
                val response = when (req.schemaName) {
                    "classify" -> buildJsonObject { put("items", Json.encodeToJsonElement(listOf(tech.classification, demand.classification))) }
                    "generate" -> {
                        generations++
                        val cards = when {
                            generations > 1 -> listOf(daily().copy(title = "교육용 발음 퀴즈", targetCustomer = "교사",
                                pain = "시험지 제작"))
                            mixed -> listOf(daily(), daily().copy(title = "분류 오류", category = "invalid"))
                            else -> listOf(daily())
                        }
                        buildJsonObject { put("cards", Json.encodeToJsonElement(cards)) }
                    }
                    else -> error("Unexpected stage")
                }
                return LlmResult(response.toString(), id, req.model, 0, 0, 0, 0.0)
            }
        }
        val guard = CostGuard(1.0)
        val settings = config.copy(supplementSearch = config.supplementSearch.copy(enabled = false))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(404).message("Fixture").build()
        }.build()
        val promptStore = FilePromptStore(out.toPath()).apply { save("ideas", "내가 고친 카드 작성 규칙") }
        val pipeline = FullPipeline(settings, ProfileLoader.loadDefaults(), PromptResolver(promptStore),
            LlmRouter(listOf(provider), guard), guard, client, FileHistorySearch(out, settings.historyDays),
            JsonFileTrackedStore(out.resolve("tracked.json")), out = out)
        val base = run(day, emptyList())
        val result = pipeline.run(base, base.items, emptyList(), emptyList(), Instant.parse("${day}T00:00:00Z"), day) {}
        assertEquals(if (mixed) 1 else 0, result.cards.size)
        assertEquals(if (mixed) emptyMap() else mapOf("REPEAT" to 1), result.metrics.cardRejections)
        assertEquals(if (mixed) 2 else 1, generations)
        assertTrue(result.report.issues.any { "최근 카드와 같음: ${historical.title} ($previous)" in it })
        val request = calls.first { it.schemaName == "generate" }
        assertEquals("내가 고친 카드 작성 규칙", request.system)
        val user = Json.parseToJsonElement(request.user).jsonObject
        assertEquals("${historical.title} · 회계 사무소 · 음성 인식 · 회의 기록 누락",
            user.getValue("recentCards").jsonArray.single().jsonPrimitive.content)
        assertEquals(3, user.getValue("expansionSeeds").jsonArray.size)
        assertContains(user.getValue("카드 필드 지시").jsonPrimitive.content, "targetCustomer")
        assertContains(user.getValue("확장 카드 지시").jsonPrimitive.content, "speculation")
        val kind = request.jsonSchema.getValue("properties").jsonObject.getValue("cards").jsonObject
            .getValue("items").jsonObject.getValue("properties").jsonObject.getValue("kind").jsonObject
        assertEquals(listOf("daily", "expanded"), kind["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(result.usedExpansionSeeds.isEmpty())
    }

    @Test fun publishedExpansionKeepsOnlyUsedSeedsAndLabelsBothMailFormats() {
        val seeds = makeExpansionSeeds(day, config.expansion, listOf(tech), "", emptyList(), emptyMap())
        val used = seeds.last()
        val card = daily().copy(id = "expanded", title = "새 고객 카드", kind = "expanded",
            lens = used.lens, seedId = used.id, speculation = listOf("추정 <설명>"))
        val dailyCard = daily().copy(id = "ordinary")
        val result = run(day, listOf(card, dailyCard)).copy(usedExpansionSeeds = listOf(used.usedOn(day)))
        out.resolve("expansion-seeds.json").writeText(Json.encodeToString(listOf(
            used.usedOn(day.minusDays(31)), used.usedOn(day).copy(date = "invalid-date"))))
        val renderer = HtmlRenderer(listOf(Category("dev-tools", "개발 도구")))
        val rendered = assertNotNull(publishRun(out, FileHistorySearch(out, config.historyDays), renderer, result))
        assertEquals(listOf(used.usedOn(day)), Json.decodeFromString<List<UsedExpansionSeed>>(
            out.resolve("expansion-seeds.json").readText()))
        for (body in listOf(rendered.html, rendered.text)) {
            val toc = body.substringBefore("넓혀 본 아이디어")
            assertContains(toc, "AI가 넓힘 · ${used.lensName}")
            assertContains(toc, dailyCard.title)
            val section = body.substringAfter("넓혀 본 아이디어")
            assertContains(section, "오늘 자료에서 출발해 AI가 다른 고객·시장으로 넓혀 본 아이디어입니다. 추정이 섞여 있습니다.")
            assertTrue(section.indexOf(ref(tech).url) in 0 until section.indexOf("AI 제안(추정)"))
            assertContains(section, "출처로 확인되지 않은, AI가 넓혀 본 추정입니다.")
        }
        val tocHtml = rendered.html.substringAfter(card.title).substringBefore("</li>")
        assertTrue(tocHtml.indexOf("개발 도구") in 0 until tocHtml.indexOf("AI가 넓힘"))
        assertContains(tocHtml, "background:transparent")
        assertContains(rendered.html, "추정 &lt;설명&gt;")
        assertContains(rendered.text, "추정 <설명>")
    }

    @Test fun dailyCardsWinAndUnknownKindsUseDailyRules() {
        val seed = makeExpansionSeeds(day, config.expansion, listOf(tech, demand), "- 대상 고객: 회계 사무소",
            emptyList(), emptyMap()).single()
        assertEquals("회계 사무소", seed.targetCustomer)
        val ordinary = daily().copy(kind = "unexpected")
        val expanded = daily().copy(title = "현장 장부", kind = "expanded", seedId = seed.id,
            lens = seed.lens, speculation = listOf("추정"))
        val reasons = mutableListOf<CardRejectionReason>()
        val cards = CardValidator(config.copy(ideaCount = 1), setOf("dev-tools"))
            .validate(listOf(expanded, ordinary), CitationValidator(listOf(tech, demand)), seeds = listOf(seed),
                onRejected = { _, rejection -> reasons += rejection.reason })
        assertEquals(listOf(daily()), cards)
        assertEquals(listOf(CardRejectionReason.DUPLICATE), reasons)
    }

    @Test fun seedsRotatePairsAndExcludeRecentCustomersForTheSameTechnology() {
        val second = signal("vision", "tech-signal", "vision")
        val settings = config.expansion.copy(myFields = listOf("회계 사무소", "초등 교육"))
        val seeds = makeExpansionSeeds(day, settings, listOf(tech, second, demand), "", emptyList(), emptyMap(),
            recentCards = listOf(RecentCard(daily(), setOf("speech"))))
        assertEquals(listOf("vision" to "초등 교육", "speech" to "초등 교육", "vision" to "회계 사무소"),
            seeds.map { it.techTopic to it.targetCustomer })
    }

    @Test fun settingsRejectInvalidBoundsAndUseOnlyTheFirstFiveShortFields() {
        fun load(config: String) = PipelineConfig.parse("""{"expansion":{$config}}""").expansion
        for (invalid in listOf("\"cardsPerDay\":-1", "\"cardsPerDay\":3", "\"seedCount\":-1",
            "\"seedCount\":7", "\"seedHistoryDays\":-1")) {
            assertFailsWith<IllegalArgumentException> { load(invalid) }
        }
        val fields = List(6) { "가".repeat(31) + it }
        assertEquals(List(5) { "가".repeat(30) }, load("\"myFields\":${Json.encodeToString(fields)}").myFields)
    }

    private fun run(date: LocalDate, cards: List<IdeaCard>) = PipelineResult(
        listOf(tech.item, demand.item), emptyList(),
        RunReport(date.toString(), "${date}T00:00:00Z", emptyList(), emptyList(), emptyMap(), 0),
        classified = listOf(tech, demand), cards = cards
    )

    @Test fun expansionEnforcesSeedCitationsLensSpeculationAndCap() {
        val seeds = makeExpansionSeeds(day, config.expansion, listOf(tech), "", emptyList(), emptyMap())
        val seed = seeds.first()
        val expanded = daily().copy(kind = "expanded", lens = seed.lens, seedId = seed.id,
            demandSignals = emptyList(), targetCustomer = seed.targetCustomer + " 담당자",
            supplementRefs = listOf(SupplementRef("https://example.test/search", "검색", "보충")),
            speculation = listOf("새 고객에게도 필요할 수 있음"))
        val rejected = mutableListOf<CardRejectionReason>()
        val result = CardValidator(config, setOf("dev-tools")).validate(listOf(
            expanded.copy(competitors = listOf(ref(outside))), expanded.copy(lens = "invalid"),
            expanded.copy(speculation = listOf(" ")), expanded.copy(targetCustomer = "관련 없는 고객"),
            expanded, expanded.copy(title = "초과 카드")
        ), CitationValidator(listOf(tech, outside)), seeds = seeds, onRejected = { _, reason -> rejected += reason.reason })
        assertEquals(listOf(CardRejectionReason.CITATION, CardRejectionReason.MISSING_FIELD,
            CardRejectionReason.MISSING_FIELD, CardRejectionReason.SEED_MISMATCH, CardRejectionReason.EXPANSION_CAP), rejected)
        assertEquals(listOf("사람들의 요청·불편 자료 없음 — AI 추정"), result.single().caveats)
        assertTrue(result.single().supplementRefs.isEmpty())
    }
}

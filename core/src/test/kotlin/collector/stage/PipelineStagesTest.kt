package collector.stage

import collector.config.PipelineConfig
import collector.dev.FileHistorySearch
import collector.dev.JsonFileSeenStore
import collector.dev.JsonFileTrackedStore
import collector.llm.*
import collector.model.*
import collector.pipeline.FullPipeline
import collector.pipeline.PipelineResult
import collector.profile.ProfileLoader
import collector.prompt.FilePromptStore
import collector.prompt.PromptResolver
import collector.report.HtmlRenderer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import kotlin.test.*

class PipelineStagesTest {
    @TempDir lateinit var directory: Path
    private val config = PipelineConfig.defaults
    private val now = Instant.parse("2026-11-19T12:00:00Z")
    private val today = LocalDate.parse("2026-11-19")

    private fun item(id: Int, source: String = "hn") =
        Item(source, id.toString(), "회의록 자동화 $id", "https://example.com/$id", 50,
            publishedAt = now.minusSeconds(3600), profileHint = listOf("tech-signal"))
    private fun signal(item: Item, score: Double = 8.0, tag: String = "tools", topic: String = "topic-${item.externalId}",
                       profile: String = "tech-signal") =
        Signal(item, ClassifiedItem(item.key, profile, score, "이유", "요약", listOf(tag), topic, "주제", listOf("tracked")))
    private fun card(tech: Item, demand: Item) = IdeaCard(
        "card", today.toString(), "회의록 <도구>", "한 줄", "오늘", "문제",
        listOf(SignalRef(tech.key, tech.url, "기술")), listOf(SignalRef(demand.key, demand.url, "수요")),
        emptyList(), emptyList(), "1인 2주", "차이", "위험", "검증", emptyList(), "dev-tools",
        tags = listOf("meeting-notes")
    )

    @Test fun `분류는 25건씩 묶고 파싱 1회 재시도 후 누락과 none을 기록한다`() = runTest {
        val inputs = (1..26).map { item(it) }
        val prompts = PromptResolver(FilePromptStore(directory))
        val profiles = ProfileLoader.loadDefaults()
        val good = signal(inputs[0]).classification
        val none = signal(inputs[1]).classification.copy(profile = "none", score = 0.0)
        val batches = mutableListOf<Int>()
        val fake = object : LlmProvider {
            override val id = ProviderId.ANTHROPIC
            override suspend fun listModels() = emptyList<ModelInfo>()
            override suspend fun complete(req: LlmRequest): LlmResult {
                assertTrue(req.system.contains("profile.tech-signal.match"))
                val input = Json.parseToJsonElement(req.user).jsonObject
                batches += input.getValue("items").jsonArray.size
                val body = when (batches.size) {
                    1 -> "잘못된 JSON"
                    2 -> """{"items":${Json.encodeToJsonElement(listOf(good, none))}}"""
                    3 -> """{"items":[]}"""
                    else -> """{"items":${Json.encodeToJsonElement(listOf(signal(inputs.last()).classification))}}"""
                }
                return LlmResult(body, id, req.model, 0, 0, 0, 0.0)
            }
        }
        val costs = CostGuard(0.5)
        val result = Classifier(config, profiles, prompts, LlmRouter(listOf(fake), costs)).classify(inputs, emptyList(), emptyList())
        assertEquals(listOf(25, 25, 23, 1), batches)
        assertEquals(7, costs.calls.first().promptVersions.size)
        assertEquals(listOf("hn:1", "hn:2", "hn:26"), result.signals.map { it.item.key })
        assertEquals(23, result.dropped.count { it.reason == DropReason.CLASSIFY_FAILED })
        assertEquals(listOf("hn:2"), result.dropped.filter { it.reason == DropReason.NOT_RELEVANT }.map { it.item.key })
    }

    @Test fun `후보는 정렬 순서대로 태그와 프로필 상한을 채운다`() {
        val top = item(1).copy(score = 95)
        val limit = config.copy(maxCandidatesPerTag = 1, profiles = config.profiles +
            ("tech-signal" to PipelineConfig.Selection(6.0, 2)))
        val result = Selector(limit).select(listOf(signal(item(2)), signal(item(3), score = 5.0),
            signal(item(4), tag = "other"), signal(top), signal(item(5), tag = "third")))
        assertEquals(listOf("hn:1", "hn:4"), result.kept.map { it.item.key })
        assertEquals(2, result.dropped.count { it.reason == DropReason.OVER_QUOTA })
        assertEquals(1, result.dropped.count { it.reason == DropReason.BELOW_PROFILE_THRESHOLD })
    }

    @Test fun `남은 비용을 넘길 수 있는 요청은 모델에 보내지 않는다`() = runTest {
        val fake = object : LlmProvider {
            override val id = ProviderId.ANTHROPIC
            override suspend fun listModels() = emptyList<ModelInfo>()
            override suspend fun complete(req: LlmRequest): LlmResult = error("모델을 호출하면 안 됩니다.")
        }
        val costs = CostGuard(0.5, initialSpentUsd = 0.499)
        val router = LlmRouter(listOf(fake), costs, preflightBudget = true)
        val request = LlmRequest("", "분류", "입력", schema("items" to strings), "test", 100_000)
        assertFailsWith<BudgetExceededException> { router.complete("classify", request, "classify", "default") }
        assertTrue(costs.calls.isEmpty())
    }

    @Test fun `카드 작성은 수집 신호와 기준을 보내고 카드 날짜와 id를 채운다`() = runTest {
        val market = item(3, "github")
        val tech = item(1).copy(mergedUrls = listOf(market.url))
        val demand = item(2)
        val expected = card(tech, demand)
        val prompts = PromptResolver(FilePromptStore(directory))
        val fake = object : LlmProvider {
            override val id = ProviderId.ANTHROPIC
            override suspend fun listModels() = emptyList<ModelInfo>()
            override suspend fun complete(req: LlmRequest): LlmResult {
                val user = Json.parseToJsonElement(req.user).jsonObject
                assertEquals(today.toString(), user.getValue("today").jsonPrimitive.content)
                val signals = user.getValue("signals").jsonArray
                assertEquals(2, signals.size)
                val merged = signals.first().jsonObject.getValue("mergedSignals").jsonArray.single().jsonObject
                assertEquals(tech.key, merged.getValue("itemId").jsonPrimitive.content)
                assertEquals(market.url, merged.getValue("url").jsonPrimitive.content)
                assertEquals("market-signal", merged.getValue("profile").jsonPrimitive.content)
                assertContains(user.getValue("criteria").jsonPrimitive.content, "내 기준")
                return LlmResult("""{"cards":${Json.encodeToJsonElement(listOf(expected.copy(id = "", runDate = "")))}}""",
                    id, req.model, 0, 0, 0, 0.0)
            }
        }
        val costs = CostGuard(0.5)
        val result = IdeaGenerator(config, prompts, LlmRouter(listOf(fake), costs)).generate(today.toString(),
            listOf(signal(tech), signal(demand, profile = "demand-signal")),
            emptyMap(), emptyList(), emptyList(), mapOf(tech.key to listOf(signal(market, profile = "market-signal"))))
        val actual = result.cards.single()
        assertTrue(actual.id.isNotBlank())
        assertEquals(expected.copy(id = actual.id), actual)
        assertEquals(mapOf("ideas" to "default", "criteria" to "default"), costs.calls.single().promptVersions)
    }

    @Test fun generatesWithMergedTechAndRendersCollectedLicense() = runTest {
        val tech = item(1, "github").copy(extra = mapOf("license" to "MIT"))
        val demand = item(2).copy(snippet = "Original", mergedUrls = listOf(tech.url))
        val representative = signal(demand, profile = "demand-signal")
            .copy(hnContext = HnContext("Story", listOf("Comment")))
        val merged = mapOf(demand.key to listOf(signal(tech)))
        val generated = card(tech, demand).copy(techSignals = listOf(
            SignalRef(demand.key, tech.url, "기술 근거", license = "모델 값")
        ))
        val fake = object : LlmProvider {
            override val id = ProviderId.ANTHROPIC
            override suspend fun listModels() = emptyList<ModelInfo>()
            override suspend fun complete(req: LlmRequest): LlmResult {
                val sent = Json.parseToJsonElement(req.user).jsonObject.getValue("signals").jsonArray.single().jsonObject
                assertEquals("Original", sent.getValue("snippet").jsonPrimitive.content)
                assertEquals("Story", sent.getValue("storyText").jsonPrimitive.content)
                assertEquals("Comment", sent.getValue("topComments").jsonArray.single().jsonPrimitive.content)
                val member = sent.getValue("mergedSignals").jsonArray.single().jsonObject
                assertEquals("MIT", member.getValue("license").jsonPrimitive.content)
                val output = buildJsonObject { put("cards", Json.encodeToJsonElement(listOf(generated))) }
                return LlmResult(output.toString(), id, req.model, 0, 0, 0, 0.0)
            }
        }
        val generator = IdeaGenerator(config, PromptResolver(FilePromptStore(directory)), LlmRouter(listOf(fake), CostGuard(0.5)))
        val signals = listOf(representative)
        val cards = generator.generate(today.toString(), signals, emptyMap(), emptyList(), emptyList(), merged).cards
        val validated = assertNotNull(CitationValidator(signals, merged).validate(cards.single()))
        assertEquals("MIT", validated.techSignals.single().license)
        val report = RunReport(today.toString(), now.toString(), emptyList(), emptyList(), emptyMap(), 0)
        val rendered = HtmlRenderer(emptyList()).render(PipelineResult(emptyList(), emptyList(), report, cards = listOf(validated)))
        for (body in listOf(rendered.html, rendered.text)) {
            assertContains(body, "기술 근거 · 라이선스 MIT")
            assertFalse(body.contains("모델 값"))
        }
    }

    @Test fun `작성 출력이 잘리면 상한을 올려 한 번 재시도하고 단계는 하나씩 기록한다`() = runTest {
        val first = item(1, "github")
        val second = item(2, "github")
        val expected = card(first, second).let { it.copy(demandSignals = emptyList(), competitors = it.demandSignals) }
        val inputs = listOf(first, second)
        val tokenLimits = mutableListOf<Int>()
        val fake = object : LlmProvider {
            override val id = ProviderId.ANTHROPIC
            override suspend fun listModels() = emptyList<ModelInfo>()
            override suspend fun complete(req: LlmRequest): LlmResult {
                val body = when (req.schemaName) {
                    "classify" -> """{"items":${Json.encodeToJsonElement(inputs.map { signal(it).classification })}}"""
                    "generate" -> {
                        tokenLimits += req.maxOutputTokens
                        if (tokenLimits.size == 1) throw LlmServiceException(reason = "truncated")
                        """{"cards":${Json.encodeToJsonElement(listOf(expected))}}"""
                    }
                    else -> error("예상하지 않은 단계")
                }
                return LlmResult(body, id, req.model, 0, 0, 0, 0.0)
            }
        }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(404).message("Not Found").body("".toResponseBody()).build()
        }.build()
        val costs = CostGuard(0.5)
        val full = FullPipeline(config, ProfileLoader.loadDefaults(), PromptResolver(FilePromptStore(directory)),
            LlmRouter(listOf(fake), costs), costs, http, FileHistorySearch(directory.toFile(), config.historyDays),
            JsonFileTrackedStore(directory.resolve("tracked.json").toFile()))
        val base = PipelineResult(inputs, emptyList(), RunReport(today.toString(), now.toString(), emptyList(),
            emptyList(), emptyMap(), 0))
        val result = full.run(base, inputs, emptyList(), emptyList(), now, today) {}
        assertEquals(2, tokenLimits.size)
        assertEquals(tokenLimits[0] * 3 / 2, tokenLimits[1])
        assertEquals(expected.title, result.cards.single().title)
        assertEquals(1, result.report.stages.single { it.stage == "generate" }.output)
        assertEquals(1, result.report.stages.single { it.stage == "validate" }.output)
    }

    @Test fun `가짜 인용을 지우고 근거 부족 카드와 분류 상한 초과 카드는 버린다`() {
        val tech = item(1)
        val demand = item(2)
        val original = card(tech, demand)
        val fake = SignalRef(tech.key, "https://invented.example", "가짜")
        val citations = CitationValidator(listOf(signal(tech.copy(mergedUrls = listOf("https://example.com/merged"))),
            signal(demand, profile = "demand-signal")))
        assertEquals(original, citations.validate(original.copy(techSignals = original.techSignals + fake)))
        assertNull(citations.validate(original.copy(techSignals = listOf(fake))))
        assertNotNull(citations.validate(original.copy(techSignals = listOf(fake.copy(url = "https://example.com/merged")))))
        val copies = (1..4).map { original.copy(id = it.toString(), category = if (it == 1) "unknown" else original.category) }
        assertEquals(listOf("2", "3"), CardValidator(config, setOf("dev-tools")).validate(copies, citations).map { it.id })
    }

    @Test fun `인용은 근거 유형과 항목 URL 쌍을 검사하고 같은 원본의 재사용을 제외한다`() {
        val tech = item(1)
        val demand = item(2)
        val market = item(3)
        val competitor = item(4)
        val original = card(tech, demand)
        val refs = (1..5).map { SignalRef(item(it).key, item(it).url, "근거") }
        val (t, d, m, c, outside) = refs
        val citations = CitationValidator(listOf(signal(tech), signal(demand, profile = "demand-signal"),
            signal(market, profile = "market-signal"), signal(competitor)))
        val validated = assertNotNull(citations.validate(original.copy(
            techSignals = listOf(d, t),
            demandSignals = listOf(m, d),
            competitors = listOf(d, t, m, c, outside, c.copy(url = outside.url))
        )))
        assertEquals(listOf(t), validated.techSignals)
        assertEquals(listOf(d), validated.demandSignals)
        assertEquals(listOf(m, c), validated.competitors)
        assertNull(citations.validate(original.copy(techSignals = listOf(t), demandSignals = emptyList(), competitors = listOf(t))))
    }

    @Test fun `병합 URL은 원본 유형과 항목을 유지하고 전송하지 않은 그룹은 인용할 수 없다`() {
        val representative = item(1)
        val market = item(2, "github")
        val uncited = item(3, "github")
        val excluded = item(4)
        val tech = signal(representative.copy(mergedUrls = listOf(market.url, uncited.url)))
        val citations = CitationValidator(listOf(tech, signal(item(5))), mapOf(
            representative.key to listOf(signal(market, profile = "market-signal"), signal(uncited)),
            excluded.key to listOf(signal(excluded, profile = "market-signal"))
        ))
        val marketAlias = SignalRef(representative.key, market.url, "아래에 병합된 제품")
        val original = card(representative, item(5))
        val validated = assertNotNull(citations.validate(original.copy(
            techSignals = original.techSignals + marketAlias,
            demandSignals = emptyList(),
            competitors = listOf(marketAlias, SignalRef(market.key, market.url, "미전송 ID"),
                SignalRef(uncited.key, uncited.url, "미전송 ID"), SignalRef(excluded.key, excluded.url, "미전송 근거"))
        )))
        assertEquals(original.techSignals, validated.techSignals)
        assertEquals(listOf(marketAlias), validated.competitors)
        assertEquals(listOf(representative.key, market.key), citations.citedSignals(validated).map { it.item.key })
        val techAlias = SignalRef(representative.key, uncited.url, "병합된 기존 오픈소스")
        val distinctSources = assertNotNull(citations.validate(original.copy(
            techSignals = listOf(techAlias),
            demandSignals = emptyList(),
            competitors = listOf(techAlias.copy(note = "같은 원본"), marketAlias)
        )))
        assertEquals(listOf(marketAlias), distinctSources.competitors)
        assertEquals(listOf(uncited.key, market.key), citations.citedSignals(distinctSources).map { it.item.key })
    }

    @Test fun `배지는 출처 수 연속 등장과 두 종류의 급상승 이력으로 계산한다`() {
        val github = item(1, "github")
        val hn = item(2)
        val topics = listOf(TopicHistory("topic-1", "주제", today.minusDays(2).toString(), listOf(github.key)),
            TopicHistory("topic-1", "주제", today.toString(), listOf(github.key)))
        val stats = listOf(MetricSnapshot(github.key, 20, now.minusSeconds(3600)))
        val calculator = BadgeCalculator(config.badges)
        assertEquals(listOf("여러 곳에서 화제", "연속 등장", "급상승"),
            calculator.badges(listOf(signal(github), signal(hn)), topics, stats, now, today))
        assertEquals(listOf("급상승"), calculator.badges(listOf(signal(github)), emptyList(), stats, now, today))
    }

    @Test fun `배지는 인용되지 않은 출처와 20시간 미만의 스냅숏을 무시한다`() {
        val github = item(1, "github").copy(rawScore = 100, publishedAt = now.minusSeconds(100L * 86400),
            mergedUrls = listOf(item(3).url))
        val other = item(2, "github").copy(rawScore = 0)
        val unrelated = item(3)
        val original = card(github, other)
        val topics = listOf(
            TopicHistory("topic-3", "주제", today.minusDays(1).toString(), listOf(unrelated.key)),
            TopicHistory("topic-3", "주제", today.toString(), listOf(unrelated.key))
        )
        val stats = listOf(MetricSnapshot(github.key, 0, now.minusSeconds(19 * 3600L)))
        val calculator = BadgeCalculator(config.badges)
        val citations = CitationValidator(listOf(signal(github), signal(other, profile = "demand-signal")),
            mapOf(github.key to listOf(signal(unrelated))))
        val cited = citations.citedSignals(assertNotNull(citations.validate(original)))
        assertTrue(calculator.badges(cited, topics, stats, now, today).isEmpty())
        val deduplicated = signal(github.copy(alsoSeenIn = listOf("hn")))
        val selected = Selector(config).select(listOf(signal(github), signal(unrelated).copy(
            classification = signal(github).classification.copy(score = 7.0)
        )))
        assertEquals(unrelated.key, selected.groups.getValue(github.key).single().item.key)
        assertTrue(calculator.badges(selected.kept, emptyList(), emptyList(), now, today).isEmpty())
        assertEquals(listOf("여러 곳에서 화제"),
            calculator.badges(listOf(deduplicated), emptyList(), emptyList(), now, today))
        assertEquals(listOf("급상승"), calculator.badges(cited, topics,
            listOf(stats.single().copy(rawScore = 100), MetricSnapshot(github.key, 0, now.minusSeconds(20 * 3600L))),
            now, today))
    }

    @Test fun `추적 키워드와 7일 강도 변화 및 조용한 상태를 계산한다`() {
        val tech = item(1)
        val demand = item(2)
        val row = signal(tech)
        assertEquals(listOf("회의록", "도구", "meeting-notes", "topic-1"),
            TrackingKeywords.extract(card(tech, demand), listOf(row)))
        val idea = TrackedIdea("tracked", "card", "제목", "dev-tools", listOf("회의록"), emptyList(),
            TrackedIdea.Status.TRACKING, now.minusSeconds(31L * 86400), null, 0.0, TrackedIdea.Trend.FLAT)
        val calculator = StrengthCalculator(config.tracking)
        val weighted = row.copy(classification = row.classification.copy(profile = "demand-signal"))
        val quiet = calculator.update(idea, mapOf(today to listOf(weighted), today.minusDays(7) to listOf(row)), today, now, false)
        assertEquals(1.2, quiet.strength, 0.00001)
        assertEquals(TrackedIdea.Trend.UP, quiet.trend)
        assertEquals(TrackedIdea.Status.QUIET, quiet.status)
        assertEquals(TrackedIdea.Status.TRACKING, calculator.update(quiet, emptyMap(), today, now, true).status)
    }

    @Test fun `리포트는 두 근거 링크와 추적 비용 및 실패 출처를 표시한다`() {
        val report = RunReport(today.toString(), now.toString(), listOf(SourceResult("github", 0, "시간 초과", 1)),
            listOf(StageResult("select", 3, 2, 1)), emptyMap(), 2)
        val result = PipelineResult(emptyList(), emptyList(), report, cards = listOf(card(item(1), item(2))))
        val rendered = HtmlRenderer(listOf(Category("dev-tools", "개발 도구"))).render(result)
        for (section in listOf("깔때기", "실패 출처", "목차", "오늘 고른 이유", "가장 큰 위험", "추적 업데이트",
            "오늘 비용", "실행 시간", "ideacollector://card/card", "https://example.com/1", "https://example.com/2")) {
            assertContains(rendered.html, section)
            assertContains(rendered.text, section)
        }
        assertContains(rendered.html, "회의록 &lt;도구&gt;")
    }

    @Test fun `이미 본 기록은 같은 날 다시 허용하고 이전 날짜와 구형 기록은 제외한다`() = runTest {
        val file = directory.resolve("seen.json").toFile()
        file.writeText("""{"keys":["hn:3"],"urls":[]}""")
        val store = JsonFileSeenStore(file)
        store.markSeen(listOf(item(1)), today.minusDays(1))
        store.markSeen(listOf(item(2)), today)
        val reloaded = JsonFileSeenStore(file)
        assertEquals(listOf("hn:2", "hn:4"), reloaded.filterUnseen((1..4).map { item(it) }, today).map { it.key })
        assertTrue(reloaded.filterUnseen(listOf(item(2)), today.plusDays(1)).isEmpty())
    }
}

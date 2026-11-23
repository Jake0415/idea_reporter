package collector.stage

import collector.config.PipelineConfig
import collector.llm.*
import collector.model.*
import collector.pipeline.PipelineResult
import collector.profile.ProfileLoader
import collector.prompt.FilePromptStore
import collector.prompt.PromptResolver
import collector.report.HtmlRenderer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import collector.llm.BatchMockServer
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import kotlin.test.*

class P13StageTest {
    @TempDir lateinit var directory: File
    private val prompts by lazy { PromptResolver(FilePromptStore(directory.toPath())) }
    private val date = "2026-11-23"
    private val model = "claude-haiku-5-5"
    private val config = PipelineConfig.defaults
    private val tech = signal("1", "tech-signal")
    private val demand = signal("2", "demand-signal")
    private val signals = listOf(tech, demand)

    private fun signal(id: String, profile: String): Signal {
        val item = Item("hn", id, "topic $id", "https://example.org/item/$id", 30,
            publishedAt = Instant.parse("2026-11-23T00:00:00Z"), profileHint = listOf(profile))
        return Signal(item, ClassifiedItem(item.key, profile, 8.0, "reason", "summary", listOf("tag"), "topic-$id", "Topic"))
    }

    private fun card() = IdeaCard(title = "Card", oneLiner = "Summary", whyToday = "Now", problem = "Problem",
        techSignals = listOf(SignalRef(tech.item.key, tech.item.url, "Tech")),
        demandSignals = listOf(SignalRef(demand.item.key, demand.item.url, "Demand")), competitors = emptyList(),
        history = emptyList(), effort = "Week", differentiation = "Different", biggestRisk = "Risk",
        validationPlan = "Plan", caveats = emptyList(), category = "dev-tools", tags = listOf("tag"),
        supplementRefs = listOf(SupplementRef("http://www.example.com/result/?utm_source=test", "Model title", "Useful"),
            SupplementRef("https://example.com/invented", "Invented", "Unverified")))

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun message(body: String) = """{"id":"msg_1","type":"message","role":"assistant","model":"$model",
        "stop_reason":"end_turn","stop_sequence":null,"content":$body,"usage":{"input_tokens":5,"output_tokens":5}}""".replace("\n", "")
    private fun text(body: String) = message("""[{"type":"text","text":${Json.encodeToString(body)}}]""")
    private fun search() = json(message("""[{"type":"web_search_tool_result","tool_use_id":"srvtool_1",
        "caller":{"type":"direct"},"content":[{"type":"web_search_result","url":"https://WWW.Example.com/result/?ref=batch#source",
        "title":"Source title","encrypted_content":"mock","page_age":null}]}]"""))
    private fun cards(card: IdeaCard) = json(text("""{"cards":${Json.encodeToString(listOf(card))}}"""))

    @Test fun keepsOnlyVerifiedSupplementAndRendersItSeparately() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                server.enqueue(search())
                server.enqueue(cards(card()))
                val generator = IdeaGenerator(config, prompts, LlmRouter(listOf(provider), CostGuard(1.0)))
                val result = generator.generate(date, signals, emptyMap(), emptyList(), emptyList())
                assertEquals(listOf("보충 근거 링크 1건 제거"), result.issues)
                val card = result.cards.single()
                assertEquals(listOf(SupplementRef("https://WWW.Example.com/result/?ref=batch#source", "Source title", "Useful")), card.supplementRefs)
                assertNotNull(CitationValidator(signals).validate(card))
                assertNull(CitationValidator(signals).validate(card.copy(techSignals = emptyList(), demandSignals = emptyList())))
                val report = RunReport(date, "", emptyList(), emptyList(), emptyMap(), 125_000,
                    classificationBatchWaitMillis = 121_000)
                val rendered = HtmlRenderer(generator.categories).render(PipelineResult(emptyList(), emptyList(), report,
                    cards = listOf(card)))
                assertTrue("보충 자료(웹 검색)" in rendered.html)
                assertTrue("보충 자료(웹 검색)" in rendered.text)
                assertTrue("분류 Batch 대기 2분" in rendered.html)
                assertTrue("Source title" in rendered.html)
                assertFalse("invented" in rendered.html)
                server.takeRequest()
                val request = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                assertFalse("tools" in request)
                assertTrue("보충 자료" in request["messages"].toString())
            }
        }
    }

    @Test fun insufficientSearchBudgetStillWritesCards() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                server.enqueue(cards(card().copy(supplementRefs = emptyList())))
                val router = LlmRouter(listOf(provider), CostGuard(0.02), preflightBudget = true,
                    stageModels = mapOf("generate" to StageModel(ProviderId.ANTHROPIC, model)))
                val result = IdeaGenerator(config, prompts, router).generate(date, signals, emptyMap(), emptyList(), emptyList())
                assertEquals(1, result.cards.size)
                assertEquals(listOf("예산 부족 · 보충 검색 생략"), result.issues)
                assertEquals(1, server.requestCount)
                assertFalse("tools" in Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject)
            }
        }
    }

    @Test fun resumedGenerationChecksOriginalSearchProvenanceWithoutRepeatingSearch() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val batchConfig = config.copy(batch = config.batch.copy(generate = true, pollSeconds = 1))
                val generator = IdeaGenerator(batchConfig, prompts, LlmRouter(listOf(provider), CostGuard(1.0)))
                server.enqueue(search())
                server.enqueue(json("""{"id":"batch_1","processing_status":"in_progress"}"""))
                server.enqueue(json("{}").setResponseCode(503))
                assertTrue(generator.generate(date, signals, emptyMap(), emptyList(), emptyList(), out = directory).cards.isEmpty())
                server.enqueue(json("""{"id":"batch_1","processing_status":"ended"}"""))
                val success = text("""{"cards":${Json.encodeToString(listOf(card()))}}""")
                server.enqueue(json("""{"custom_id":"generate-$date-1","result":{"type":"succeeded","message":$success}}""" + "\n"))
                val result = generator.generate(date, signals, emptyMap(), emptyList(), emptyList(), out = directory)
                assertEquals(listOf("Batch 재개", "보충 근거 링크 1건 제거"), result.issues)
                assertEquals(listOf(SupplementRef("https://WWW.Example.com/result/?ref=batch#source", "Source title", "Useful")),
                    result.cards.single().supplementRefs)
                assertEquals(listOf("/v1/messages", "/v1/messages/batches", "/v1/messages/batches/batch_1",
                    "/v1/messages/batches/batch_1", "/v1/messages/batches/batch_1/results"),
                    List(5) { server.takeRequest().path })
                server.enqueue(cards(card()))
                val changed = generator.generate(date, signals, emptyMap(), listOf("changed feedback"), emptyList(), out = directory)
                assertEquals(result.cards.single().supplementRefs, changed.cards.single().supplementRefs)
                val sync = server.takeRequest()
                assertEquals("/v1/messages", sync.path)
                assertContains(sync.body.readUtf8(), "https://WWW.Example.com/result/?ref=batch#source")
            }
        }
    }

    @Test fun resumesByItemKeyWhenInputsAndChunksChanged() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val router = LlmRouter(listOf(provider), CostGuard(1.0))
                val profiles = ProfileLoader.loadDefaults()
                val first = Classifier(config.copy(classifyBatchSize = 1, batch = config.batch.copy(pollSeconds = 1)),
                    profiles, prompts, router)
                server.enqueue(json("""{"id":"batch_1","processing_status":"in_progress"}"""))
                server.enqueue(json("{}").setResponseCode(503))
                assertTrue(first.classify(signals.map { it.item }, emptyList(), emptyList(), runDate = date, out = directory).batchPending)
                server.enqueue(json("""{"id":"batch_1","processing_status":"ended"}"""))
                fun result(index: Int, signal: Signal) =
                    """{"custom_id":"classify-$date-$index","result":{"type":"succeeded","message":${text(
                        """{"items":[${Json.encodeToString(signal.classification)}]}""")}}}"""
                server.enqueue(json(result(1, tech) + "\n" + result(2, demand) + "\n"))
                val newItem = signal("3", "demand-signal")
                server.enqueue(json(text("""{"items":[${Json.encodeToString(newItem.classification)}]}""")))
                val changed = listOf(demand.item.copy(rawScore = 100), newItem.item)
                val resumed = Classifier(config.copy(classifyBatchSize = 2, batch = config.batch.copy(classify = false)),
                    profiles, prompts, router).classify(changed, emptyList(), emptyList(), runDate = date, out = directory)
                assertEquals(changed, resumed.signals.map { it.item })
                assertEquals(listOf(demand.classification, newItem.classification), resumed.signals.map { it.classification })
                assertEquals(5, server.requestCount)
                repeat(4) { server.takeRequest() }
                val sync = server.takeRequest().body.readUtf8()
                assertContains(sync, newItem.item.key)
                assertFalse(demand.item.key in sync)
            }
        }
    }

    @Test fun synchronousFallbackSplitsTruncatedChunk() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                server.enqueue(json("""{"id":"batch_1","processing_status":"ended"}"""))
                server.enqueue(json("""{"custom_id":"classify-$date-1","result":{"type":"canceled"}}""" + "\n"))
                server.enqueue(json(text("{}").replace("end_turn", "max_tokens")))
                for (signal in signals) {
                    server.enqueue(json(text("""{"items":[${Json.encodeToString(signal.classification)}]}""")))
                }
                val classifier = Classifier(config.copy(classifyBatchSize = 2), ProfileLoader.loadDefaults(), prompts,
                    LlmRouter(listOf(provider), CostGuard(1.0)))
                val result = classifier.classify(signals.map { it.item }, emptyList(), emptyList(), runDate = date, out = directory)
                assertEquals(signals.map { it.classification }, result.signals.map { it.classification })
                assertEquals(5, server.requestCount)
            }
        }
    }

    @Test fun classifierRetriesOnlyErroredBatchChunk() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                server.enqueue(json("""{"id":"batch_1","processing_status":"ended"}"""))
                val success = text("""{"items":[${Json.encodeToString(tech.classification)}]}""")
                val error = """{"type":"errored","error":{"type":"error","error":{"type":"invalid_request_error","message":"mock"}}}"""
                server.enqueue(json("""{"custom_id":"classify-$date-2","result":$error}""" + "\n" +
                    """{"custom_id":"classify-$date-1","result":{"type":"succeeded","message":$success}}""" + "\n"))
                server.enqueue(json(text("""{"items":[${Json.encodeToString(demand.classification)}]}""")))
                val costs = CostGuard(1.0)
                val classifier = Classifier(config.copy(classifyBatchSize = 1), ProfileLoader.loadDefaults(), prompts,
                    LlmRouter(listOf(provider), costs, preflightBudget = true))
                val result = classifier.classify(signals.map { it.item }, emptyList(), emptyList(), runDate = date, out = directory)
                assertEquals(signals.map { it.classification }, result.signals.map { it.classification })
                assertEquals(listOf("Batch classification: reused 1, synchronous 1"), result.issues)
                assertEquals(listOf(true, true, false), costs.calls.map { it.batch })
                assertEquals(3, server.requestCount)
                val submitted = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["requests"]!!.jsonArray
                server.takeRequest()
                val sync = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                val async = submitted[1].jsonObject["params"]!!.jsonObject
                assertEquals(async, sync)
            }
        }
    }

    @Test fun earlierClassificationIsReusedAfterResultFileRetentionCleanup() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val router = LlmRouter(listOf(provider), CostGuard(1.0))
                val classifier = Classifier(config, ProfileLoader.loadDefaults(), prompts, router)
                server.enqueue(json("""{"id":"batch_1","processing_status":"ended"}"""))
                val success = text("""{"items":[${Json.encodeToString(tech.classification)}]}""")
                server.enqueue(json("""{"custom_id":"classify-$date-1","result":{"type":"succeeded","message":$success}}""" + "\n"))
                val first = classifier.classify(listOf(tech.item), emptyList(), emptyList(), runDate = date, out = directory)
                assertEquals(tech.classification, first.signals.single().classification)
                val batchDir = directory.resolve("batch")
                val saved = batchDir.resolve("batch_1.results.jsonl")
                val pendingResult = batchDir.resolve("still_pending.results.jsonl").apply { writeText("pending") }
                router.markAppliedBatchesConsumed()
                val done = batchDir.resolve("$date-classify.done")
                val expiredResult = batchDir.resolve("expired.results.jsonl").apply { writeText("already settled") }
                val expired = batchDir.resolve("2026-11-01-classify.json")
                expired.writeText(done.readText().replace("batch_1", "expired"))
                batchDir.resolve("2026-11-02-classify.json").writeText(
                    JsonObject(Json.parseToJsonElement(done.readText()).jsonObject
                        .minus("settled") + ("batchId" to JsonPrimitive("still_pending"))).toString())
                listOf(saved, pendingResult, done).forEach { assertTrue(it.setLastModified(1_000L)) }
                val later = java.time.LocalDate.parse(date).plusDays(8)
                collector.llm.cleanBatchFiles(batchDir, config.recentTopicDays,
                    later.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant())
                assertFalse(saved.exists())
                assertFalse(done.exists())
                assertTrue(pendingResult.isFile)
                assertFalse(expired.isFile)
                val expiredDone = batchDir.resolve("2026-11-01-classify.done")
                assertTrue(expiredDone.isFile)
                assertTrue(expiredResult.isFile)
                assertTrue(expiredDone.setLastModified(1_000L))
                collector.llm.cleanBatchFiles(batchDir, config.recentTopicDays,
                    later.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant())
                assertFalse(expiredDone.isFile)
                assertFalse(expiredResult.isFile)
                val second = classifier.classify(listOf(tech.item), emptyList(), emptyList(),
                    runDate = later.toString(), out = directory)
                assertEquals(tech.classification, second.signals.single().classification)
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun writingInputCapsSupplementCountAndText() = runBlocking {
        BatchMockServer().use { server ->
            AnthropicProvider("mock", server.url("/").toString()).use { provider ->
                val refs = (1..4).map { SupplementRef("https://example.com/$it", "T".repeat(121), "N".repeat(301)) }
                val blocks = refs.joinToString(",") {
                    """{"type":"web_search_result","url":"${it.url}","title":"${it.title}","encrypted_content":"mock","page_age":null}"""
                }
                val notes = Json.encodeToString(refs)
                server.enqueue(json(message("""[{"type":"web_search_tool_result","tool_use_id":"srvtool_1",
                    "caller":{"type":"direct"},"content":[$blocks]},{"type":"text","text":${Json.encodeToString(notes)}}]""")))
                server.enqueue(cards(card().copy(supplementRefs = emptyList())))
                val generator = IdeaGenerator(config, prompts, LlmRouter(listOf(provider), CostGuard(1.0)))
                assertEquals(1, generator.generate(date, signals, emptyMap(), emptyList(), emptyList()).cards.size)
                server.takeRequest()
                val input = server.takeRequest().body.readUtf8()
                assertContains(input, "https://example.com/3")
                assertFalse("https://example.com/4" in input)
                assertContains(input, "T".repeat(120))
                assertFalse("T".repeat(121) in input)
                assertContains(input, "N".repeat(300))
                assertFalse("N".repeat(301) in input)
            }
        }
    }
}


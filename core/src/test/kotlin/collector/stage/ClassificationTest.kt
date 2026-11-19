package collector.stage

import collector.config.PipelineConfig
import collector.llm.*
import collector.model.*
import collector.profile.ProfileLoader
import collector.profile.Sampling
import collector.prompt.FilePromptStore
import collector.prompt.PromptResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ClassificationTest {
    @TempDir lateinit var directory: Path
    private val profiles = ProfileLoader.loadDefaults()

    @Test fun parallelBatchesWaitForWarmupAndMergeInInputOrder() = runTest {
        var active = 0
        var peak = 0
        val finished = mutableListOf<String>()
        val items = listOf(8, 6, 1, 5, 2, 4, 3).map(::item)
        val config = PipelineConfig.defaults.copy(classifyBatchSize = 1, classifyConcurrency = 2)
        val result = classifier(config = config) { _, keys ->
            val key = keys.single()
            if (key != items.first().key) assertEquals(items.first().key, finished.firstOrNull())
            peak = maxOf(peak, ++active)
            try {
                delay(if (key == "hn:6") 100 else 10)
                if (key == "hn:1") throw LlmServiceException(reason = "truncated")
                listOf(row(key).copy(profile = "none"))
            } finally {
                active--
                finished += key
            }
        }.classify(items, emptyList(), emptyList())

        assertEquals(config.classifyConcurrency, peak)
        assertTrue(finished.indexOf("hn:5") < finished.indexOf("hn:6"))
        assertEquals(items.map { it.key } - "hn:1", result.signals.map { it.item.key })
        assertEquals(items.map { it.key }, result.dropped.map { it.item.key })
        assertEquals(DropReason.CLASSIFY_FAILED, result.dropped[2].reason)
    }

    @Test fun concurrencyOneCompletesRecoveryBeforeStartingNextBatch() = runTest {
        val config = PipelineConfig.defaults.copy(classifyBatchSize = 2, classifyConcurrency = 1)
        val calls = mutableListOf<List<String>>()
        val result = classifier(config = config) { _, keys ->
            delay(10)
            calls += keys
            when (keys) {
                listOf("hn:1", "hn:2") -> listOf(row("hn:1"))
                listOf("hn:3", "hn:4") -> throw LlmServiceException(reason = "truncated")
                else -> keys.map(::row)
            }
        }.classify((1..5).map(::item), emptyList(), emptyList())

        assertEquals(listOf(listOf("hn:1", "hn:2"), listOf("hn:2"), listOf("hn:3", "hn:4"),
            listOf("hn:3"), listOf("hn:4"), listOf("hn:5")), calls)
        assertEquals((1..5).map { "hn:$it" }, result.signals.map { it.item.key })
        assertTrue(result.dropped.isEmpty())
    }

    @Test fun parallelBatchesReserveOnlyRemainingBudgetAndKeepInputOrder() = runTest {
        val items = listOf(8, 6, 1, 5, 2, 4, 3, 9, 7, 0).map(::item)
        val config = PipelineConfig.defaults.copy(classifyBatchSize = 2, classifyConcurrency = 4)
        val batchCost = 928.0 // 928 maximum output tokens at $1 per token.
        val budget = CostGuard(3 * batchCost)
        val catalog = ModelCatalog(listOf(ModelPrice(ProviderId.ANTHROPIC, "test", ModelTier.FAST,
            inputUsd = 0.0, outputUsd = 1_000_000.0, cachedInputUsd = 0.0)))
        val started = mutableListOf<String>()
        val finished = mutableListOf<String>()
        val result = classifier(costUsd = batchCost, config = config, costGuard = budget,
            preflightBudget = true, catalog = catalog) { _, keys ->
            started += keys.first()
            when (started.size) {
                2 -> delay(100)
                3 -> delay(10)
            }
            finished += keys.first()
            keys.map(::row)
        }.classify(items, emptyList(), emptyList())

        assertEquals(3, started.size)
        assertEquals(listOf(started[0], started[2], started[1]), finished)
        assertEquals(budget.dailyBudgetUsd, budget.calls.sumOf { it.costUsd })
        assertEquals(items.chunked(2).filter { it.first().key in started }.flatten(), result.signals.map { it.item })
        assertEquals(items.chunked(2).filter { it.first().key !in started }.flatten(), result.dropped.map { it.item })
        assertEquals(List(4) { DropReason.BUDGET_EXCEEDED }, result.dropped.map { it.reason })
    }

    @Test fun parentCancellationCancelsActiveRequestsWithoutStartingQueuedBatches() = runTest {
        val items = (1..5).map(::item)
        val config = PipelineConfig.defaults.copy(classifyBatchSize = 1, classifyConcurrency = 2)
        val started = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val bothActive = CompletableDeferred<Unit>()
        val classifier = classifier(config = config) { _, keys ->
            val key = keys.single()
            started += key
            if (key == items.first().key) listOf(row(key)) else {
                if (started.size == 3) bothActive.complete(Unit)
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    cancelled += key
                    throw e
                }
            }
        }
        val parent = async { classifier.classify(items, emptyList(), emptyList()) }
        bothActive.await()
        yield()
        parent.cancelAndJoin()

        assertTrue(parent.isCancelled)
        assertEquals(3, started.size)
        assertEquals(started.drop(1).toSet(), cancelled.toSet())
    }

    @Test fun parallelBatchCancellationPropagates() = runTest {
        val config = PipelineConfig.defaults.copy(classifyBatchSize = 1)
        assertFailsWith<CancellationException> {
            classifier(config = config) { _, keys ->
                if (keys.single() == "hn:2") throw CancellationException()
                keys.map(::row)
            }.classify((1..3).map(::item), emptyList(), emptyList())
        }
    }

    @Test fun `분류가 잘리면 최대 두 단계까지 묶음을 반으로 나눈다`() = runTest {
        val items = (1..8).map(::item)
        val requested = mutableListOf<List<String>>()
        val result = classifier { request, keys ->
            requested += keys
            assertTrue(request.maxOutputTokens >= keys.size * 400)
            if (keys.size > 2 || keys.first() == "hn:3") throw LlmServiceException(reason = "truncated")
            keys.map(::row)
        }.classify(items, emptyList(), emptyList())

        assertEquals(listOf(8, 4, 2, 2, 4, 2, 2), requested.map { it.size })
        assertEquals(items.map { it.key } - setOf("hn:3", "hn:4"), result.signals.map { it.item.key })
        assertEquals(listOf("hn:3", "hn:4"), result.dropped.map { it.item.key })
        assertTrue(result.dropped.all { it.reason == DropReason.CLASSIFY_FAILED })
    }

    @Test fun `검사별 분류 실패 사유를 기록한다`() = runTest {
        val result = classifier { _, keys ->
            keys.map { key ->
                when (key) {
                    "hn:1" -> row(key).copy(profile = "market-signal")
                    "hn:2" -> row(key).copy(profile = "unknown")
                    "hn:3" -> row(key).copy(score = null)
                    else -> row(key).copy(topicKey = "/".repeat(45))
                }
            }
        }.classify((1..4).map(::item), emptyList(), emptyList())

        assertTrue(result.signals.isEmpty())
        assertEquals(List(4) { DropReason.CLASSIFY_FAILED }, result.dropped.map { it.reason })
        assertEquals(listOf("후보에 없는 프로필(market-signal)", "알 수 없는 프로필(unknown)",
            "점수 없음(null)", "주제 키 형식(${"/".repeat(40)})"), result.dropped.map { it.detail })
    }

    @Test fun `주제 키를 정규화한 후 이전 주제 이름을 적용한다`() = runTest {
        val result = classifier { _, keys ->
            listOf(row(keys[0]).copy(topicKey = " -- BAd _./ Key -- "),
                row(keys[1]).copy(topicKey = "A".repeat(59) + "/B"))
        }.classify((1..2).map(::item), listOf(TopicHistory("bad-key", "Previous", "2026-11-19", emptyList())),
            emptyList())

        assertEquals(listOf("bad-key", "a".repeat(59)), result.signals.map { it.classification.topicKey })
        assertEquals("Previous", result.signals.first().classification.topicLabel)
        assertTrue(result.dropped.isEmpty())
    }

    @Test fun `누락 항목과 후보 밖 응답과 점수 없는 응답을 함께 한 번 재요청한다`() = runTest {
        val requested = mutableListOf<List<String>>()
        val result = classifier { request, keys ->
            requested += keys
            if (requested.size == 1) listOf(row(keys[0]).copy(profile = "none", score = null),
                row(keys[1]).copy(profile = "market-signal"), row(keys[2]).copy(score = null))
            else {
                val inputs = Json.parseToJsonElement(request.user).jsonObject.getValue("items").jsonArray
                assertEquals(List(3) { listOf("tech-signal", "none") }, inputs.map {
                    it.jsonObject.getValue("candidates").jsonArray.map { candidate -> candidate.jsonPrimitive.content }
                })
                keys.map(::row)
            }
        }.classify((1..4).map(::item), emptyList(), emptyList())

        assertEquals(listOf(listOf("hn:1", "hn:2", "hn:3", "hn:4"), listOf("hn:2", "hn:3", "hn:4")), requested)
        assertEquals(listOf("hn:1", "hn:2", "hn:3", "hn:4"), result.signals.map { it.item.key })
        assertNull(result.signals.first().classification.score)
        assertEquals(DropReason.NOT_RELEVANT, result.dropped.single().reason)
    }

    @Test fun `후보 밖 프로필의 재요청이 예산에 막히면 예산 초과를 기록한다`() = runTest {
        val result = classifier(costUsd = 0.5) { _, keys ->
            listOf(row(keys[0]), row(keys[1]).copy(profile = "market-signal"))
        }.classify((1..2).map(::item), emptyList(), emptyList())

        assertEquals("hn:1", result.signals.single().item.key)
        assertEquals(DropReason.BUDGET_EXCEEDED, result.dropped.single().reason)
    }

    @Test fun recoveryKeepsRequestFailureOrOriginalCandidateReason() = runTest {
        for ((failure, detail) in listOf(
            "timeout" to "모델 호출 실패 (timeout)",
            "refused" to "응답 거절",
            "invalid_json" to "JSON 응답 오류",
            null to "후보에 없는 프로필(market-signal)"
        )) {
            var calls = 0
            val result = classifier { _, keys ->
                if (++calls == 1) listOf(row(keys.single()).copy(profile = "market-signal"))
                else if (failure == "refused") null
                else if (failure != null) throw LlmServiceException(reason = failure)
                else emptyList()
            }.classify(listOf(item(1)), emptyList(), emptyList())

            assertEquals(2, calls)
            assertEquals(DropReason.CLASSIFY_FAILED, result.dropped.single().reason)
            assertEquals(detail, result.dropped.single().detail)
        }
    }

    @Test fun `범위 밖 점수를 양쪽 경계로 보정하고 정상 점수는 유지한다`() = runTest {
        val result = classifier { _, keys ->
            keys.zip(listOf(-1.0, 11.0, 8.0)).map { (key, score) -> row(key).copy(score = score) }
        }.classify((1..3).map(::item), emptyList(), emptyList())

        assertEquals(listOf(0.0, 10.0, 8.0), result.signals.map { it.classification.score })
        assertTrue(result.dropped.isEmpty())
    }

    @Test fun `본문 표본은 크기 한도 안에서 처음 중간 끝을 유지한다`() {
        val http = OkHttpClient()
        val enricher = Enricher(http, profiles, CommentFetcher(http, 0))
        val text = "ABCDEFGHIJKLMNO"
        assertEquals("ABC…GHI…MNO", enricher.sample(text, 11, Sampling.HEAD_MIDDLE_TAIL))
        assertEquals("ABCDE", enricher.sample(text, 5, Sampling.PREFIX))
        assertEquals(text, enricher.sample(text, 20, Sampling.HEAD_MIDDLE_TAIL))
    }

    @Test fun `HN 본문과 상위 댓글은 별도 전송하고 작성 보강 시 응답을 재사용한다`() = runTest {
        val requests = AtomicInteger()
        val inFlight = AtomicInteger()
        val highWater = AtomicInteger()
        val longComment = "x".repeat(400)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val firebase = request.url.host == "hacker-news.firebaseio.com"
            val id = request.url.pathSegments.last().removeSuffix(".json")
            requests.incrementAndGet()
            highWater.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            try {
                val storyField = if (id == "3") "text" else "story_text"
                val story = if (id == "4") "story ".repeat(400) else "Story $id"
                val body = if (firebase) """{"kids":[16,15,14,13,12,11,10]}"""
                    else """{"$storyField":"<b>$story</b>","children":[
                        {"id":10,"text":"Sixth"},{"id":11,"text":"Fifth"},{"id":12,"text":"Fourth"},
                        {"id":13,"text":"Third"},{"id":14,"text":"$longComment"},
                        {"id":15,"text":"<b>First</b>"},{"id":16,"text":null}
                    ]}"""
                okhttp3.Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(if (id == "2") 500 else 200).message("test").body(body.toResponseBody()).build()
            } finally {
                inFlight.decrementAndGet()
            }
        }.build()
        val items = listOf(item(0).copy(source = "github")) +
            (1..41).map { item(it).copy(snippet = "Existing") }
        val fetcher = CommentFetcher(http, 5)
        val contexts = fetcher.contexts(items)
        val expectedComments = listOf("First", "x".repeat(299) + "…", "Third", "Fourth", "Fifth")
        assertEquals(79, requests.get())
        assertTrue(highWater.get() in 1..4)
        assertEquals(HnContext("Story 1", expectedComments), contexts[items[1].key])
        assertNull(contexts[items[2].key])
        assertEquals("Story 3", contexts.getValue(items[3].key).storyText)
        assertEquals(1000, contexts.getValue(items[4].key).storyText.length)
        assertNull(contexts[items[0].key])
        assertNull(contexts[items.last().key])

        val result = classifier { request, keys ->
            val input = Json.parseToJsonElement(request.user).jsonObject.getValue("items").jsonArray.single().jsonObject
            assertEquals("Existing", input.getValue("snippet").jsonPrimitive.content)
            assertEquals("Story 1", input.getValue("storyText").jsonPrimitive.content)
            assertEquals(expectedComments, input.getValue("topComments").jsonArray.map { it.jsonPrimitive.content })
            keys.map(::row)
        }.classify(listOf(items[1]), emptyList(), emptyList(), contexts)
        assertEquals(items[1], result.signals.single().item)
        fetcher.fetch(items[1])
        assertEquals(contexts[items[1].key], fetcher.context(items[1]))
        assertEquals(79, requests.get())
    }

    @Test fun redditTopCommentsReachClassificationAndBodyReachesEnrichment() = runTest {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            fun enqueue(body: String) = server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(body))
            val fullText = "request ".repeat(410)
            val longComment = "x".repeat(400)
            val replies = (1..6).joinToString(",") {
                val body = if (it == 1) longComment else "Reply $it"
                """{"kind":"t1","data":{"author":"author","body":"$body"}}"""
            }
            val thread = """[{"data":{"children":[]}},{"data":{"children":[$replies]}}]"""
            enqueue("""{"access_token":"mock","expires_in":3600}""")
            enqueue(collector.fixture("reddit_top.json").replace("Model feedback", fullText))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(500))
            enqueue(thread)
            val http = OkHttpClient()
            val base = server.url("/").toString()
            val reddit = collector.source.RedditAdapter(http, { "mock" }, base, base)
            val post = reddit.fetch(collector.config.RedditConfig(subreddits = listOf("LocalLLaMA")), collector.NOW).single()
            val comments = CommentFetcher(http, 5, reddit)
            assertTrue(comments.fetch(post).isEmpty())
            val contexts = comments.contexts(listOf(post))

            val result = classifier { request, keys ->
                val input = Json.parseToJsonElement(request.user).jsonObject.getValue("items").jsonArray.single().jsonObject
                assertEquals(300, input.getValue("snippet").jsonPrimitive.content.length)
                assertEquals(listOf(longComment.take(300), "Reply 2", "Reply 3", "Reply 4", "Reply 5"),
                    input.getValue("topComments").jsonArray.map { it.jsonPrimitive.content })
                keys.map { row(it).copy(profile = "demand-signal") }
            }.classify(listOf(post), emptyList(), emptyList(), contexts)

            val signal = Enricher(http, profiles, comments).enrich(result.signals, emptyMap()).single()
            assertEquals(fullText.take(3000), signal.body)
            assertEquals(5, signal.enrichedComments.size)
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun cancellingFirstHnCallerLetsWaitingCallerLoadThread() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CountDownLatch(1)
        val requests = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            if (requests.incrementAndGet() == 1) {
                firstStarted.complete(Unit)
                check(releaseFirst.await(10, TimeUnit.SECONDS))
            }
            okhttp3.Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("test").body("""{"text":"Story"}""".toResponseBody()).build()
        }.build()
        val fetcher = CommentFetcher(http, 5)
        val first = async { fetcher.context(item(1)) }
        firstStarted.await()
        try {
            val waiting = async { fetcher.context(item(1)) }
            yield()
            first.cancel()
            releaseFirst.countDown()
            first.cancelAndJoin()
            assertEquals(HnContext("Story", emptyList()), waiting.await())
            assertEquals(2, requests.get())
        } finally {
            releaseFirst.countDown()
        }
    }

    @Test fun retriesOnlyMissingAndDuplicateRowsOnce() = runTest {
        val requested = mutableListOf<List<String>>()
        val result = classifier { _, keys ->
            requested += keys
            if (requested.size == 1) listOf(row(keys[0]), row(keys[2]), row(keys[2]))
            else listOf(row(keys[1]))
        }.classify((1..3).map(::item), emptyList(), emptyList())

        assertEquals(listOf(listOf("hn:1", "hn:2", "hn:3"), listOf("hn:2", "hn:3")), requested)
        assertEquals(listOf("hn:1", "hn:3"), result.signals.map { it.item.key })
        assertEquals("hn:2", result.dropped.single().item.key)
        assertEquals(DropReason.CLASSIFY_FAILED, result.dropped.single().reason)
    }

    @Test fun missingRowRetryBlockedByBudgetPreservesCompletedRows() = runTest {
        val result = classifier(costUsd = 0.5) { _, keys -> listOf(row(keys.first())) }
            .classify((1..2).map(::item), emptyList(), emptyList())

        assertEquals(listOf("hn:1"), result.signals.map { it.item.key })
        assertEquals("hn:2", result.dropped.single().item.key)
        assertEquals(DropReason.BUDGET_EXCEEDED, result.dropped.single().reason)
    }

    @Test fun truncatedBatchSplitBlockedByBudgetPreservesCompletedRows() = runTest {
        val result = classifier(costUsd = 0.5) { _, keys ->
            if (keys.size > 1) throw LlmServiceException(reason = "truncated")
            listOf(row(keys.single()))
        }.classify((1..2).map(::item), emptyList(), emptyList())

        assertEquals(listOf("hn:1"), result.signals.map { it.item.key })
        assertEquals("hn:2", result.dropped.single().item.key)
        assertEquals(DropReason.BUDGET_EXCEEDED, result.dropped.single().reason)
    }

    @Test fun firebaseFailureKeepsAlgoliaCommentOrder() = runTest {
        val requests = AtomicInteger()
        val children = (1..6).joinToString(",") { """{"id":$it,"author":"a","text":"Comment $it"}""" }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            val request = chain.request()
            val firebase = request.url.host == "hacker-news.firebaseio.com"
            okhttp3.Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (firebase) 500 else 200).message("test")
                .body("""{"story_text":"Story","children":[$children]}""".toResponseBody()).build()
        }.build()
        val fetcher = CommentFetcher(http, 10)
        val expected = (1..5).map { "Comment $it" }
        assertEquals(HnContext("Story", expected), fetcher.context(item(1)))
        assertEquals(expected, fetcher.fetch(item(1)).map { it.text })
        assertEquals(2, requests.get())
    }

    private fun item(id: Int) = Item("hn", id.toString(), "Title", "https://example.com/$id", 50,
        publishedAt = Instant.EPOCH, profileHint = listOf("tech-signal"))

    private fun row(key: String) = ClassifiedItem(key, "tech-signal", 8.0, "reason", "summary", emptyList(), "topic", "Topic")

    private fun classifier(
        costUsd: Double = 0.0,
        config: PipelineConfig = PipelineConfig.defaults,
        costGuard: CostGuard = CostGuard(0.5),
        preflightBudget: Boolean = false,
        catalog: ModelCatalog = ModelCatalog.defaults,
        output: suspend (LlmRequest, List<String>) -> List<ClassifiedItem>?
    ): Classifier {
        val fake = object : LlmProvider {
            override val id = ProviderId.ANTHROPIC
            override suspend fun listModels() = emptyList<ModelInfo>()
            override suspend fun complete(req: LlmRequest): LlmResult {
                val keys = Json.parseToJsonElement(req.user).jsonObject.getValue("items").jsonArray
                    .map { it.jsonObject.getValue("itemId").jsonPrimitive.content }
                val rows = output(req, keys)
                return LlmResult("""{"items":${Json.encodeToJsonElement(rows)}}""", id, req.model, 0, 0, 0, costUsd,
                    refused = rows == null)
            }
        }
        val alternate = object : LlmProvider {
            override val id = ProviderId.OPENAI
            override suspend fun listModels() = emptyList<ModelInfo>()
            override suspend fun complete(req: LlmRequest): LlmResult =
                throw AssertionError("이 분류 요청은 공급자를 바꾸어 반복하지 않는다.")
        }
        return Classifier(config, profiles, PromptResolver(FilePromptStore(directory)),
            LlmRouter(listOf(fake, alternate), costGuard, catalog = catalog, preflightBudget = preflightBudget))
    }
}

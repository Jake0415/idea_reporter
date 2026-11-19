package collector.pipeline

import collector.NOW
import collector.config.GitHubConfig
import collector.config.HnConfig
import collector.config.HfConfig
import collector.config.RssConfig
import collector.config.RssFeed
import collector.config.ProductHuntConfig
import collector.config.RedditConfig
import collector.config.SourceConfig
import collector.config.SourcesFile
import collector.item
import collector.model.DropReason
import collector.model.Item
import collector.port.SeenStore
import collector.source.SourceAdapter
import collector.source.RssAdapter
import collector.source.HuggingFaceAdapter
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import collector.source.ProductHuntAdapter
import collector.source.RedditAdapter
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class PipelineTest {
    private class FakeAdapter(override val id: String, val block: suspend () -> List<Item>) : SourceAdapter {
        override suspend fun fetch(config: SourceConfig, now: Instant) = block()
    }

    private class MemorySeen(val seenKeys: Set<String> = emptySet()) : SeenStore {
        override suspend fun filterUnseen(items: List<Item>) = items.filter { it.key !in seenKeys }
        override suspend fun markSeen(items: List<Item>) {}
    }

    @Test
    fun rssDoesNotExposeFeedCredentialsInResultLinks() = runBlocking {
        MockWebServer().use { server ->
            val feedUrl = server.url("/feed?token=fake-feed-token&lang=en").newBuilder()
                .username("fake-feed-user").password("fake-feed-password").build()
            val link = feedUrl.toString().replace("&", "&amp;") + "&amp;ref=public"
            val reflected = "/before-fake-feed-token?ref=pre%66ake-feed-token-post#fake-feed-password"
            server.enqueue(MockResponse().setBody("""<rss><channel>
                <item><title>Useful fake-feed-token release</title><link>$reflected</link>
                    <description>before-fake-feed-user-after</description><author>by fake-feed-password</author>
                    <pubDate>$NOW</pubDate></item>
                <item><title>crypto fake-feed-token</title><link>$link</link>
                    <pubDate>$NOW</pubDate></item>
                </channel></rss>"""))
            val config = RssConfig(enabled = true, feeds = listOf(RssFeed("feed", feedUrl.toString(), "tech-signal")))
            val result = Pipeline(listOf(RssAdapter(OkHttpClient())), MemorySeen(), clock = { NOW })
                .run(SourcesFile(listOf(config), excludeKeywords = listOf("crypto")))

            assertEquals(server.url("/feed?lang=en&ref=public").toString(), result.dropped.single().item.url)
            assertEquals("Useful [redacted] release", result.items.single().title)
            val json = collector.source.Http.json.encodeToString(PipelineResult.serializer(), result)
            for (secret in listOf("fake-feed-token", "%66ake-feed-token", "fake-feed-user", "fake-feed-password")) {
                assertTrue(secret !in json)
            }
        }
    }

    @Test
    fun rssKeepsSuccessfulFeedWhenSourceDeadlineExpires() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(rssEntry("/article")))
            server.enqueue(MockResponse().setBody(rssEntry("/late")).setBodyDelay(3, TimeUnit.SECONDS))
            val config = RssConfig(enabled = true, feeds = listOf(
                RssFeed("working", server.url("/good").toString(), "tech-signal"),
                RssFeed("slow", server.url("/slow").toString(), "tech-signal")
            ))
            val slowCall = java.util.concurrent.atomic.AtomicReference<Call>()
            val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS)
                .eventListener(object : EventListener() {
                    override fun callStart(call: Call) {
                        if (call.request().url.encodedPath == "/slow") slowCall.set(call)
                    }
                }).build()
            val deadline = 1500.milliseconds
            val start = TimeSource.Monotonic.markNow()
            val result = Pipeline(listOf(RssAdapter(http)), MemorySeen(), clock = { NOW },
                sourceTimeout = deadline).run(SourcesFile(listOf(config)))
            val elapsed = start.elapsedNow()

            assertEquals("Useful release", result.items.single().title)
            assertTrue(result.report.failedSources.isEmpty())
            assertEquals(listOf("RSS 부분 실패 · 피드 수집 실패: slow (시간 초과)"), result.report.issues)
            val canceled = assertNotNull(slowCall.get())
            assertEquals(TimeUnit.SECONDS.toNanos(10), canceled.timeout().timeoutNanos())
            assertTrue(canceled.isCanceled())
            assertTrue(elapsed < deadline + 1.seconds, "RSS returned after $elapsed")
        }
    }

    private fun rssEntry(link: String) = """<rss><channel><item><title>Useful release</title>
        <link>$link</link><pubDate>$NOW</pubDate></item></channel></rss>"""

    @Test
    fun rssIsolatesFailuresAndCapsAfterFiltering() = runBlocking {
        MockWebServer().use { server ->
            val token = "fake-feed-token"
            val entries = listOf("crypto release", "Useful release", "Older release").mapIndexed { index, title ->
                """<item><title>$title</title><guid>$token-$index</guid><link>#$index</link>
                    <pubDate>${NOW.minusSeconds(index * 3600L)}</pubDate></item>"""
            }
            server.enqueue(MockResponse().setBody("<rss><channel>${entries.joinToString("")}</channel></rss>"))
            server.enqueue(MockResponse().setResponseCode(401).setBody(token))
            val adapter = RssAdapter(OkHttpClient())
            val config = RssConfig(enabled = true, feeds = listOf(
                RssFeed("working", server.url("/good?token=$token").toString(), "tech-signal", 1),
                RssFeed("broken", server.url("/bad?token=$token").toString(), "tech-signal")
            ))
            val pipeline = Pipeline(listOf(adapter), MemorySeen(), clock = { NOW })
            val result = pipeline.run(SourcesFile(listOf(config), excludeKeywords = listOf("crypto")))
            assertEquals("Useful release", result.items.single().title)
            assertEquals("working", result.items.single().extra["feed"])
            assertTrue(result.items.single().externalId.matches(Regex("working:[0-9a-f]{64}")))
            assertEquals(mapOf(DropReason.EXCLUDED_KEYWORD to 1, DropReason.OVER_QUOTA to 1), result.report.dropCounts)
            assertTrue(result.report.failedSources.isEmpty())
            assertEquals(listOf("RSS 부분 실패 · 피드 수집 실패: broken (HTTP 401)"), result.report.issues)
            assertTrue(token !in result.toString())
            assertEquals(2, server.requestCount)

            server.enqueue(MockResponse().setResponseCode(500).setBody(token))
            val failed = pipeline.run(SourcesFile(listOf(config.copy(feeds = listOf(config.feeds.last())))))
            assertTrue(failed.items.isEmpty())
            assertEquals("피드 수집 실패: broken (HTTP 500)", failed.report.failedSources.single().error)
            assertTrue(failed.report.issues.isEmpty())
        }
    }

    @Test
    fun huggingFaceKeepsFifteenOfEachKind() = runBlocking {
        MockWebServer().use { server ->
            for (kind in listOf("models", "spaces")) {
                val repos = (1..20).joinToString(",") { index ->
                    val score = index + if (kind == "models") 100 else 0
                    """{"id":"org/$kind-$index","createdAt":"$NOW","trendingScore":$score}"""
                }
                server.enqueue(MockResponse().setBody("[$repos]"))
            }
            val adapter = HuggingFaceAdapter(OkHttpClient(), server.url("/").toString())
            val result = Pipeline(listOf(adapter), MemorySeen(), clock = { NOW }).run(SourcesFile(listOf(HfConfig())))
            assertEquals(40, result.report.sources.single().fetched)
            assertEquals(listOf("models", "spaces").flatMap { kind -> (6..20).map { "$kind:org/$kind-$it" } }.toSet(),
                result.items.map { it.externalId }.toSet())
            assertEquals(10, result.report.dropCounts[DropReason.OVER_QUOTA])
        }
    }

    private val file = SourcesFile(listOf(HnConfig(minRawScore = 50), GitHubConfig(minRawScore = 200)), listOf("crypto"))

    @Test
    fun `인증 키가 없는 출처가 있어도 다른 출처를 수집한다`() = runTest {
        val http = OkHttpClient()
        val hn = FakeAdapter("hn") { listOf(item(rawScore = 100)) }
        val pipeline = Pipeline(listOf(hn, RedditAdapter(http, { null }), ProductHuntAdapter(http, { null })),
            MemorySeen(), clock = { NOW })
        val result = pipeline.run(SourcesFile(listOf(HnConfig(), RedditConfig(enabled = true),
            ProductHuntConfig(enabled = true))))

        assertEquals("hn", result.items.single().source)
        val failed = result.report.sources.filter { it.error.orEmpty().contains("키 없음") }
        assertEquals(setOf("reddit", "ph"), failed.map { it.source }.toSet())
    }

    @Test
    fun `수집부터 점수까지 돌고 탈락 이유와 깔때기를 남긴다`() = runTest {
        val hn = FakeAdapter("hn") {
            listOf(
                item(source = "hn", id = "1", url = "https://github.com/a/b", rawScore = 300),
                item(source = "hn", id = "2", rawScore = 10),                      // 점수 미달
                item(source = "hn", id = "3", title = "crypto bot", rawScore = 90), // 키워드
                item(source = "hn", id = "4", rawScore = 120),                     // 이미 봄
            )
        }
        val gh = FakeAdapter("github") { listOf(item(source = "github", id = "9", url = "https://github.com/a/b/", rawScore = 900)) }

        val result = Pipeline(listOf(hn, gh), MemorySeen(setOf("hn:4")), clock = { NOW }, zone = ZoneOffset.UTC).run(file)

        assertEquals(listOf("hn:1"), result.items.map { it.key })
        assertEquals(listOf("github"), result.items[0].alsoSeenIn)
        assertEquals(listOf("tech-signal", "demand-signal", "market-signal"), result.items[0].profileHint)
        assertEquals(
            mapOf(DropReason.LOW_SOURCE_SCORE to 1, DropReason.EXCLUDED_KEYWORD to 1, DropReason.DUPLICATE to 1, DropReason.ALREADY_SEEN to 1),
            result.report.dropCounts
        )
        assertEquals(listOf(5, 3, 1, 1), result.report.stages.map { it.output })
        assertEquals("2026-11-19", result.report.runDate)
    }

    @Test
    fun `이전에 본 대표가 새 글을 중복으로 탈락시키지 않는다`() = runTest {
        val hn = FakeAdapter("hn") {
            listOf(
                item(id = "1", title = "New local inference runtime", rawScore = 300),
                item(id = "2", title = "New local inference runtime", rawScore = 100)
            )
        }
        val result = Pipeline(listOf(hn), MemorySeen(setOf("hn:1")), clock = { NOW }, zone = ZoneOffset.UTC)
            .run(SourcesFile(listOf(HnConfig())))

        assertEquals(listOf("hn:2"), result.items.map { it.key })
        assertEquals(listOf(DropReason.ALREADY_SEEN), result.dropped.map { it.reason })
    }

    @Test
    fun `출처 하나가 실패하거나 느려도 나머지는 계속한다`() = runTest {
        val broken = FakeAdapter("hn") { error("boom") }
        val slow = FakeAdapter("github") { delay(10_000); emptyList() }
        val result = Pipeline(listOf(broken, slow), MemorySeen(), clock = { NOW }, sourceTimeout = 50.milliseconds).run(file)

        assertEquals("boom", result.report.sources.first { it.source == "hn" }.error)
        assertNotNull(result.report.sources.first { it.source == "github" }.error)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `꺼진 출처는 수집하지 않는다`() = runTest {
        val hn = FakeAdapter("hn") { listOf(item(source = "hn", id = "1")) }
        val off = SourcesFile(listOf(HnConfig(enabled = false), GitHubConfig()))
        val result = Pipeline(listOf(hn), MemorySeen(), clock = { NOW }).run(off)

        assertEquals(listOf("github"), result.report.sources.map { it.source })
        assertEquals("어댑터 없음", result.report.sources.single().error)
        assertNull(result.items.firstOrNull())
    }
}

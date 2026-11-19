package collector.source

import collector.NOW
import collector.config.*
import collector.fixture
import collector.stage.CommentFetcher
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class P3AdapterTest {
    private val server = MockWebServer()
    private val http = Http.client()
    private val base get() = server.url("/").toString()

    @BeforeTest fun start() = server.start()
    @AfterTest fun stop() = server.shutdown()

    private fun enqueue(body: String) = server.enqueue(MockResponse().setBody(body))

    @Test fun huggingFace() = runTest {
        enqueue(fixture("hf_models.json"))
        enqueue("""[{"id":"org/demo","createdAt":"2026-11-18T10:00:00Z","trendingScore":9999,"tags":["license:mit"]}]""")
        val items = HuggingFaceAdapter(http, base).fetch(HfConfig(), NOW)
        assertEquals(30, items.count { it.externalId.startsWith("models:") })
        assertTrue(items.any { it.extra["pipeline_tag"] != null })
        assertEquals("mit", items.single { it.externalId == "spaces:org/demo" }.extra["license"])
        for (kind in listOf("models", "spaces")) {
            val request = server.takeRequest()
            assertEquals("/api/$kind", request.requestUrl!!.encodedPath)
            val url = request.requestUrl!!
            assertEquals("trendingScore", url.queryParameter("sort"))
            assertEquals("-1", url.queryParameter("direction"))
            assertEquals("30", url.queryParameter("limit"))
            val fields = listOf("createdAt", "likes", "trendingScore", "tags")
            assertEquals(if (kind == "models") fields + "pipeline_tag" else fields, url.queryParameterValues("expand[]"))
            assertNull(request.getHeader("Authorization"))
        }
    }

    @Test fun redditAndComments() = runTest {
        // Listing 형태: https://www.reddit.com/dev/api/#GET_top 및 #GET_comments_{article}
        enqueue("""{"access_token":"mock-token","expires_in":3600}""")
        enqueue(fixture("reddit_top.json"))
        enqueue(fixture("reddit_comments.json"))
        enqueue(fixture("reddit_comments.json"))
        val reddit = RedditAdapter(http, { "mock" }, base, base)
        val item = reddit.fetch(RedditConfig(subreddits = listOf("LocalLLaMA")), NOW).single()
        assertEquals(listOf("tech-signal", "demand-signal"), item.profileHint)
        assertEquals("https://www.reddit.com/r/LocalLLaMA/comments/abc/demo/", item.discussionUrl)
        val comments = CommentFetcher(http, 1, reddit)
        assertEquals("Useful detail", comments.context(item)?.topComments?.first())
        assertEquals("Useful detail", comments.fetch(item).single().text)
        assertEquals(3, server.requestCount)
        val token = server.takeRequest()
        assertEquals("/api/v1/access_token", token.path)
        assertEquals("jvm:ai-idea-collector:0.1", token.getHeader("User-Agent"))
        assertTrue(token.getHeader("Authorization")!!.startsWith("Basic "))
        assertEquals("grant_type=client_credentials", token.body.readUtf8())
        val top = server.takeRequest()
        assertEquals("/r/LocalLLaMA/top", top.requestUrl!!.encodedPath)
        assertEquals("day", top.requestUrl!!.queryParameter("t"))
        assertEquals("jvm:ai-idea-collector:0.1", top.getHeader("User-Agent"))
        assertEquals("Bearer mock-token", top.getHeader("Authorization"))
        assertEquals("/comments/abc", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test fun redditCommentsAreRetriedAfterFailure() = runTest {
        enqueue("""{"access_token":"mock-token","expires_in":3600}""")
        enqueue(fixture("reddit_top.json"))
        server.enqueue(MockResponse().setResponseCode(500))
        enqueue(fixture("reddit_comments.json"))
        val reddit = RedditAdapter(http, { "mock" }, base, base)
        val item = reddit.fetch(RedditConfig(subreddits = listOf("LocalLLaMA")), NOW).single()
        val comments = CommentFetcher(http, 1, reddit)

        assertTrue(comments.fetch(item).isEmpty())
        assertEquals(3, server.requestCount)
        assertEquals("Useful detail", comments.fetch(item).single().text)
        assertEquals(4, server.requestCount)
        repeat(2) { server.takeRequest() }
        repeat(2) { assertEquals("/comments/abc", server.takeRequest().requestUrl!!.encodedPath) }
    }

    @Test fun productHunt() = runTest {
        // GraphQL 응답 형태: https://api-v2-docs.producthunt.com/query/posts/
        enqueue(fixture("product_hunt.json"))
        val items = ProductHuntAdapter(http, { "mock" }, base).fetch(ProductHuntConfig(), NOW)
        assertEquals(listOf("1", "2"), items.map { it.externalId })
        val item = items.first()
        assertEquals(70, item.rawScore)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("Bearer mock", request.getHeader("Authorization"))
        assertContains(request.body.readUtf8(), "artificial-intelligence")
    }

    @Test fun productHuntUsesRollingWindowAcrossMidnights() = runTest {
        for (time in listOf("00:05:00", "14:59:59", "15:00:01")) {
            val now = Instant.parse("2026-11-19T${time}Z")
            val since = now.minusSeconds(86400)
            val posts = listOf(since.minusSeconds(1), since, now, now.plusSeconds(1))
            val edges = posts.mapIndexed { i, date ->
                """{"node":{"id":"$i","name":"Launch","url":"https://example.com/$i","votesCount":70,"createdAt":"$date"}}"""
            }
            enqueue("""{"data":{"posts":{"edges":[${edges.joinToString()}]}}}""")
            val items = ProductHuntAdapter(http, { "mock" }, base).fetch(ProductHuntConfig(), now)
            assertEquals(listOf("1", "2"), items.map { it.externalId })
            assertContains(server.takeRequest().body.readUtf8(), """"since":"$since"""")
        }
    }

    @Test fun redditTokenErrorsOmitResponseBody() = runTest {
        for (status in listOf(401, 500)) {
            server.enqueue(MockResponse().setResponseCode(status)
                .setBody("""{"access_token":"fake-token","client_secret":"fake-secret"}"""))
            val error = assertFailsWith<java.io.IOException> {
                RedditAdapter(http, { "mock" }, base, base).fetch(RedditConfig(), NOW)
            }
            assertEquals("HTTP $status", error.message)
        }
    }

    @Test fun missingCredentials() = runTest {
        assertContains(assertFailsWith<MissingSourceSecret> {
            RedditAdapter(http, { null }, base, base).fetch(RedditConfig(), NOW)
        }.message.orEmpty(), "키 없음")
        assertContains(assertFailsWith<MissingSourceSecret> {
            ProductHuntAdapter(http, { " " }, base).fetch(ProductHuntConfig(), NOW)
        }.message.orEmpty(), "키 없음")
        assertEquals(0, server.requestCount)
    }

    @Test fun rssAndAtom() = runBlocking {
        enqueue(fixture("hf_blog.xml"))
        enqueue("""<feed xmlns="http://www.w3.org/2005/Atom"><entry><id>a</id><title>Atom</title>
            <link rel="self" href="https://example.org/api"/><link href="/article"/>
            <updated>2026-11-19T00:00:00Z</updated></entry></feed>""")
        val items = RssAdapter(http).fetch(RssConfig(feeds = listOf(
            RssFeed("rss", base, "tech-signal"),
            RssFeed("atom", base, "market-signal", 1)
        )), NOW)
        assertTrue(items.count { it.extra["feed"] == "rss" } > 10)
        assertEquals(1, items.count { it.extra["feed"] == "atom" })
        assertTrue(items.all { it.publishedAt in NOW.minusSeconds(7 * 86400)..NOW })
        assertEquals(listOf("tech-signal"), items.first().profileHint)
        assertEquals(server.url("/article").toString(), items.last().url)
    }

    @Test fun rssHonorsXmlDeclaredEncoding() = runBlocking {
        val xml = """<?xml version="1.0" encoding="ISO-8859-1"?>
            <rss><channel><item><title>Café</title><link>/article</link>
            <pubDate>Thu, 19 Nov 2026 00:00:00 GMT</pubDate></item></channel></rss>"""
        server.enqueue(MockResponse().setBody(okio.Buffer().write(xml.toByteArray(Charsets.ISO_8859_1))))
        val profiles = listOf("tech-signal", "market-signal")
        val config = RssConfig(feeds = listOf(RssFeed("rss", base, profiles)))
        val item = RssAdapter(http).fetch(config, NOW).single()
        assertEquals("Café", item.title)
        assertEquals(profiles, item.profileHint)
    }

    @Test fun rejectsExternalEntities() = runBlocking {
        enqueue("""<!DOCTYPE rss [<!ENTITY xxe SYSTEM "${base}secret">]>
            <rss version="2.0"><channel><item><title>&xxe;</title></item></channel></rss>""")
        assertFailsWith<RssFetchException> {
            RssAdapter(http).fetch(RssConfig(feeds = listOf(RssFeed("rss", base, "tech-signal"))), NOW)
        }
        assertEquals(1, server.requestCount)
    }
}

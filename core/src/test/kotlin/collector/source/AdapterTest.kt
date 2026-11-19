package collector.source

import collector.NOW
import collector.config.GitHubConfig
import collector.config.HnConfig
import collector.fixture
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 저장해 둔 실제 API 응답(fixtures)으로 어댑터를 검증한다. 네트워크 없이 돈다. */
class AdapterTest {
    private val server = MockWebServer()
    private val http = OkHttpClient()

    @BeforeTest fun start() = server.start()
    @AfterTest fun stop() = server.shutdown()

    @Test
    fun `HN — 응답을 Item으로 바꾸고, 원문 URL이 없으면 토론 페이지를 쓴다`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("hn_search.json")))
        val items = HackerNewsAdapter(http, server.url("/").toString())
            .fetch(HnConfig(queries = listOf("LLM"), minRawScore = 50, maxItems = 10), NOW)

        assertEquals(4, items.size)
        assertTrue(items.zipWithNext().all { (a, b) -> a.rawScore >= b.rawScore }, "점수 내림차순")
        val showHn = items.first { it.externalId == "50548660" }
        assertEquals("https://news.ycombinator.com/item?id=50548660", showHn.url)
        assertTrue(items.all { it.source == "hn" && it.discussionUrl != null && it.author != null })

        val request = server.takeRequest()
        assertEquals("/api/v1/search_by_date", request.requestUrl!!.encodedPath)
        assertEquals("story", request.requestUrl!!.queryParameter("tags"))
        assertTrue(request.requestUrl!!.queryParameter("numericFilters")!!.contains("points>=50"))
    }

    @Test
    fun `HN — 검색어가 여러 개면 겹치는 항목은 하나만`() = runTest {
        repeat(2) { server.enqueue(MockResponse().setBody(fixture("hn_search.json"))) }
        val items = HackerNewsAdapter(http, server.url("/").toString())
            .fetch(HnConfig(queries = listOf("Ask HN", "LLM"), maxItems = 10), NOW)
        assertEquals(4, items.size)
        assertTrue(items.all { it.extra["matchedQuery"] == "Ask HN" })
        val demand = server.takeRequest().requestUrl!!
        val regular = server.takeRequest().requestUrl!!
        assertEquals("created_at_i>${NOW.epochSecond - 72 * 3600},points>=5", demand.queryParameter("numericFilters"))
        assertEquals("created_at_i>${NOW.epochSecond - 24 * 3600},points>=20", regular.queryParameter("numericFilters"))
    }

    @Test
    fun `GitHub — 스타·라이선스·토픽을 담고 검색 조건을 붙인다`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("github_search.json")))
        val items = GitHubAdapter(http, { null }, server.url("/").toString())
            .fetch(GitHubConfig(queries = listOf("topic:llm"), createdWithinDays = 14, minRawScore = 200), NOW)

        assertEquals(3, items.size)
        val first = items.first()
        assertEquals("github", first.source)
        assertTrue(first.rawScore >= 200)
        assertEquals("MIT", first.extra["license"])
        assertTrue(items.any { it.extra["license"] == "AGPL-3.0" })

        val q = server.takeRequest().requestUrl!!.queryParameter("q")!!
        assertEquals("topic:llm created:>=2026-11-05 stars:>=200", q)
    }

    @Test
    fun `GitHub — 토큰이 있으면 Authorization 헤더를 보낸다`() = runTest {
        server.enqueue(MockResponse().setBody("""{"items":[]}"""))
        GitHubAdapter(http, { if (it == "GITHUB_TOKEN") "t0k" else null }, server.url("/").toString())
            .fetch(GitHubConfig(queries = listOf("topic:llm")), NOW)
        assertEquals("Bearer t0k", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `실패 응답은 예외로 알린다`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("rate limited"))
        assertFailsWith<java.io.IOException> {
            GitHubAdapter(http, { null }, server.url("/").toString()).fetch(GitHubConfig(queries = listOf("x")), NOW)
        }
    }
}

package collector.source

import collector.config.GitHubConfig
import collector.config.HnConfig
import collector.config.SourcesFile
import collector.dev.JsonFileSeenStore
import collector.pipeline.Pipeline
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.*

class BackfillAdapterTest {
    @TempDir lateinit var out: File
    private val day = LocalDate.parse("2026-11-18")

    @Test fun hnUsesUtcDayAndQueryScore() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            HackerNewsAdapter(OkHttpClient(), server.url("/").toString())
                .fetchDay(HnConfig(queries = listOf("Ask HN"), fetchPerQuery = 12), day)
            val query = server.takeRequest().requestUrl!!
            val start = day.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
            assertEquals("created_at_i>${start - 1},created_at_i<${start + 86400},points>=5",
                query.queryParameter("numericFilters"))
            assertEquals("12", query.queryParameter("hitsPerPage"))
        }
    }

    @Test fun githubTokenSuppressesWarningAndAuthenticatesIncompleteResultRetry() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"incomplete_results":true}"""))
            server.enqueue(MockResponse().setBody("""{"incomplete_results":false}"""))
            var now = Instant.ofEpochSecond(100)
            val waits = mutableListOf<Long>()
            val warnings = mutableListOf<String>()
            val adapter = GitHubAdapter(OkHttpClient(), { "test-token" }, server.url("/").toString())
            val search = adapter.historicalSearch({ now }, { millis ->
                waits += millis
                now = now.plusMillis(millis)
            }, warnings::add)
            search.fetch(GitHubConfig(queries = listOf("one")), day)
            assertTrue(warnings.isEmpty())
            assertEquals(listOf(2000L), waits)
            assertEquals(2, server.requestCount)
            repeat(2) { assertEquals("Bearer test-token", server.takeRequest().getHeader("Authorization")) }
        }
    }

    @Test fun dailyGitHubKeepsPartialItemsAndRecordsOneWarning() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) {
                server.enqueue(MockResponse().setBody("""{"incomplete_results":true,"items":[
                    {"id":1,"full_name":"owner/repo","html_url":"https://github.com/owner/repo",
                     "stargazers_count":250,"created_at":"2026-11-18T00:00:00Z"}
                ]}"""))
            }
            val adapter = GitHubAdapter(OkHttpClient(), { null }, server.url("/").toString())
            val pipeline = Pipeline(listOf(adapter), JsonFileSeenStore(out.resolve("seen.json")),
                clock = { day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC) })
            val result = pipeline.run(SourcesFile(listOf(GitHubConfig(queries = listOf("one", "two")))))
            assertEquals(listOf("1"), result.items.map { it.externalId },
                "sources=${result.report.sources}, dropped=${result.dropped}")
            assertContains(result.report.issues.single(), "GitHub")
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun githubWaitsForResetAndRetriesRateLimit() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}").setHeader("X-RateLimit-Remaining", 0)
                .setHeader("X-RateLimit-Reset", 110))
            server.enqueue(MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", 0)
                .setHeader("X-RateLimit-Reset", 125))
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", 9))
            server.enqueue(MockResponse().setBody("{}"))
            var now = Instant.ofEpochSecond(100)
            val waits = mutableListOf<Long>()
            val warnings = mutableListOf<String>()
            val adapter = GitHubAdapter(OkHttpClient(), { null }, server.url("/").toString())
            val search = adapter.historicalSearch({ now }, { millis ->
                waits += millis
                now = now.plusMillis(millis)
            }, warnings::add)
            search.fetch(GitHubConfig(queries = listOf("one", "two")), day)
            assertEquals(listOf(10000L, 15000L, 9000L), waits)
            assertEquals(1, warnings.size)
            assertEquals("GITHUB_TOKEN 없음 · GitHub 과거 검색 요청 간격을 늘립니다.", warnings.single())
            assertNull(server.takeRequest().apply {
                assertTrue(requestUrl!!.queryParameter("q")!!.contains("created:$day "))
            }.getHeader("Authorization"))
            val limitedQuery = server.takeRequest().path
            repeat(2) { assertEquals(limitedQuery, server.takeRequest().path) }
        }
    }
}

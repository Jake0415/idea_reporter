package collector.source

import collector.config.ConfigLoader
import collector.config.GitHubConfig
import collector.config.GitHubIssuesConfig
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.seconds
import collector.config.SourcesFile
import collector.pipeline.Pipeline
import collector.dev.JsonFileSeenStore
import kotlin.test.*

class GitHubIssuesAdapterTest {
    private val now = Instant.parse("2026-11-20T12:00:00Z")
    private val directory = createTempDirectory().toFile()
    @AfterTest fun cleanup() { directory.deleteRecursively() }
    private val defaults = GitHubIssuesConfig()

    @Test fun issuesKeepOnlyHumanQualifyingPostsAndFetchTopComments() = runBlocking {
        MockWebServer().use { server ->
            val body = "Hi @octocat\n```kt\nprivate code\n```\n" + "x".repeat(3100)
            val items = listOf(issue(1, 10, body = body), issue(2, 6), issue(3, 1), issue(6, 0).replace("\"comments\":2", "\"comments\":10"),
                issue(4, 20, ""","pull_request":{}"""),
                issue(5, 20, ""","user":{"login":"auto[bot]"}"""))
            server.enqueue(MockResponse().setBody("""{"items":[${items.joinToString()}],"total_count":42,"incomplete_results":true}"""))
            server.enqueue(repository())
            server.enqueue(MockResponse().setBody("""{"items":[${issue(7, 0)},${issue(8, 0)}]}"""))
            server.enqueue(MockResponse().setBody("""[
                {"body":"excluded","user":{"type":"Bot"}},
                {"body":"first","reactions":{"total_count":1}},
                {"body":"second @someone","reactions":{"total_count":5}}]"""))
            val (results, warning) = adapter(server).fetchWithWarning(
                defaults.copy(queries = listOf(defaults.queries.first()), commentItems = 1), now.plusMillis(987))
            assertEquals(listOf(22, 14), results.map { it.rawScore })
            val top = results.first()
            assertNull(top.author)
            assertEquals(listOf("enhancement"), top.tags)
            assertEquals("owner/repo", top.extra["repository"])
            assertEquals(3000, top.extra.getValue("body").length)
            assertContains(top.snippet, "@user")
            assertFalse("private code" in top.snippet)
            assertEquals(listOf("second @user", "first"), top.comments.map { it.text })
            assertTrue(top.comments.all { it.author == "익명" })
            assertContains(warning.orEmpty(), "부분 결과")
            assertContains(warning.orEmpty(), "GITHUB_TOKEN")
            val request = server.takeRequest()
            assertEquals("is:issue ollama in:title reactions:>=1 label:enhancement,feature,\"feature request\",\"type: feature\",\"kind/feature\",proposal,idea is:public created:>=2026-11-13T12:00:00Z comments:>=2",
                request.requestUrl!!.queryParameter("q"))
            assertContains(request.path.orEmpty(), "%22feature%20request%22")
            assertEquals(defaults.queries.first(), top.extra["matchedQuery"])
            assertEquals("/repos/owner/repo", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals("reactions", request.requestUrl!!.queryParameter("sort"))
            assertEquals("30", request.requestUrl!!.queryParameter("per_page"))
            val intent = server.takeRequest().requestUrl!!
            assertEquals("true", intent.queryParameter("advanced_search"))
            assertEquals("""is:issue AND is:public AND ollama AND in:title AND (support OR "add support" OR "feature request" OR "please add" OR "is there a way" OR integration) AND reactions:>=1 AND created:>=2026-11-13T12:00:00Z AND comments:>=2""",
                intent.queryParameter("q"))
            val funnels = Json.parseToJsonElement(directory.resolve("github-issues-2026-11-20.json").readText()).jsonArray
            val funnel = funnels.first().jsonObject
            for ((key, expected) in mapOf("total_count" to 42, "received" to 6, "afterPrAndBot" to 4,
                "afterPositiveReactions" to 3, "afterScore" to 2, "afterExcludedLabels" to 2,
                "afterStars" to 2, "starsUnverified" to 0, "afterOwnerMemo" to 2, "afterAmbiguity" to 2, "finalCount" to 2)) {
                assertEquals(expected, funnel.getValue(key).jsonPrimitive.int, key)
            }
            val rejected = funnel.getValue("rejected").jsonArray
            assertEquals(4, rejected.size)
            assertTrue(rejected.all { it.jsonObject.keys == setOf("title", "url") })
            assertFalse("auto[bot]" in funnel.toString())
            assertEquals(5, funnels.sumOf { it.jsonObject.getValue("rejected").jsonArray.size })
            assertContains(warning.orEmpty(), "받음 8 → 최종 2")
            val comments = server.takeRequest().requestUrl!!
            assertEquals("/repos/owner/repo/issues/1/comments", comments.encodedPath)
            assertEquals("10", comments.queryParameter("per_page"))
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun tokenEnablesDiscussionSearchAndTopDiscussionReplies() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"items":[${issue(1, 4)}]}"""))
            server.enqueue(repository())
            server.enqueue(emptyIssues())
            val discussion = """{"id":"discussion","title":"Request","url":"https://github.com/owner/repo/discussions/1",
                "createdAt":"2026-11-20T00:00:00Z","body":"Hello @person","upvoteCount":5,
                "reactions":{"totalCount":4},"comments":{"totalCount":3},
                "author":{"login":"person","avatarUrl":"https://example.com/avatar"},
                "category":{"name":"Ideas"},"repository":{"nameWithOwner":"owner/repo"}}"""
            val bot = discussion.replace("\"id\":\"discussion\"", "\"id\":\"bot\"")
                .replace("\"login\":\"person\"", "\"__typename\":\"Bot\"")
            val otherCategory = discussion.replace("\"id\":\"discussion\"", "\"id\":\"other\"")
                .replace("\"Ideas\"", "\"General\"")
            server.enqueue(MockResponse().setBody("""{"data":{"search":{"nodes":[$discussion,$bot,$otherCategory]}}}"""))
            server.enqueue(MockResponse().setBody("""{"errors":[{}],"data":{"node":{"comments":{"nodes":[
                {"body":"bot","author":{"__typename":"Bot"}},
                {"body":"Reply @person","upvoteCount":3,"reactions":{"total_count":2}},
                {"body":"Reaction only","reactions":{"total_count":4}}]}}}}"""))
            val (items, warning) = adapter(server, "fake-token").fetchWithWarning(
                defaults.copy(queries = defaults.queries.take(1), commentItems = 1), now)
            assertContains(warning.orEmpty(), "일부 댓글")
            assertEquals(listOf("discussion:discussion", "1"), items.map { it.externalId })
            assertEquals(13, items.first().rawScore)
            assertEquals("50", items.first().extra["stars"])
            assertEquals("Hello @user", items.first().extra["body"])
            assertEquals(listOf("Reply @user", "Reaction only"), items.first().comments.map { it.text })
            assertNull(items.first().author)
            server.takeRequest()
            assertEquals("/repos/owner/repo", server.takeRequest().path)
            server.takeRequest()
            val graphql = server.takeRequest()
            assertEquals("/graphql", graphql.path)
            assertEquals("POST", graphql.method)
            assertTrue(graphql.getHeader("Authorization") == "Bearer fake-token")
            val payload = Json.parseToJsonElement(graphql.body.readUtf8()).jsonObject
            assertContains(payload.getValue("query").jsonPrimitive.content, "type: DISCUSSION")
            assertEquals("ollama in:title is:public created:>=2026-11-13T12:00:00Z comments:>=2",
                payload.getValue("variables").jsonObject.getValue("query").jsonPrimitive.content)
            assertContains(server.takeRequest().body.readUtf8(), "discussion")
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun unauthenticatedRepositorySearchFinishesBeforeNineIssueSearches() = runBlocking {
        MockWebServer().use { server ->
            val http = OkHttpClient()
            var clock = now
            GitHubSearch.shared(http) { GitHubSearch({ clock }, { clock = clock.plusMillis(it) }) }
            repeat(12) { server.enqueue(MockResponse().setBody("""{"items":[]}""")) }
            val pipeline = Pipeline(listOf(adapter(server, http = http),
                GitHubAdapter(http, { null }, server.url("/").toString())),
                JsonFileSeenStore(directory.resolve("seen.json")), clock = { now })
            val result = pipeline.run(SourcesFile(listOf(
                defaults.copy(queries = defaults.queries.take(6)), GitHubConfig())))
            assertTrue(result.report.failedSources.isEmpty())
            assertTrue(clock < now.plusSeconds(45))
            assertEquals(now, clock)
            repeat(3) { assertEquals("/search/repositories", server.takeRequest().requestUrl!!.encodedPath) }
            repeat(9) { assertEquals("/search/issues", server.takeRequest().requestUrl!!.encodedPath) }
        }
    }

    @Test fun longRetryAfterPreservesRepositoriesWithoutMakingDailySearchWait() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"items":[{"id":1,"full_name":"owner/repo",
                "html_url":"https://github.com/owner/repo","stargazers_count":250,"created_at":"2026-11-20T00:00:00Z"}]}"""))
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
            val http = OkHttpClient()
            val waits = mutableListOf<Long>()
            GitHubSearch.shared(http) { GitHubSearch({ now }, { waits += it; awaitCancellation() }) }
            val (items, warning) = GitHubAdapter(http, { null }, server.url("/").toString())
                .fetchWithWarning(GitHubConfig(), now, 1.seconds)
            assertEquals(listOf("1"), items.map { it.externalId })
            assertContains(warning.orEmpty(), "부분 결과")
            assertEquals(2, server.requestCount)
            assertTrue(waits.isEmpty())
        }
    }

    @Test fun repositoryFloorCacheAndQueryFailureAreIndependent() = runBlocking {
        MockWebServer().use { server ->
            val adapter = adapter(server)
            val config = defaults.copy(queries = listOf("first", "second"), includeDiscussions = false, commentItems = 0)
            server.enqueue(MockResponse().setResponseCode(500))
            val valid = issue(2, 6).replace("owner/repo", "owner/valid")
            server.enqueue(MockResponse().setBody("""{"items":[${issue(1, 8)},$valid]}"""))
            server.enqueue(repository(19))
            server.enqueue(repository())
            repeat(2) { server.enqueue(emptyIssues()) }
            val (items, warning) = adapter.fetchWithWarning(config, now)
            assertEquals(listOf("2"), items.map { it.externalId })
            assertContains(warning.orEmpty(), "검색어 1번 부분 실패")
            assertEquals(6, server.requestCount)
            server.enqueue(MockResponse().setBody("""{"items":[${issue(1, 8)}]}"""))
            server.enqueue(emptyIssues())
            assertTrue(adapter.fetch(config.copy(queries = listOf("first")), now).isEmpty())
            assertEquals(8, server.requestCount)
            val cache = directory.resolve("github-repos.json").readText()
            assertFalse("login" in cache)
            assertEquals(604800L, Json.parseToJsonElement(cache).jsonObject.getValue("owner/repo")
                .jsonObject.getValue("expiresAt").jsonPrimitive.long - now.epochSecond)
        }
    }

    @Test fun lowStarOwnerNeedsExternalPositiveReactionsAndExcludedLabelsAreSkipped() = runBlocking {
        MockWebServer().use { server ->
            val owner = ""","user":{"login":"OWNER","type":"User"}"""
            val items = listOf(issue(1, 3, owner), issue(2, 3, owner), issue(3, 3),
                issue(4, 8).replace("enhancement", "Fabrik:queued"))
            server.enqueue(MockResponse().setBody("""{"items":[${items.joinToString()}]}"""))
            server.enqueue(repository(10))
            val reactions = """[{"content":"+1","user":{"login":"owner"}},
                {"content":"heart","user":{"login":"someone"}},
                {"content":"hooray","user":{"login":"other"}},
                {"content":"rocket","user":{"login":"third"}},
                {"content":"heart","user":{"login":"automation[bot]"}}]"""
            server.enqueue(MockResponse().setBody(reactions))
            server.enqueue(MockResponse().setBody(reactions.replace("rocket", "-1")))
            server.enqueue(emptyIssues())
            val results = adapter(server).fetch(defaults.copy(queries = listOf("llm"), commentItems = 0), now)
            assertEquals(listOf("1"), results.map { it.externalId })
            assertEquals("10", results.single().extra["stars"])
            assertNull(results.single().author)
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun timedOutIssueSearchReturnsPartialResultsWithConfiguredDeadline() = runBlocking {
        MockWebServer().use { server ->
            directory.resolve("github-repos.json").writeText("""
                {"owner/low":{"expiresAt":${now.epochSecond + 3600},"repository":{"stargazers_count":10,"owner":{"type":"User"}}},
                 "owner/high":{"expiresAt":${now.epochSecond + 3600},"repository":{"stargazers_count":50}}}
            """.trimIndent())
            val items = listOf(issue(1, 8), issue(2, 7).replace("owner/repo", "owner/slow"),
                issue(3, 9, ""","user":{"login":"owner"}""").replace("owner/repo", "owner/low"),
                issue(4, 6).replace("owner/repo", "owner/high"))
            server.enqueue(MockResponse().setBody("""{"items":[${items.joinToString()}]}"""))
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val pipeline = Pipeline(listOf(adapter(server)), JsonFileSeenStore(directory.resolve("seen.json")),
                clock = { now }, sourceTimeout = 30.seconds)
            val result = pipeline.run(SourcesFile(listOf(defaults.copy(
                queries = listOf("llm", "mcp"), commentItems = 0, sourceTimeoutSeconds = 1))))
            assertEquals(listOf("2", "4"), result.items.map { it.externalId })
            assertEquals("50", result.items.last().extra["stars"])
            assertTrue(result.report.issues.any { "시간 초과" in it && "스타 하한 판정 보류 1건" in it })
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun defaultSearchUsesBothIssueRoutesPerTermAndRotatesThreeDiscussionsDaily() = runBlocking {
        MockWebServer().use { server ->
            val adapter = adapter(server, "fake-token")
            val dailyTerms = (0L..3L).map { day ->
                repeat(22) { server.enqueue(MockResponse().setBody("""{"items":[]}""")) }
                repeat(3) { server.enqueue(MockResponse().setBody("""{"data":{"search":{"nodes":[]}}}""")) }
                val (items, warning) = adapter.fetchWithWarning(defaults, now.plusSeconds(day * 86400))
                assertTrue(items.isEmpty())
                assertContains(warning.orEmpty(), "받음 0 → 최종 0")
                for (term in defaults.queries) {
                    val query = server.takeRequest().requestUrl!!.queryParameter("q").orEmpty()
                    assertTrue(query.startsWith("is:issue $term in:title "))
                    assertContains(query, "reactions:>=1 label:")
                }
                for (term in defaults.queries) {
                    val url = server.takeRequest().requestUrl!!
                    assertEquals("true", url.queryParameter("advanced_search"))
                    assertTrue(url.queryParameter("q").orEmpty().startsWith("is:issue AND is:public AND $term AND in:title AND ("))
                }
                List(3) {
                    val request = server.takeRequest()
                    assertEquals("/graphql", request.path)
                    val query = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        .getValue("variables").jsonObject.getValue("query").jsonPrimitive.content
                    defaults.queries.single { term ->
                        val intent = defaults.titleIntents[term]
                        val title = if (intent == null) term else "$term $intent"
                        query.startsWith("$title in:title ")
                    }
                }
            }
            assertEquals(defaults.queries.toSet(), dailyTerms.flatten().toSet())
            assertEquals(4, dailyTerms.distinct().size)
            assertEquals(100, server.requestCount)
        }
    }

    @Test fun ambiguousTermsRequireAiContextFromTopicsOrDescription() = runBlocking {
        MockWebServer().use { server ->
            val items = listOf(issue(1, 8), issue(2, 7).replace("owner/repo", "owner/description"),
                issue(3, 6).replace("owner/repo", "owner/unrelated"))
            server.enqueue(MockResponse().setBody("""{"items":[${items.joinToString()}]}"""))
            server.enqueue(MockResponse().setBody("""{"stargazers_count":50,"topics":["generative-ai"]}"""))
            server.enqueue(MockResponse().setBody("""{"stargazers_count":50,"description":"LLM by @person ${"x".repeat(210)}"}"""))
            server.enqueue(MockResponse().setBody("""{"stargazers_count":50,"description":"Chair models"}"""))
            server.enqueue(emptyIssues())
            val config = defaults.copy(queries = listOf("\"embedding model\""), commentItems = 0, includeDiscussions = false)
            assertEquals(listOf("1", "2"), adapter(server).fetch(config, now).map { it.externalId })
            val cache = Json.parseToJsonElement(directory.resolve("github-repos.json").readText()).jsonObject
            val description = cache.getValue("owner/description").jsonObject.getValue("repository").jsonObject
                .getValue("description").jsonPrimitive.content
            assertTrue(description.length <= 200)
            assertContains(description, "@user")
            assertFalse("@person" in description)
        }
    }

    @Test fun firstSearchTimeoutOrErrorMarksBothSourcesFailed() = runBlocking {
        val sources = listOf(GitHubConfig(queries = listOf("ollama")),
            defaults.copy(queries = listOf("ollama"), sourceTimeoutSeconds = 1))
        for (source in sources) for (timesOut in listOf(true, false)) {
            MockWebServer().use { server ->
                val response = if (timesOut) MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
                    else MockResponse().setResponseCode(500)
                server.enqueue(response)
                if (!timesOut && source is GitHubIssuesConfig) server.enqueue(response)
                val http = OkHttpClient()
                val pipeline = Pipeline(listOf(adapter(server, http = http),
                    GitHubAdapter(http, { null }, server.url("/").toString())),
                    JsonFileSeenStore(directory.resolve("seen.json")), clock = { now }, sourceTimeout = 1.seconds)
                val report = pipeline.run(SourcesFile(listOf(source))).report
                val error = report.failedSources.single().error.orEmpty()
                if (timesOut) assertContains(error, "시간 초과") else assertTrue(error.isNotBlank())
            }
        }
    }

    @Test fun loadsEnabledDefaults() {
        val config = ConfigLoader.loadDefault().sources.filterIsInstance<GitHubIssuesConfig>().single()
        assertEquals(defaults, config)
        assertEquals(90, config.sourceTimeoutSeconds)
        assertEquals(20, config.minRepositoryStars)
        assertEquals(1, config.minPositiveReactions)
        val overridden = ConfigLoader.parse("""
            {"sources":[{"id":"github-issues","titleSignalWords":["custom signal"]}]}
        """.trimIndent()).sources.filterIsInstance<GitHubIssuesConfig>().single()
        assertEquals(listOf("custom signal"), overridden.titleSignalWords)
    }

    @Test fun issueBudgetCapsAndRotatesRoutesByToken() = runBlocking {
        val terms = List(13) { "term$it" }
        val config = defaults.copy(queries = terms, includeDiscussions = false)
        for ((token, budget) in listOf(null to 9, "fake-token" to 24)) {
            MockWebServer().use { server ->
                val adapter = adapter(server, token)
                val dailyQueries = (0L..2L).map { day ->
                    repeat(budget) { server.enqueue(emptyIssues()) }
                    adapter.fetch(config, now.plusSeconds(day * 86400))
                    List(budget) { server.takeRequest().requestUrl!!.queryParameter("q").orEmpty() }
                }
                assertEquals(3 * budget, server.requestCount)
                assertTrue(dailyQueries.flatten().any { "label:" in it })
                assertTrue(dailyQueries.flatten().any { " AND (" in it })
                assertTrue(dailyQueries[0] != dailyQueries[1])
            }
        }
    }

    @Test fun titleIntentRequiresBothSearchPhraseAndSignalInTheTitle() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(emptyIssues())
            val titles = listOf("Support for LLM", "LLM response FAILED", "Support for LLMs",
                "LLM supportable", "LLM: please add export", "LLM: please also add export", "Disclose AI/LLM usage")
            val issues = titles.mapIndexed { id, title -> issue(id, 4).replace("Request $id", title) }
            server.enqueue(MockResponse().setBody("""{"items":[${issues.joinToString()}]}"""))
            server.enqueue(repository())
            val (results, warning) = adapter(server).fetchWithWarning(defaults.copy(
                queries = listOf("llm"), ambiguousTerms = emptyList(), includeDiscussions = false, commentItems = 0), now)
            assertEquals(listOf("0", "1", "4"), results.map { it.externalId })
            assertContains(warning.orEmpty(), "요청·불편 표현 없음")
            val funnels = Json.parseToJsonElement(directory.resolve("github-issues-2026-11-20.json").readText()).jsonArray
            assertEquals(4, funnels.last().jsonObject.getValue("dropped").jsonObject.getValue("요청·불편 표현 없음").jsonPrimitive.int)
        }
    }

    @Test fun immediateRepositoryHttpErrorsApplyTheStarFloor() = runBlocking {
        MockWebServer().use { server ->
            val issues = listOf(404, 422, 500).map { issue(it, 4).replace("owner/repo", "owner/repo$it") }
            server.enqueue(MockResponse().setBody("""{"items":[${issues.joinToString()}]}"""))
            for (status in listOf(404, 422, 500)) server.enqueue(MockResponse().setResponseCode(status))
            server.enqueue(emptyIssues())
            val (results, warning) = adapter(server).fetchWithWarning(defaults.copy(
                queries = listOf("llm"), includeDiscussions = false, commentItems = 0), now)
            assertTrue(results.isEmpty())
            assertContains(warning.orEmpty(), "가장 많이 뺀 단계: 스타 하한")
            assertFalse("판정 보류" in warning.orEmpty())
            assertFalse(directory.resolve("github-repos.json").exists())
        }
    }

    @Test fun excludesInternalTrackingTitlesAndLabels() = runBlocking {
        MockWebServer().use { server ->
            val titles = listOf("TRACK LLM work", "LLM Follow-up", "LLM Tracking Issue", "LLM epic",
                "LLM Meta Issue", "LLM ROADMAP", "LLM epicenter support")
            val issues = titles.mapIndexed { id, title -> issue(id, 4).replace("Request $id", title) } +
                listOf("Tracking", "epic", "meta").mapIndexed { id, label -> issue(id + 7, 4).replace("enhancement", label) }
            server.enqueue(MockResponse().setBody("""{"items":[${issues.joinToString()}]}"""))
            server.enqueue(repository())
            server.enqueue(emptyIssues())
            val results = adapter(server).fetch(defaults.copy(
                queries = listOf("llm"), includeDiscussions = false, commentItems = 0), now)
            assertEquals(listOf("6"), results.map { it.externalId })
        }
    }

    @Test fun privateRepositoriesAreNeverKeptOrSampled() = runBlocking {
        MockWebServer().use { server ->
            val secret = issue(1, 4).replace("Request 1", "LLM secret plan support").replace("owner/repo", "owner/hidden")
            server.enqueue(MockResponse().setBody("""{"items":[$secret]}"""))
            server.enqueue(MockResponse().setBody("""{"stargazers_count":500,"private":true,"owner":{"type":"User"}}"""))
            server.enqueue(emptyIssues())
            val privateDiscussion = """{"id":"p","title":"LLM private idea support","url":"https://github.com/owner/hidden/discussions/1",
                "createdAt":"2026-11-20T00:00:00Z","upvoteCount":5,"reactions":{"totalCount":4},"comments":{"totalCount":3},
                "category":{"name":"Ideas"},"repository":{"nameWithOwner":"owner/hidden","isPrivate":true}}"""
            server.enqueue(MockResponse().setBody("""{"data":{"search":{"nodes":[$privateDiscussion]}}}"""))
            val paths = collector.runtime.CollectorPaths(directory.resolve("out"), directory.resolve("config"))
            val cache = paths.cacheDir.resolve("github-repos.json")
            val adapter = GitHubIssuesAdapter(OkHttpClient(), { "fake-token" }, server.url("/").toString(),
                cache, paths.debugDir)
            val (results, warning) = adapter.fetchWithWarning(defaults.copy(
                queries = listOf("llm"), commentItems = 0), now)
            assertTrue(cache.isFile)
            assertFalse(directory.resolve("github-repos.json").exists())
            assertFalse(directory.resolve("github-issues-2026-11-20.json").exists())
            assertTrue(results.isEmpty())
            assertContains(warning.orEmpty(), "비공개 저장소 2건 제외")
            // Private titles must not leak into the debug funnel samples either.
            val debug = paths.debugDir.resolve("github-issues-2026-11-20.json").readText()
            assertFalse("secret plan" in debug)
            assertFalse("private idea" in debug)
            val graphql = (1..server.requestCount).map { server.takeRequest() }.single { it.path == "/graphql" }
            assertContains(graphql.body.readUtf8(), "isPrivate")
        }
    }

    private fun emptyIssues() = MockResponse().setBody("""{"items":[]}""")

    private fun adapter(server: MockWebServer, token: String? = null, http: OkHttpClient = OkHttpClient()) =
        GitHubIssuesAdapter(http, { token }, server.url("/").toString(), directory.resolve("github-repos.json"), directory)

    private fun repository(stars: Int = 50) = MockResponse().setBody(
        """{"stargazers_count":$stars,"owner":{"type":"User","login":"owner"}}""")

    private fun issue(id: Int, reactions: Int, extra: String = "", body: String = "") =
        """{"id":$id,"title":"Request $id","html_url":"https://github.com/owner/repo/issues/$id",
            "created_at":"2026-11-20T00:00:00Z","number":$id,"comments":2,
            "body":${kotlinx.serialization.json.JsonPrimitive(body)},
            "reactions":{"+1":$reactions},"labels":[{"name":"enhancement"}]$extra}"""
}

package collector.source

import collector.config.GitHubConfig
import collector.config.SourceConfig
import collector.model.Item
import collector.port.SecretProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * GitHub — 저장소 검색 API. 최근 [createdWithinDays]일 안에 만들어지고 스타가 기준 이상인 저장소를 스타순으로.
 * 토큰(GITHUB_TOKEN)은 선택. 없으면 검색 API 호출 한도가 낮다.
 * https://docs.github.com/rest/search/search#search-repositories
 */
class GitHubAdapter(
    private val http: OkHttpClient,
    private val secrets: SecretProvider,
    private val baseUrl: String = "https://api.github.com/"
) : SourceAdapter {
    override val id = "github"

    override suspend fun fetch(config: SourceConfig, now: Instant): List<Item> =
        fetchWithWarning(config, now).first

    internal suspend fun fetchTracking(config: SourceConfig, now: Instant): List<Item> =
        fetchWithWarning(config, now, priority = GitHubSearch.Priority.TRACKING).first

    internal suspend fun fetchWithWarning(
        config: SourceConfig, now: Instant, timeout: Duration = Duration.INFINITE,
        priority: GitHubSearch.Priority = GitHubSearch.Priority.DAILY_REPOSITORIES
    ): Pair<List<Item>, String?> {
        require(config is GitHubConfig) { "GitHubAdapter에는 GitHubConfig가 필요합니다" }
        val sinceDate = now.minus(config.window()).atOffset(ZoneOffset.UTC).toLocalDate()
        val warnings = linkedSetOf<String>()
        val token = token()
        val repos = linkedMapOf<Long, Repo>()
        suspend fun collect() {
            search(config, ">=$sinceDate", token, repos) {
                try {
                    GitHubSearch.shared(http).get(http, it, token, retryLimited = false, priority = priority) { body, _ -> decode(body) }.also { response ->
                        if (response.incompleteResults) warnings += "GitHub 검색 응답에 부분 결과가 포함되었습니다."
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (repos.isEmpty() || e is HttpStatusException && e.status in setOf(403, 429)) throw e
                    warnings += "GitHub 저장소 검색 일부 실패"
                    Response()
                }
            }
        }
        try {
            if (timeout.isInfinite()) {
                collect()
            } else if (withTimeoutOrNull(timeout) { collect() } == null) {
                if (repos.isEmpty()) throw GitHubCollectionException("GitHub 저장소 수집 시간 초과")
                warnings += "GitHub 저장소 수집 시간 초과로 부분 결과를 사용합니다."
            }
        } catch (e: HttpStatusException) {
            if (repos.isEmpty() || e.status !in setOf(403, 429)) throw e
            warnings += "GitHub 저장소 검색 한도에 도달해 부분 결과를 사용합니다."
        }
        return repoItems(repos) to warnings.joinToString(" · ").ifBlank { null }
    }

    private fun token() = secrets.get("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }

    internal fun historicalSearch(
        clock: () -> Instant = Instant::now,
        wait: suspend (Long) -> Unit = { delay(it) },
        warn: (String) -> Unit
    ): HistoricalSearch = HistoricalSearch(clock, wait, warn)

    internal inner class HistoricalSearch(
        private val clock: () -> Instant,
        private val wait: suspend (Long) -> Unit,
        warn: (String) -> Unit
    ) {
        private val token = token()
        private val limiter = GitHubSearch(clock, wait, historical = true)

        init {
            if (token == null) warn("GITHUB_TOKEN 없음 · GitHub 과거 검색 요청 간격을 늘립니다.")
        }

        suspend fun fetch(config: GitHubConfig, day: LocalDate): List<Item> =
            repoItems(linkedMapOf<Long, Repo>().also { search(config, day.toString(), token, it, ::get) })

        private suspend fun get(request: Request): Response =
            limiter.get(http, request, token) { body, attempt ->
                val result = decode(body)
                when {
                    !result.incompleteResults -> result
                    attempt == 2 -> error("GitHub 검색 응답에 부분 결과가 포함되었습니다.")
                    else -> null
                }
            }
    }

    private suspend fun search(
        config: GitHubConfig, created: String, token: String?, repos: MutableMap<Long, Repo>,
        get: suspend (Request) -> Response
    ) {
        for (query in config.queries) {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegments("search/repositories")
                .addQueryParameter("q", "$query created:$created stars:>=${config.minRawScore}")
                .addQueryParameter("sort", "stars")
                .addQueryParameter("order", "desc")
                .addQueryParameter("per_page", config.fetchPerQuery.coerceIn(1, 100).toString())
                .build()
            val request = Request.Builder().url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .build()
            get(request).items.forEach { repos.putIfAbsent(it.id, it) }
        }
    }

    private fun repoItems(repos: Map<Long, Repo>): List<Item> {
        // 출처당 최대 건수(maxItems)는 스팸을 거른 뒤 파이프라인이 적용한다(SourceCap)
        return repos.values
            .sortedByDescending { it.stargazers_count }
            .map { it.toItem() }
    }

    private fun Repo.toItem() = Item(
        source = this@GitHubAdapter.id,
        externalId = this.id.toString(),
        title = full_name,
        url = html_url,
        rawScore = stargazers_count,
        publishedAt = Instant.parse(created_at),
        snippet = plainText(description),
        author = owner?.login,
        extra = buildMap {
            license?.spdx_id?.takeIf { it.isNotBlank() && it != "NOASSERTION" }?.let { put("license", it) }
            language?.let { put("language", it) }
            put("forks", forks_count.toString())
        },
        tags = topics
    )

    private fun decode(body: String) = Http.json.decodeFromString(Response.serializer(), body)

    @Serializable
    private data class Response(
        val items: List<Repo> = emptyList(),
        @kotlinx.serialization.SerialName("incomplete_results") val incompleteResults: Boolean = false
    )

    @Suppress("PropertyName")
    @Serializable
    private data class Repo(
        val id: Long,
        val full_name: String,
        val html_url: String,
        val description: String? = null,
        val stargazers_count: Int = 0,
        val forks_count: Int = 0,
        val created_at: String,
        val language: String? = null,
        val topics: List<String> = emptyList(),
        val license: License? = null,
        val owner: Owner? = null
    )

    @Suppress("PropertyName")
    @Serializable
    private data class License(val spdx_id: String? = null)

    @Serializable
    private data class Owner(val login: String? = null)
}

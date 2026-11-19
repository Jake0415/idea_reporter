package collector.source

import collector.config.GitHubConfig
import collector.config.SourceConfig
import collector.model.Item
import collector.port.SecretProvider
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
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

    override suspend fun fetch(config: SourceConfig, now: Instant): List<Item> {
        require(config is GitHubConfig) { "GitHubAdapter에는 GitHubConfig가 필요합니다" }
        val sinceDate = now.minus(config.window()).atOffset(ZoneOffset.UTC).toLocalDate()
        val token = secrets.get("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }
        val repos = LinkedHashMap<Long, Repo>()
        for (query in config.queries) {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegments("search/repositories")
                .addQueryParameter("q", "$query created:>=$sinceDate stars:>=${config.minRawScore}")
                .addQueryParameter("sort", "stars")
                .addQueryParameter("order", "desc")
                .addQueryParameter("per_page", config.maxItems.coerceIn(1, 100).toString())
                .build()
            val request = Request.Builder().url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .build()
            val body = Http.getText(http, request)
            Http.json.decodeFromString(Response.serializer(), body).items
                .forEach { repos.putIfAbsent(it.id, it) }
        }
        return repos.values
            .sortedByDescending { it.stargazers_count }
            .take(config.maxItems)
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

    @Serializable
    private data class Response(val items: List<Repo> = emptyList())

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

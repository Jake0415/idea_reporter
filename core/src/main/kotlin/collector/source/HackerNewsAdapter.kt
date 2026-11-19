package collector.source

import collector.config.HnConfig
import collector.config.SourceConfig
import collector.model.Item
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant

/**
 * Hacker News — Algolia 검색 API. 검색어별로 [window] 안의 story를 최소 점수 이상만 가져온다.
 * https://hn.algolia.com/api
 */
class HackerNewsAdapter(
    private val http: OkHttpClient,
    private val baseUrl: String = "https://hn.algolia.com/"
) : SourceAdapter {
    override val id = "hn"

    override suspend fun fetch(config: SourceConfig, now: Instant): List<Item> {
        require(config is HnConfig) { "HackerNewsAdapter에는 HnConfig가 필요합니다" }
        val since = now.minus(config.window()).epochSecond
        val hits = LinkedHashMap<String, Hit>()
        for (query in config.queries) {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegments("api/v1/search_by_date")
                .addQueryParameter("query", query)
                .addQueryParameter("tags", "story")
                .addQueryParameter("numericFilters", "created_at_i>$since,points>=${config.minRawScore}")
                .addQueryParameter("hitsPerPage", config.maxItems.coerceIn(1, 100).toString())
                .build()
            val body = Http.getText(http, Request.Builder().url(url).build())
            Http.json.decodeFromString(Response.serializer(), body).hits
                .filter { !it.title.isNullOrBlank() }
                .forEach { hits.putIfAbsent(it.objectID, it) }   // 검색어끼리 겹치면 하나만
        }
        return hits.values
            .sortedByDescending { it.points ?: 0 }
            .take(config.maxItems)
            .map { it.toItem() }
    }

    private fun Hit.toItem(): Item {
        val discussion = "https://news.ycombinator.com/item?id=$objectID"
        return Item(
            source = id,
            externalId = objectID,
            title = title!!.trim(),
            url = url?.takeIf { it.isNotBlank() } ?: discussion,   // Ask/Show HN은 원문 URL이 없음
            rawScore = points ?: 0,
            publishedAt = Instant.ofEpochSecond(created_at_i),
            snippet = plainText(story_text),
            author = author,
            commentCount = num_comments ?: 0,
            discussionUrl = discussion
        )
    }

    @Serializable
    private data class Response(val hits: List<Hit> = emptyList())

    @Suppress("PropertyName")
    @Serializable
    private data class Hit(
        val objectID: String,
        val title: String? = null,
        val url: String? = null,
        val author: String? = null,
        val points: Int? = null,
        val num_comments: Int? = null,
        val created_at_i: Long,
        val story_text: String? = null
    )
}

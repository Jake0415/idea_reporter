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
        val hits = LinkedHashMap<String, Item>()
        for (query in config.queries) {
            val override = config.overrideFor(query)
            val since = now.minus(override?.window() ?: config.window()).epochSecond
            val minRawScore = override?.minRawScore ?: config.minRawScore
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegments("api/v1/search_by_date")
                .addQueryParameter("query", query)
                .addQueryParameter("tags", "story")
                .addQueryParameter("numericFilters", "created_at_i>$since,points>=$minRawScore")
                .addQueryParameter("hitsPerPage", config.fetchPerQuery.coerceIn(1, 100).toString())
                .build()
            val body = Http.getText(http, Request.Builder().url(url).build())
            Http.json.decodeFromString(Response.serializer(), body).hits
                .filter { !it.title.isNullOrBlank() }
                .forEach { hits.putIfAbsent(it.objectID, it.toItem(query)) }   // 검색어끼리 겹치면 하나만
        }
        // 출처당 최대 건수(maxItems)는 거르고 난 뒤 파이프라인이 적용한다(SourceCap)
        return hits.values.sortedByDescending { it.rawScore }
    }

    private fun Hit.toItem(query: String): Item {
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
            discussionUrl = discussion,
            extra = mapOf("matchedQuery" to query)
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

package collector.source

import collector.config.ProductHuntConfig
import collector.config.SourceConfig
import collector.model.Item
import collector.port.SecretProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant

/** https://www.producthunt.com/v2/docs */
class ProductHuntAdapter(
    private val http: OkHttpClient,
    private val secrets: SecretProvider,
    private val endpoint: String = "https://api.producthunt.com/v2/api/graphql"
) : SourceAdapter {
    override val id = "ph"

    override suspend fun fetch(config: SourceConfig, now: Instant): List<Item> {
        require(config is ProductHuntConfig)
        val token = secrets.get("PRODUCTHUNT_TOKEN")?.takeIf { it.isNotBlank() }
            ?: throw MissingSourceSecret("PRODUCTHUNT_TOKEN")
        val since = now.minus(config.window())
        val payload = buildJsonObject {
            put("query", """
                query AiPosts(${'$'}topic: String!, ${'$'}since: DateTime!, ${'$'}first: Int!) {
                  posts(topic: ${'$'}topic, postedAfter: ${'$'}since, first: ${'$'}first, order: VOTES) {
                    edges { node { id name tagline url votesCount createdAt } }
                  }
                }
            """.trimIndent())
            put("variables", buildJsonObject {
                put("topic", config.topic)
                put("since", since.toString())
                put("first", config.fetchPerQuery.coerceIn(1, 50))
            })
        }
        val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        val response = Http.authenticatedJson<Response>(http, request)
        if (response.errors.isNotEmpty()) throw IOException("Product Hunt GraphQL 요청 실패")
        val data = response.data ?: throw IOException("Product Hunt 응답 데이터 없음")
        return data.posts.edges.mapNotNull { (post) ->
            val createdAt = Instant.parse(post.createdAt)
            if (createdAt.isBefore(since) || createdAt.isAfter(now) || post.votesCount < config.minVotes) null
            else Item(id, post.id, post.name, post.url, post.votesCount, publishedAt = createdAt,
                snippet = plainText(post.tagline))
        }
    }

    @Serializable private data class Response(val data: Data? = null, val errors: List<JsonObject> = emptyList())
    @Serializable private data class Data(val posts: Posts)
    @Serializable private data class Posts(val edges: List<Edge>)
    @Serializable private data class Edge(val node: Post)
    @Serializable private data class Post(
        val id: String, val name: String, val tagline: String? = null, val url: String,
        val votesCount: Int, val createdAt: String
    )
}

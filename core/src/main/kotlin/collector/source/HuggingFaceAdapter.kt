package collector.source

import collector.config.HfConfig
import collector.config.SourceConfig
import collector.model.Item
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import kotlin.math.roundToInt

/** https://huggingface.co/.well-known/openapi.md */
class HuggingFaceAdapter(
    private val http: OkHttpClient,
    private val baseUrl: String = "https://huggingface.co/"
) : SourceAdapter {
    override val id = "hf"

    override suspend fun fetch(config: SourceConfig, now: Instant): List<Item> {
        require(config is HfConfig)
        return listOf("models", "spaces").flatMap { kind ->
            val url = baseUrl.toHttpUrl().newBuilder().addPathSegments("api/$kind")
                .addQueryParameter("sort", "trendingScore")
                .addQueryParameter("direction", "-1")
                .addQueryParameter("limit", config.fetchPerQuery.coerceAtLeast(1).toString())
                .apply {
                    val fields = listOf("createdAt", "likes", "trendingScore", "tags")
                    (if (kind == "models") fields + "pipeline_tag" else fields)
                        .forEach { addQueryParameter("expand[]", it) }
                }.build()
            Http.json.decodeFromString<List<Repo>>(Http.getText(http, Request.Builder().url(url).build()))
                .mapNotNull { repo ->
                    val created = repo.createdAt?.let(Instant::parse) ?: return@mapNotNull null
                    Item(id, "$kind:${repo.id}", repo.id,
                        "https://huggingface.co/${if (kind == "spaces") "spaces/" else ""}${repo.id}",
                        repo.trendingScore?.roundToInt() ?: repo.likes, publishedAt = created,
                        author = repo.id.substringBefore('/'),
                        extra = buildMap {
                            repo.tags.firstOrNull { it.startsWith("license:") }
                                ?.substringAfter(':')?.let { put("license", it) }
                            repo.pipeline_tag?.let { put("pipeline_tag", it) }
                        }, tags = repo.tags)
                }
        }.sortedByDescending { it.rawScore }
    }

    @Serializable
    private data class Repo(
        val id: String,
        val createdAt: String? = null,
        val likes: Int = 0,
        val trendingScore: Double? = null,
        val tags: List<String> = emptyList(),
        val pipeline_tag: String? = null
    )
}

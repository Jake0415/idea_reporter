package collector.source

import collector.config.RedditConfig
import collector.config.SourceConfig
import collector.model.Comment
import collector.model.Item
import collector.port.SecretProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant

internal class MissingSourceSecret(name: String) : IOException("키 없음 ($name)")

/** https://github.com/reddit-archive/reddit/wiki/OAuth2 */
class RedditAdapter(
    private val http: OkHttpClient,
    private val secrets: SecretProvider,
    private val baseUrl: String = "https://oauth.reddit.com/",
    private val authUrl: String = "https://www.reddit.com/"
) : SourceAdapter {
    override val id = "reddit"
    private val userAgent = "jvm:ai-idea-collector:0.1"
    private val tokenLock = Mutex()
    private var cachedToken: Token? = null
    private var expiresAt = Instant.MIN

    override suspend fun fetch(config: SourceConfig, now: Instant): List<Item> {
        require(config is RedditConfig)
        val token = accessToken()
        return config.subreddits.flatMap { subreddit ->
            val url = baseUrl.toHttpUrl().newBuilder().addPathSegment("r").addPathSegment(subreddit)
                .addPathSegment("top").addQueryParameter("t", "day")
                .addQueryParameter("limit", config.fetchPerQuery.coerceIn(1, 100).toString())
                .addQueryParameter("raw_json", "1").build()
            val listing = get<Listing<Post>>(url, token)
            listing.data.children.map { it.data }.filter { it.ups >= config.minUpvotes }.map { post ->
                val discussion = "https://www.reddit.com${post.permalink}"
                Item(id, post.id, post.title, post.url?.takeIf { it.isNotBlank() } ?: discussion,
                    post.ups, publishedAt = Instant.ofEpochSecond(post.created_utc.toLong()),
                    snippet = plainText(post.selftext), author = post.author,
                    commentCount = post.num_comments, discussionUrl = discussion,
                    extra = post.selftext?.let { mapOf("body" to it.take(3000)) }.orEmpty(),
                    profileHint = config.subredditProfiles.entries.firstOrNull { it.key.equals(subreddit, ignoreCase = true) }
                        ?.value ?: config.profile)
            }
        }.distinctBy { it.externalId }.sortedByDescending { it.rawScore }
    }

    internal suspend fun comments(postId: String, limit: Int): List<Comment> {
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegment("comments").addPathSegment(postId)
            .addQueryParameter("sort", "top").addQueryParameter("limit", limit.coerceIn(1, 100).toString())
            .addQueryParameter("raw_json", "1").build()
        val listings = get<List<Listing<Reply>>>(url, accessToken())
        return listings.getOrNull(1)?.data?.children.orEmpty().asSequence()
            .filter { it.kind == "t1" }
            .mapNotNull {
                val author = it.data.author?.takeUnless { name -> name.isBlank() || name == "[deleted]" }
                val body = plainText(it.data.body, 500)
                if (author != null && body.isNotBlank() && body != "[removed]") Comment(author, body) else null
            }.take(limit).toList()
    }

    private suspend fun accessToken(): String = tokenLock.withLock {
        val clientId = secrets.get("REDDIT_CLIENT_ID")?.takeIf { it.isNotBlank() }
            ?: throw MissingSourceSecret("REDDIT_CLIENT_ID")
        val secret = secrets.get("REDDIT_CLIENT_SECRET")?.takeIf { it.isNotBlank() }
            ?: throw MissingSourceSecret("REDDIT_CLIENT_SECRET")
        val now = Instant.now()
        cachedToken?.takeIf { now.isBefore(expiresAt) }?.let { return@withLock it.access_token }
        val url = authUrl.toHttpUrl().newBuilder().addPathSegments("api/v1/access_token").build()
        val request = Request.Builder().url(url).header("User-Agent", userAgent).header("Authorization", Credentials.basic(clientId, secret))
            .post(FormBody.Builder().add("grant_type", "client_credentials").build()).build()
        val token = Http.authenticatedJson<Token>(http, request)
        cachedToken = token
        expiresAt = now.plusSeconds((token.expires_in - 30).coerceAtLeast(0))
        token.access_token
    }

    private suspend inline fun <reified T> get(url: HttpUrl, token: String): T =
        Http.authenticatedJson(http, Request.Builder().url(url).header("User-Agent", userAgent).header("Authorization", "Bearer $token").build())

    @Serializable private data class Token(val access_token: String, val expires_in: Long)
    @Serializable private data class Listing<T>(val data: ListingData<T>)
    @Serializable private data class ListingData<T>(val children: List<Child<T>>)
    @Serializable private data class Child<T>(val kind: String, val data: T)
    @Serializable private data class Reply(val author: String? = null, val body: String? = null)
    @Serializable private data class Post(
        val id: String, val title: String, val permalink: String, val created_utc: Double,
        val ups: Int, val num_comments: Int = 0, val author: String? = null,
        val url: String? = null, val selftext: String? = null
    )
}

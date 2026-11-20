package collector.stage

import collector.model.Comment
import collector.model.HnContext
import collector.model.Item
import collector.model.Signal
import collector.profile.ProfileLoader
import collector.profile.Sampling
import collector.source.Http
import collector.source.RedditAdapter
import collector.source.plainText
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

class CommentFetcher(
    private val http: OkHttpClient, private val limit: Int, private val reddit: RedditAdapter? = null
) {
    suspend fun fetch(item: Item): List<Comment> {
        if (limit <= 0) return emptyList()
        if (item.source == "reddit") return fetchRedditComments(item.externalId).take(limit)
        if (item.source != "hn") return emptyList()
        val thread = fetchThread(item) ?: return emptyList()
        return thread.children.asSequence()
            .filter { !it.author.isNullOrBlank() && !it.text.isNullOrBlank() }
            .take(if (thread.rankingUnavailable) minOf(limit, 5) else limit).map { Comment(checkNotNull(it.author), plainText(it.text, 500)) }.toList()
    }

    internal suspend fun contexts(items: List<Item>): Map<String, HnContext> = coroutineScope {
        val slots = Semaphore(4)
        val candidates = items.filter { it.source == "hn" }.take(40) + items.filter { it.source == "reddit" }
        candidates.distinctBy { it.key }.map { item ->
            async { slots.withPermit { context(item)?.let { item.key to it } } }
        }.awaitAll().filterNotNull().toMap()
    }

    internal suspend fun context(item: Item): HnContext? {
        if (item.source == "reddit" && reddit != null) {
            val top = fetchRedditComments(item.externalId).take(5)
            return HnContext(topComments = top.map { it.text.take(300) })
        }
        if (item.source != "hn") return null
        val thread = fetchThread(item) ?: return null
        val story = plainText(thread.storyText ?: thread.text, 1000)
        val comments = thread.children.asSequence().map { plainText(it.text, 300) }
            .filter { it.isNotBlank() }.take(5).toList()
        return HnContext(story, comments)
    }

    private class CachedThread {
        val mutex = Mutex()
        var loaded = false
        var thread: Thread? = null
    }

    private val threads = ConcurrentHashMap<String, CachedThread>()

    private class CachedRedditComments {
        val mutex = Mutex()
        var comments: List<Comment>? = null
    }

    private val redditComments = ConcurrentHashMap<String, CachedRedditComments>()

    private suspend fun fetchRedditComments(id: String): List<Comment> {
        val adapter = reddit ?: return emptyList()
        val cached = redditComments.computeIfAbsent(id) { CachedRedditComments() }
        return cached.mutex.withLock {
            cached.comments ?: fetchOrNull { adapter.comments(id, maxOf(limit, 5)) }
                ?.also { cached.comments = it }.orEmpty()
        }
    }

    private suspend fun fetchThread(item: Item): Thread? {
        val cached = threads.computeIfAbsent(item.externalId) { CachedThread() }
        return cached.mutex.withLock {
            if (!cached.loaded) {
                cached.thread = loadThread(item.externalId)
                cached.loaded = true
            }
            cached.thread
        }
    }

    private suspend fun loadThread(id: String): Thread? {
        val url = "https://hn.algolia.com/api/v1/items".toHttpUrl().newBuilder().addPathSegment(id).build()
        val thread = fetchOrNull {
            Http.json.decodeFromString<Thread>(Http.getText(http, Request.Builder().url(url).build()))
        } ?: return null
        if (thread.children.isEmpty()) return thread
        val ranking = fetchOrNull {
            val rankedUrl = "https://hacker-news.firebaseio.com/v0/item".toHttpUrl().newBuilder()
                .addPathSegment("$id.json").build()
            Http.json.decodeFromString<Ranking>(Http.getText(http, Request.Builder().url(rankedUrl).build()))
        }
        if (ranking == null) return thread.copy(rankingUnavailable = true)
        val comments = thread.children.associateBy { it.id }
        return thread.copy(children = ranking.kids.mapNotNull(comments::get))
    }

    @Serializable private data class Ranking(val kids: List<Long> = emptyList())

    @Serializable private data class Thread(
        val children: List<Entry> = emptyList(),
        @SerialName("story_text") val storyText: String? = null,
        val text: String? = null,
        @kotlinx.serialization.Transient val rankingUnavailable: Boolean = false
    )
    @Serializable private data class Entry(val id: Long, val author: String? = null, val text: String? = null)
}

internal fun JsonObjectBuilder.putHnContext(context: HnContext?) {
    context ?: return
    if (context.storyText.isNotBlank()) put("storyText", context.storyText)
    put("topComments", Json.encodeToJsonElement(context.topComments))
}

class Enricher(http: OkHttpClient, private val profiles: ProfileLoader.ProfileSet, private val comments: CommentFetcher) {
    private val articleHttp = http.newBuilder().proxy(Proxy.NO_PROXY).dns(object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            Dns.SYSTEM.lookup(hostname).filter(::isPublicAddress).ifEmpty { throw UnknownHostException("공개 주소 없음") }
    }).build()
    suspend fun enrich(signals: List<Signal>, groups: Map<String, List<Signal>>): List<Signal> = coroutineScope {
        val slots = Semaphore(6)
        signals.map { signal ->
            async {
                slots.withPermit {
                    val item = signal.item
                    val budget = checkNotNull(profiles[signal.classification.profile]).config.content
                    val text = if (item.source in setOf("hf", "reddit", "ph", "rss", "github-issues")) {
                        if (item.source in setOf("reddit", "github-issues")) item.extra["body"].orEmpty().ifBlank { item.snippet } else item.snippet
                    } else fetchOrNull {
                        val request = Request.Builder().url(item.url).build()
                        val html = withContext(Dispatchers.IO) {
                            articleHttp.newCall(request).execute().use { response ->
                                val type = response.body?.contentType()?.type
                                if (!response.isSuccessful || type != null && type != "text") ""
                                else response.peekBody(512_000).string()
                            }
                        }
                        plainText(html.replace(Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>"), ""),
                            Int.MAX_VALUE)
                    }.orEmpty().ifBlank { item.snippet }
                    val thread = (listOf(item) + groups[item.key].orEmpty().map { it.item }).flatMap { comments.fetch(it) }
                    signal.copy(body = sample(text, budget.generateMaxChars, budget.sampling),
                        enrichedComments = (item.comments + thread).distinct(),
                        hnContext = signal.hnContext ?: comments.context(item))
                }
            }
        }.awaitAll()
    }

    internal fun sample(text: String, limit: Int, sampling: Sampling): String {
        if (text.length <= limit || sampling == Sampling.PREFIX) return text.take(limit)
        val part = (limit - 2) / 3
        val middle = (text.length - part) / 2
        return text.take(part) + "…" + text.substring(middle, middle + part) + "…" + text.takeLast(limit - 2 - part * 2)
    }
}

private fun isPublicAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isSiteLocalAddress ||
        address.isLinkLocalAddress || address.isMulticastAddress) return false
    val bytes = address.address.map { it.toInt() and 255 }
    val (first, second) = bytes
    if (bytes.size == 16) return first in 32..63
    return first in 1..223 &&
        !(first == 100 && second in 64..127) &&
        !(first == 198 && second in 18..19)
}

private suspend fun <T> fetchOrNull(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

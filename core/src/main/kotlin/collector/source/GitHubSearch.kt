package collector.source

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.WeakHashMap

internal class GitHubCollectionException(message: String) : IOException(message)

internal class GitHubSearch(
    private val clock: () -> Instant = Instant::now,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val historical: Boolean = false
) {
    // Pipeline orders the daily phases. Published search resets gate only the lower priorities.
    enum class Priority { DAILY_REPOSITORIES, DAILY_ISSUES, TRACKING }

    private val mutex = Mutex()
    private val nextRequest = mutableMapOf<String, Instant>()

    suspend fun <T : Any> get(
        http: OkHttpClient, request: Request, token: String?, retryLimited: Boolean = true,
        priority: Priority = Priority.TRACKING, accept: (String, Int) -> T?
    ): T {
        val resource = if (request.method == "POST") "graphql" else "search"
        repeat(3) { attempt ->
            var response: SearchResponse? = null
            while (response == null) {
                val waitMillis = mutex.withLock {
                    val remaining = Duration.between(clock(), nextRequest[resource] ?: Instant.MIN)
                    if (priority != Priority.DAILY_REPOSITORIES && !remaining.isNegative && !remaining.isZero)
                        return@withLock remaining.toMillis().coerceAtLeast(1)
                    val result = Http.readResponse(http, request, includeErrorBody = false, requireSuccess = false) {
                        SearchResponse(it.code, it.header("X-RateLimit-Remaining")?.toIntOrNull(),
                            it.header("X-RateLimit-Reset")?.toLongOrNull(), it.header("Retry-After")?.toLongOrNull(),
                            if (it.isSuccessful) it.body?.string().orEmpty() else "")
                    }
                    val received = clock()
                    var next = if (historical) received.plusSeconds(if (token == null) 7 else 2) else received
                    val lowRemaining = if (historical) result.code in 200..299 && result.remaining == 0
                        else (result.remaining ?: Int.MAX_VALUE) <= 2
                    if (lowRemaining || result.limited) {
                        next = maxOf(next, nextRequest[resource] ?: received)
                        val reset = result.reset?.let(Instant::ofEpochSecond)
                        if (reset != null) next = maxOf(next, reset)
                        if (result.retryAfter != null) next = maxOf(next, received.plusSeconds(result.retryAfter))
                        if (result.limited && (reset == null || reset <= received) && result.retryAfter == null)
                            next = maxOf(next, received.plusSeconds(60L shl attempt))
                    }
                    nextRequest[resource] = next
                    response = result
                    0L
                }
                if (waitMillis > 0) wait(waitMillis)
            }
            val result = checkNotNull(response)
            if (result.code in 200..299) {
                accept(result.body, attempt)?.let { return it }
            } else if (!result.limited || !retryLimited || attempt == 2) {
                throw HttpStatusException(result.code, null)
            }
        }
        error("Unreachable")
    }

    private data class SearchResponse(
        val code: Int, val remaining: Int?, val reset: Long?, val retryAfter: Long?, val body: String
    ) {
        val limited get() = code == 429 || code == 403
    }

    companion object {
        private val clients = WeakHashMap<OkHttpClient, GitHubSearch>()

        @Synchronized
        fun shared(http: OkHttpClient, create: () -> GitHubSearch = { GitHubSearch() }): GitHubSearch =
            clients.getOrPut(http, create)
    }
}

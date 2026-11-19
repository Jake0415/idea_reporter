package collector.source

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** 공용 HTTP 설정. User-Agent에는 개인정보를 넣지 않는다. */
object Http {
    const val USER_AGENT = "ai-idea-collector/0.1"

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val request = chain.request()
            chain.proceed(if (request.header("User-Agent") == null) request.newBuilder().header("User-Agent", USER_AGENT).build() else request)
        }
        .build()

    /** GET 후 본문 문자열. 2xx가 아니면 IOException(상태 코드 포함). */
    suspend fun getText(client: OkHttpClient, request: Request, includeErrorBody: Boolean = true): String =
        readResponse(client, request, includeErrorBody) { it.body?.string().orEmpty() }

    internal suspend fun <T> readResponse(
        client: OkHttpClient, request: Request, includeErrorBody: Boolean, read: (Response) -> T
    ): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resumeWith(runCatching {
                    response.use { resp ->
                        if (!resp.isSuccessful) throw HttpStatusException(
                            resp.code, if (includeErrorBody) resp.body?.string().orEmpty().take(200) else null
                        )
                        read(resp)
                    }
                })
            }
        })
    }

    internal suspend inline fun <reified T> authenticatedJson(client: OkHttpClient, request: Request): T {
        val body = getText(client, request, includeErrorBody = false)
        return try {
            json.decodeFromString<T>(body)
        } catch (_: SerializationException) {
            throw IOException("응답 JSON 형식 오류")
        }
    }
}

internal class HttpStatusException(val status: Int, body: String?) :
    IOException(if (body == null) "HTTP $status" else "HTTP $status: $body")

/** HTML 태그 제거 + 자주 쓰는 엔티티 복원 + 공백 정리 + 길이 제한 */
internal fun plainText(html: String?, max: Int = 300): String {
    if (html.isNullOrBlank()) return ""
    val text = html
        .replace(Regex("<[^>]+>"), " ")
        .replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&#x2F;", "/").replace("&amp;", "&")
        .replace(Regex("\\s+"), " ")
        .trim()
    return if (text.length <= max) text else text.take(max - 1) + "…"
}

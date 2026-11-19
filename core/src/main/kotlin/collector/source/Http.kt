package collector.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

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
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()

    /** GET 후 본문 문자열. 2xx가 아니면 IOException(상태 코드 포함). */
    suspend fun getText(client: OkHttpClient, request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${body.take(200)}")
            body
        }
    }
}

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

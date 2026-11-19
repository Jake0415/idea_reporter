package collector.source

import collector.config.RssConfig
import collector.config.RssFeed
import collector.config.SourceConfig
import collector.model.Item
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class RssFetchException(message: String) : IOException(message)

class RssAdapter(private val http: OkHttpClient) : SourceAdapter {
    override val id = "rss"

    override suspend fun fetch(config: SourceConfig, now: Instant): List<Item> {
        require(config is RssConfig)
        return fetchWithSummary(config, now).items
    }

    internal data class FetchResult(val items: List<Item>, val warning: String?)

    internal suspend fun fetchWithSummary(config: RssConfig, now: Instant, sourceTimeout: Duration = 45.seconds): FetchResult {
        val since = now.minus(config.window())
        val failures = mutableListOf<String>()
        val items = mutableListOf<Item>()
        var completed = 0
        val feedHttp = if (http.callTimeoutMillis > 0) http
            else http.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
        val finished = withTimeoutOrNull(sourceTimeout) {
            for (feed in config.feeds) {
                try {
                    val request = Request.Builder().url(feed.url).build()
                    items += Http.readResponse(feedHttp, request, includeErrorBody = false) { response ->
                        response.body?.byteStream()?.let { parse(feed, request.url, it, since, now) }
                            ?: throw IOException("빈 피드 응답")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    coroutineContext.ensureActive()
                    val status = when (e) {
                        is HttpStatusException -> " (HTTP ${e.status})"
                        is InterruptedIOException -> " (시간 초과)"
                        else -> ""
                    }
                    failures += feed.name + status
                }
                completed++
            }
            true
        }
        if (finished != true) {
            failures += config.feeds.drop(completed).map { "${it.name} (시간 초과)" }
        }
        val error = failures.takeIf { it.isNotEmpty() }?.joinToString(prefix = "피드 수집 실패: ")
        if (error != null && failures.size == config.feeds.size) throw RssFetchException(error)
        return FetchResult(items.distinctBy { it.externalId }, error?.let { "RSS 부분 실패 · $it" })
    }

    private fun parse(feed: RssFeed, requestUrl: HttpUrl, body: InputStream, since: Instant, now: Instant): List<Item> {
        val builder = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }.newDocumentBuilder().apply { setErrorHandler(DefaultHandler()) }
        val root = builder.parse(InputSource(body)).documentElement
        val atom = root.localName == "feed" && root.namespaceURI == "http://www.w3.org/2005/Atom"
        val entries = when {
            atom -> root.elements("entry")
            root.localName == "rss" -> root.elements("channel").firstOrNull()?.elements("item").orEmpty()
            else -> error("지원하지 않는 피드 형식")
        }
        val base = requestUrl.newBuilder().query(null).username("").password("").build()
        val privateValues = requestUrl.rssPrivateValues()
        val privatePattern = privateValues.rssPrivatePattern()
        fun redact(value: String) = privatePattern?.replace(value, "[redacted]") ?: value
        fun redactText(value: String?): String {
            val text = redact(plainText(value, Int.MAX_VALUE))
            return if (text.length <= 300) text else text.take(299) + "…"
        }
        return entries.mapNotNull { entry ->
            val published = if (atom) entry.text("published") ?: entry.text("updated") else entry.text("pubDate")
            val date = published?.let(::parseDate) ?: return@mapNotNull null
            if (date.isBefore(since) || date.isAfter(now)) return@mapNotNull null
            val title = redactText(entry.text("title")).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val link = if (atom) entry.elements("link")
                .firstOrNull { it.getAttribute("rel") in listOf("", "alternate") && it.getAttribute("href").isNotBlank() }
                ?.getAttribute("href") else entry.text("link")
            val resolved = link?.let(base::resolve) ?: return@mapNotNull null
            val query = resolved.encodedQuery?.split('&')?.filterIndexed { i, _ ->
                resolved.queryParameterName(i) !in privateValues && resolved.queryParameterValue(i) !in privateValues
            }?.takeIf { it.isNotEmpty() }?.joinToString("&")
            val url = redact(resolved.newBuilder().username("").password("").encodedQuery(query).build().toString())
            val guid = entry.text(if (atom) "id" else "guid") ?: resolved.toString()
            val snippet = if (atom) entry.text("summary") ?: entry.text("content") else entry.text("description")
            val hash = MessageDigest.getInstance("SHA-256").digest(guid.toByteArray())
            val author = if (atom) entry.elements("author").firstOrNull()?.text("name") else entry.text("author")
            Item(id, "${feed.name}:${HexFormat.of().formatHex(hash)}", title, url, 0, publishedAt = date,
                snippet = redactText(snippet), author = author?.let(::redact),
                extra = mapOf("feed" to feed.name), profileHint = feed.profile.map(::redact))
        }
    }

    private fun Element.elements(name: String): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
            .filter { it.localName == name && it.namespaceURI == namespaceURI }

    private fun Element.text(name: String): String? =
        elements(name).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    private fun parseDate(value: String): Instant? = try {
        Instant.parse(value)
    } catch (_: DateTimeParseException) {
        try {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

internal fun HttpUrl.rssPrivateValues(): List<String> =
    (0 until querySize).mapNotNull(::queryParameterValue).filter { it.length >= 6 } +
        listOf(username, password).filter { it.isNotEmpty() }

internal fun List<String>.rssPrivatePattern(): Regex? =
    distinct().sortedByDescending { it.length }.takeIf { it.isNotEmpty() }
        ?.joinToString("|", transform = ::encodedSecretPattern)?.toRegex()

private fun encodedSecretPattern(secret: String): String =
    secret.codePoints().toArray().joinToString("") { codePoint ->
        val character = String(Character.toChars(codePoint))
        val encoded = HexFormat.of().withPrefix("%").formatHex(character.toByteArray(Charsets.UTF_8))
        val formSpace = if (character == " ") "|\\+" else ""
        "(?:${Regex.escape(character)}|(?i:$encoded)$formSpace)"
    }

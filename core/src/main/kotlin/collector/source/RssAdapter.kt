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
        // Android's DocumentBuilderFactory rejects most of these hardening switches (it throws instead of
        // ignoring them), so each is applied best-effort. If the parser cannot itself refuse DOCTYPE, the
        // document is checked for a DOCTYPE before parsing, keeping XXE/entity-expansion protection.
        var doctypeBlocked = false
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { isXIncludeAware = false }
            isExpandEntityReferences = false
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            doctypeBlocked = runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }.isSuccess
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
            runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
        }
        val source = if (doctypeBlocked) body else {
            val bytes = body.readBytes()
            val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
            require(!head.contains("<!DOCTYPE", ignoreCase = true)) { "DOCTYPE가 있는 피드는 처리하지 않음" }
            bytes.inputStream()
        }
        val builder = factory.newDocumentBuilder().apply { setErrorHandler(DefaultHandler()) }
        val root = builder.parse(InputSource(source)).documentElement
        val atom = root.localName == "feed" && root.namespaceURI == "http://www.w3.org/2005/Atom"
        val entries = when {
            atom -> root.elements("entry")
            root.localName == "rss" -> root.elements("channel").firstOrNull()?.elements("item").orEmpty()
            else -> error("지원하지 않는 피드 형식")
        }
        val base = requestUrl.newBuilder().query(null).username("").password("").build()
        val privateValues = requestUrl.rssPrivateValues()
        val privatePattern = privateValues.filter { it.length > 2 }.rssPrivatePattern()
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
            val url = resolved.redactRssUrl(privateValues).split("[redacted]").joinToString("[redacted]", transform = ::redact)
            val guid = entry.text(if (atom) "id" else "guid") ?: resolved.toString()
            val snippet = if (atom) entry.text("summary") ?: entry.text("content") else entry.text("description")
            val hash = MessageDigest.getInstance("SHA-256").digest(guid.toByteArray())
            val author = if (atom) entry.elements("author").firstOrNull()?.text("name") else entry.text("author")
            Item(id, "${feed.name}:${hash.toHexString()}", title, url, 0, publishedAt = date,
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

internal val privateField = Regex("mail|smtp|address|token|secret|password|key|authorization|recipient|sender", RegexOption.IGNORE_CASE)
private const val PRIVATE_RSS_QUERY_LENGTH = 6
private val strayPercent = Regex("%(?![0-9a-fA-F]{2})")

internal fun normalizePrivateText(content: String): String {
    var result = content
    while ('%' in result) {
        val decoded = runCatching {
            java.net.URLDecoder.decode(strayPercent.replace(result, "%25").replace("+", "%2B"), "UTF-8")
        }.getOrDefault(result)
        if (decoded == result) break
        result = decoded
    }
    return java.text.Normalizer.normalize(result, java.text.Normalizer.Form.NFKC)
}

internal fun HttpUrl.hasPrivateRssQuery(index: Int): Boolean =
    privateField.containsMatchIn(normalizePrivateText(queryParameterName(index))) ||
        (queryParameterValue(index)?.length ?: 0) >= PRIVATE_RSS_QUERY_LENGTH

internal fun HttpUrl.redactRssUrl(privateValues: List<String> = emptyList()): String {
    val builder = newBuilder().username("").password("")
    // 짧은 인증값이 링크 경로 조각이나 # 뒤로 돌아오는 경우도 가린다(일반 텍스트 치환은 길이 제한이 있어 못 잡는다).
    pathSegments.forEachIndexed { index, segment ->
        if (segment.isNotEmpty() && segment in privateValues) builder.setPathSegment(index, "[redacted]")
    }
    if (fragment?.let { it in privateValues } == true) builder.fragment("[redacted]")
    val encoded = encodedQuery ?: return builder.build().toString()
    val query = encoded.split('&').mapIndexed { index, parameter ->
        val name = queryParameterName(index)
        if (name in privateValues) null
        else if (hasPrivateRssQuery(index) || queryParameterValue(index) in privateValues) {
            parameter.substringBefore('=') + "=[redacted]"
        } else parameter
    }.filterNotNull()
    return builder.encodedQuery(query.takeIf { it.isNotEmpty() }?.joinToString("&")).build().toString()
}

internal fun HttpUrl.rssPrivateValues(): List<String> =
    (0 until querySize).filter(::hasPrivateRssQuery).mapNotNull(::queryParameterValue)
        .filter { it.isNotEmpty() && it != "[redacted]" } +
        listOf(username, password).filter { it.isNotEmpty() }

internal fun List<String>.rssPrivatePattern(): Regex? =
    distinct().sortedByDescending { it.length }.takeIf { it.isNotEmpty() }
        ?.joinToString("|", transform = ::encodedSecretPattern)?.toRegex()

private fun encodedSecretPattern(secret: String): String =
    secret.codePoints().toArray().joinToString("") { codePoint ->
        val character = String(Character.toChars(codePoint))
        val encoded = character.toByteArray(Charsets.UTF_8).toHexString(HexFormat { bytes.bytePrefix = "%" })
        val formSpace = if (character == " ") "|\\+" else ""
        "(?:${Regex.escape(character)}|(?i:$encoded)$formSpace)"
    }

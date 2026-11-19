package collector.refine

import java.net.URI

/**
 * 같은 글을 같은 URL로 보이게 정규화한다(중복 판정용, 표시용 URL은 원문 유지).
 * - 스킴은 https로, 호스트는 소문자·www 제거, 기본 포트 제거
 * - 끝 슬래시·#조각 제거
 * - 추적 파라미터(utm_*, ref, fbclid, gclid 등) 제거, 나머지는 정렬
 */
object UrlNormalizer {
    private val trackingParams = setOf("ref", "ref_src", "ref_url", "fbclid", "gclid", "mc_cid", "mc_eid", "igshid", "si")

    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed
        val host = uri.host ?: return trimmed
        val normalizedHost = host.lowercase().removePrefix("www.")
        val port = uri.port.takeIf { it != -1 && it != 80 && it != 443 }
        val path = (uri.rawPath ?: "").trimEnd('/')
        val query = uri.rawQuery
            ?.split('&')
            ?.filter { it.isNotBlank() }
            ?.filterNot { param ->
                val key = param.substringBefore('=').lowercase()
                key.startsWith("utm_") || key in trackingParams
            }
            ?.sorted()
            ?.joinToString("&")
        return buildString {
            append("https://").append(normalizedHost)
            if (port != null) append(':').append(port)
            append(path)
            if (!query.isNullOrEmpty()) append('?').append(query)
        }
    }
}

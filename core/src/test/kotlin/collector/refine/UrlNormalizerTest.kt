package collector.refine

import kotlin.test.Test
import kotlin.test.assertEquals

class UrlNormalizerTest {
    @Test
    fun `스킴·www·끝 슬래시·조각·추적 파라미터를 정리한다`() {
        assertEquals(
            "https://example.com/post/1?id=3",
            UrlNormalizer.normalize("http://WWW.Example.com/post/1/?utm_source=x&id=3&ref=hn#comments")
        )
    }

    @Test
    fun `쿼리 순서가 달라도 같은 URL`() {
        assertEquals(UrlNormalizer.normalize("https://a.com/p?b=2&a=1"), UrlNormalizer.normalize("https://a.com/p?a=1&b=2"))
    }

    @Test
    fun `기본이 아닌 포트는 유지한다`() {
        assertEquals("https://a.com:8080/x", UrlNormalizer.normalize("https://a.com:8080/x/"))
    }

    @Test
    fun `해석할 수 없는 문자열은 그대로 둔다`() {
        assertEquals("not a url", UrlNormalizer.normalize(" not a url "))
    }
}

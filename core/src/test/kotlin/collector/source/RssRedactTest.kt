package collector.source

import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RssRedactTest {
    @Test
    fun shortCredentialInPathAndFragmentIsRedacted() {
        val secrets = listOf("q7")
        val link = "https://feed.example.com/article/q7/view?id=1#q7".toHttpUrl().redactRssUrl(secrets)
        assertFalse("/q7/" in link)
        assertFalse(link.endsWith("#q7"))
        assertEquals("https://feed.example.com/article/[redacted]/view?id=1#[redacted]", link)
    }

    @Test
    fun ordinaryLinkIsUnchanged() {
        val link = "https://feed.example.com/article/hello?id=1".toHttpUrl().redactRssUrl(listOf("q7"))
        assertEquals("https://feed.example.com/article/hello?id=1", link)
    }
}

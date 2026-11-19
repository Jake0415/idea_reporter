package collector.refine

import collector.item
import collector.model.DropReason
import kotlin.test.Test
import kotlin.test.assertEquals

class DeduplicatorTest {
    @Test
    fun `다른 출처의 같은 URL은 합치고 alsoSeenIn에 남긴다`() {
        val a = item(source = "hn", id = "1", url = "https://github.com/foo/bar")
        val b = item(source = "github", id = "9", url = "http://www.github.com/foo/bar/?utm_source=x")
        val result = Deduplicator().dedupe(listOf(a, b))

        assertEquals(1, result.kept.size)
        assertEquals("hn:1", result.kept[0].key)
        assertEquals(listOf("github"), result.kept[0].alsoSeenIn)
        assertEquals(DropReason.DUPLICATE, result.dropped.single().reason)
    }

    @Test
    fun `같은 출처에서는 원점수가 높은 쪽이 대표`() {
        val low = item(id = "1", url = "https://x.com/a", rawScore = 60)
        val high = item(id = "2", url = "https://x.com/a/", rawScore = 300)
        val result = Deduplicator().dedupe(listOf(low, high))

        assertEquals("hn:2", result.kept.single().key)
        assertEquals("hn:1", result.dropped.single().item.key)
    }

    @Test
    fun `제목이 매우 비슷하면 URL이 달라도 합친다`() {
        val a = item(id = "1", url = "https://a.com/1", title = "OpenAI releases new open weight reasoning model")
        val b = item(id = "2", url = "https://b.com/2", title = "OpenAI releases new open-weight reasoning model")
        val c = item(id = "3", url = "https://c.com/3", title = "Google ships Gemini update for developers")
        val result = Deduplicator().dedupe(listOf(a, b, c))

        assertEquals(listOf("hn:1", "hn:3"), result.kept.map { it.key })
    }
}

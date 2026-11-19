package collector.stage

import collector.config.PipelineConfig
import collector.model.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class StageTest {
    @Test fun `같은 주제는 높은 분류 점수를 대표로 사용한다`() {
        val first = Item("hn", "1", "첫 글", "https://example.com/1", 99, publishedAt = Instant.EPOCH)
        val second = first.copy(externalId = "2", url = "https://example.com/2")
        fun signal(item: Item, score: Double) = Signal(item, ClassifiedItem(
            item.key, "tech-signal", score, "이유", "요약", listOf("tools"), "topic", "주제"
        ))
        val result = Selector(PipelineConfig.defaults).select(listOf(signal(first, 7.0), signal(second, 8.0)))
        assertEquals(second.key, result.kept.single().item.key)
        assertEquals(listOf(first.url), result.kept.single().item.mergedUrls)
        assertEquals(DropReason.SAME_TOPIC, result.dropped.single().reason)
        assertEquals(second.key, result.dropped.single().detail)
        assertEquals(first, result.groups.getValue(second.key).single().item)
    }
}

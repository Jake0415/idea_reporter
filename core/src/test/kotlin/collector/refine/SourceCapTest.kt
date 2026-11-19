package collector.refine

import collector.config.GitHubConfig
import collector.config.HnConfig
import collector.item
import collector.model.DropReason
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceCapTest {
    @Test
    fun `출처마다 원점수 상위 maxItems건만 남긴다`() {
        val items = (1..5).map { item(source = "hn", id = "h$it", rawScore = it * 10) } +
            (1..3).map { item(source = "github", id = "g$it", rawScore = it * 100) }
        val configs = mapOf("hn" to HnConfig(maxItems = 2), "github" to GitHubConfig(maxItems = 10))
        val result = SourceCap.cap(items, configs)

        assertEquals(setOf("hn:h5", "hn:h4", "github:g1", "github:g2", "github:g3"), result.kept.map { it.key }.toSet())
        assertEquals(3, result.dropped.size)
        assertEquals(setOf(DropReason.OVER_QUOTA), result.dropped.map { it.reason }.toSet())
    }
}

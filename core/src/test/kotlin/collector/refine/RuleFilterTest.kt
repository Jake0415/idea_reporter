package collector.refine

import collector.NOW
import collector.config.HnConfig
import collector.item
import collector.model.DropReason
import kotlin.test.Test
import kotlin.test.assertEquals

class RuleFilterTest {
    private val configs = mapOf("hn" to HnConfig(windowHours = 24, minRawScore = 50))

    @Test
    fun `기간·점수·키워드로 걸러내고 이유를 남긴다`() {
        val items = listOf(
            item(id = "ok", rawScore = 80),
            item(id = "old", rawScore = 80, publishedAt = NOW.minusSeconds(48 * 3600)),
            item(id = "low", rawScore = 10),
            item(id = "kw", rawScore = 80, title = "Crypto airdrop with AI"),
        )
        val result = RuleFilter(listOf("crypto")).filter(items, configs, NOW)

        assertEquals(listOf("ok"), result.kept.map { it.externalId })
        assertEquals(
            mapOf("old" to DropReason.OUT_OF_WINDOW, "low" to DropReason.LOW_SOURCE_SCORE, "kw" to DropReason.EXCLUDED_KEYWORD),
            result.dropped.associate { it.item.externalId to it.reason }
        )
    }

    @Test
    fun `키워드는 단어 단위로만 맞춘다`() {
        val result = RuleFilter(listOf("hiring")).filter(listOf(item(title = "Rehiringtools for AI")), configs, NOW)
        assertEquals(1, result.kept.size)
    }
}

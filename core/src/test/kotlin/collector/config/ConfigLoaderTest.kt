package collector.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigLoaderTest {
    @Test
    fun `기본 설정을 출처별 타입으로 읽는다`() {
        val file = ConfigLoader.loadDefault()
        val hn = file.sources.filterIsInstance<HnConfig>().single()
        val gh = file.sources.filterIsInstance<GitHubConfig>().single()

        assertEquals(listOf("Ask HN", "alternative to", "looking for a tool", "frustrated with")
            .map { HnQueryOverride(it, 72, 5) }, hn.queryOverrides)
        assertEquals(20, hn.minRawScore)
        assertEquals(listOf("LLM", "AI agent", "Show HN AI", "GPT", "Claude", "open source model",
            "Ask HN", "alternative to", "looking for a tool", "frustrated with"), hn.queries)
        assertEquals(listOf("tech-signal", "demand-signal", "market-signal"), hn.profile)
        assertEquals(100, gh.fetchPerQuery)
        assertEquals(listOf("tech-signal"), gh.profile)
        assertTrue("crypto" in file.excludeKeywords)
    }

    @Test
    fun readsCustomHnQueryOverrides() {
        val file = ConfigLoader.parse("""{"sources":[{"id":"hn","queryOverrides":[
            {"query":"custom","windowHours":48,"minRawScore":8}
        ]}]}""")
        assertEquals(listOf(HnQueryOverride("custom", 48, 8)), (file.sources.single() as HnConfig).queryOverrides)
    }

    @Test
    fun `빠진 값은 기본값으로 채운다`() {
        val file = ConfigLoader.parse("""{"sources":[{"id":"github","minRawScore":500}]}""")
        val gh = file.sources.single() as GitHubConfig
        assertEquals(500, gh.minRawScore)
        assertEquals(14, gh.createdWithinDays)
    }
}

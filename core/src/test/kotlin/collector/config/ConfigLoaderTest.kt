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

        assertEquals(50, hn.minRawScore)
        assertEquals(listOf("tech-signal"), gh.profile)
        assertTrue("crypto" in file.excludeKeywords)
    }

    @Test
    fun `빠진 값은 기본값으로 채운다`() {
        val file = ConfigLoader.parse("""{"sources":[{"id":"github","minRawScore":500}]}""")
        val gh = file.sources.single() as GitHubConfig
        assertEquals(500, gh.minRawScore)
        assertEquals(14, gh.createdWithinDays)
    }
}

package collector.profile

import collector.config.ConfigLoader
import collector.config.GitHubConfig
import collector.config.SourcesFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProfileLoaderTest {
    @Test
    fun `기본 프로필 3종을 읽고 검증을 통과한다`() {
        val set = ProfileLoader.loadDefaults()
        assertEquals(listOf("tech-signal", "demand-signal", "market-signal"), set.ids)
        assertEquals("tech-signal", set.default.id)
        assertEquals(Sampling.HEAD_MIDDLE_TAIL, set["demand-signal"]!!.config.content.sampling)
        assertTrue(set.profiles.all { it.analysis.contains("0~10") && it.match.contains("포함") })
    }

    @Test
    fun `기본 출처 설정의 프로필 배정은 모두 유효하다`() {
        val problems = ProfileLoader.validateRouting(ConfigLoader.loadDefault(), ProfileLoader.loadDefaults())
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `알 수 없는 프로필 배정을 잡아낸다`() {
        val sources = SourcesFile(listOf(GitHubConfig(profile = listOf("tech-signal", "typo-signal"))))
        val problems = ProfileLoader.validateRouting(sources, ProfileLoader.loadDefaults())
        assertTrue(problems.single().contains("typo-signal"))
    }

    @Test
    fun `잘못된 값은 시작할 때 거부한다`() {
        val files = mapOf(
            "profiles/index.json" to """{"profiles":["bad"],"default":"bad"}""",
            "profiles/bad/profile.json" to """{"id":"bad","name":"x","description":"x","threshold":12.0,"maxCandidates":0}""",
            "profiles/bad/match.md" to "포함",
            "profiles/bad/analysis.md" to " ",
        )
        val e = assertFailsWith<IllegalStateException> { ProfileLoader.loadDefaults { files[it] } }
        val msg = e.message!!
        assertTrue("threshold" in msg && "maxCandidates" in msg && "analysis.md" in msg, msg)
    }

    @Test
    fun `profile json의 모르는 필드는 거부한다`() {
        val files = mapOf(
            "profiles/index.json" to """{"profiles":["a"],"default":"a"}""",
            "profiles/a/profile.json" to """{"id":"a","name":"x","description":"x","threshold":5.0,"maxCandidates":1,"treshold":1}""",
            "profiles/a/match.md" to "포함",
            "profiles/a/analysis.md" to "0~10",
        )
        assertFailsWith<Exception> { ProfileLoader.loadDefaults { files[it] } }
    }
}

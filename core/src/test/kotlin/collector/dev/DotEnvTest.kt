package collector.dev

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DotEnvTest {
    @Test
    fun `주석·빈 줄·빈 값은 무시하고 따옴표를 벗긴다`() {
        val parsed = DotEnv.parse(
            """
            # comment
            A=1
            B = "two words"
            export C='three'
            EMPTY=
            not a line
            =nokey
            """.trimIndent()
        )
        assertEquals(mapOf("A" to "1", "B" to "two words", "C" to "three"), parsed)
    }

    @Test
    fun `환경 변수가 있으면 그것을 먼저 쓴다`() {
        val secrets = DotEnv.secretProvider(mapOf("K" to "from-file", "F" to "file-only")) { if (it == "K") "from-env" else null }
        assertEquals("from-env", secrets.get("K"))
        assertEquals("file-only", secrets.get("F"))
        assertNull(secrets.get("MISSING"))
    }
}

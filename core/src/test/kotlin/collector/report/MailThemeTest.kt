package collector.report

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 메일 꾸밈 값이 디자인 토큰 원본(mockup/tokens.css)의 라이트 값과 같은지 확인한다. */
class MailThemeTest {
    private fun tokensCss(): File =
        listOf(File("../mockup/tokens.css"), File("mockup/tokens.css")).first { it.isFile }

    @Test fun `메일 토큰은 tokens_css 라이트 값과 같다`() {
        val css = tokensCss().readText()
        val light = Regex("--ic-([a-z0-9-]+):\\s*light-dark\\((#[0-9A-Fa-f]{6}),")
            .findAll(css).associate { it.groupValues[1] to it.groupValues[2].uppercase() }
        assertTrue(light.size >= MailTheme.tokens.size)
        for ((name, value) in MailTheme.tokens) assertEquals(light[name], value.uppercase(), "--ic-$name")
    }
}

package collector.prompt

import kotlin.test.Test
import kotlin.test.assertEquals

class CriteriaFieldsTest {
    @Test fun onlyFirstTopLevelDashLinePerFieldIsReadAndChanged() {
        val body = listOf(
            "  - 대상 고객: 들여쓰기", "* 대상 고객: 별표", "• 대상 고객: 점", "-\t대상 고객: 탭",
            "- **대상 고객:** 첫 고객", "- 대상 고객: 두 번째", "- 수익화: 첫 방식", "- 수익화 방식: 두 번째"
        ).joinToString("\r\n")
        assertEquals(mapOf("대상 고객" to "첫 고객", "수익화 방식" to "첫 방식"), parseCriteriaFields(body))
        val changes = mapOf("대상 고객" to "새 고객", "수익화 방식" to "새 방식", "관심 분야" to "회계")
        assertEquals(body.replace("첫 고객", "새 고객").replace("첫 방식", "새 방식") + "\r\n- **관심 분야:** 회계\r\n",
            mergeCriteria(body, changes))
    }
}

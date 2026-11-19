package collector.refine

import collector.item
import kotlin.test.Test
import kotlin.test.assertEquals

class ScorerTest {
    @Test
    fun `출처 안 백분위로 0~100을 매긴다`() {
        val items = listOf(
            item(source = "hn", id = "a", rawScore = 50),
            item(source = "hn", id = "b", rawScore = 100),
            item(source = "hn", id = "c", rawScore = 400),
            item(source = "github", id = "g", rawScore = 5000),
        )
        val scores = Scorer.score(items).associate { it.key to it.score }

        assertEquals(mapOf("hn:a" to 0, "hn:b" to 50, "hn:c" to 100, "github:g" to 100), scores)
    }

    @Test
    fun `같은 원점수는 같은 점수`() {
        val scores = Scorer.score(listOf(item(id = "a", rawScore = 10), item(id = "b", rawScore = 10))).map { it.score }
        assertEquals(scores[0], scores[1])
    }
}

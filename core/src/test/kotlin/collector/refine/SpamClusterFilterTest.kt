package collector.refine

import collector.item
import collector.model.DropReason
import kotlin.test.Test
import kotlin.test.assertEquals

class SpamClusterFilterTest {
    private fun repo(owner: String, name: String, desc: String = "desc of $owner/$name") =
        item(source = "github", id = "$owner/$name", title = "$owner/$name", snippet = desc).copy(author = owner)

    @Test
    fun `여러 계정이 같은 이름으로 찍어 낸 저장소는 전부 뺀다`() {
        val items = listOf(
            repo("a", "Gemini-Config-Checker-"), repo("b", "gemini-config-checker-"), repo("c", "Gemini-Config-Checker-"),
            repo("real", "pinky"),
        )
        val result = SpamClusterFilter().filter(items)
        assertEquals(listOf("real/pinky"), result.kept.map { it.externalId })
        assertEquals(setOf(DropReason.SUSPECTED_SPAM), result.dropped.map { it.reason }.toSet())
    }

    @Test
    fun `이름이 달라도 설명이 같은 묶음은 뺀다`() {
        val desc = "Manus AI free download unlimited credits crack"
        val items = listOf(repo("a", "ai--manus", desc), repo("b", "manus--ai", desc), repo("c", "ai--manus2.0", desc))
        assertEquals(0, SpamClusterFilter().filter(items).kept.size)
    }

    @Test
    fun `두 계정까지는 우연으로 보고 남긴다`() {
        val items = listOf(repo("a", "awesome-llm"), repo("b", "awesome-llm"))
        assertEquals(2, SpamClusterFilter().filter(items).kept.size)
    }
}

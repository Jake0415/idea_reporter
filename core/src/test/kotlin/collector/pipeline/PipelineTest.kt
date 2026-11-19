package collector.pipeline

import collector.NOW
import collector.config.GitHubConfig
import collector.config.HnConfig
import collector.config.SourceConfig
import collector.config.SourcesFile
import collector.item
import collector.model.DropReason
import collector.model.Item
import collector.port.SeenStore
import collector.source.SourceAdapter
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class PipelineTest {
    private class FakeAdapter(override val id: String, val block: suspend () -> List<Item>) : SourceAdapter {
        override suspend fun fetch(config: SourceConfig, now: Instant) = block()
    }

    private class MemorySeen(val seenKeys: Set<String> = emptySet()) : SeenStore {
        override suspend fun filterUnseen(items: List<Item>) = items.filter { it.key !in seenKeys }
        override suspend fun markSeen(items: List<Item>) {}
    }

    private val file = SourcesFile(listOf(HnConfig(minRawScore = 50), GitHubConfig(minRawScore = 200)), listOf("crypto"))

    @Test
    fun `수집부터 점수까지 돌고 탈락 이유와 깔때기를 남긴다`() = runTest {
        val hn = FakeAdapter("hn") {
            listOf(
                item(source = "hn", id = "1", url = "https://github.com/a/b", rawScore = 300),
                item(source = "hn", id = "2", rawScore = 10),                      // 점수 미달
                item(source = "hn", id = "3", title = "crypto bot", rawScore = 90), // 키워드
                item(source = "hn", id = "4", rawScore = 120),                     // 이미 봄
            )
        }
        val gh = FakeAdapter("github") { listOf(item(source = "github", id = "9", url = "https://github.com/a/b/", rawScore = 900)) }

        val result = Pipeline(listOf(hn, gh), MemorySeen(setOf("hn:4")), clock = { NOW }, zone = ZoneOffset.UTC).run(file)

        assertEquals(listOf("hn:1"), result.items.map { it.key })
        assertEquals(listOf("github"), result.items[0].alsoSeenIn)
        assertEquals(listOf("tech-signal", "demand-signal", "market-signal"), result.items[0].profileHint)
        assertEquals(
            mapOf(DropReason.LOW_SOURCE_SCORE to 1, DropReason.EXCLUDED_KEYWORD to 1, DropReason.DUPLICATE to 1, DropReason.ALREADY_SEEN to 1),
            result.report.dropCounts
        )
        assertEquals(listOf(5, 3, 1, 1), result.report.stages.map { it.output })
        assertEquals("2026-11-19", result.report.runDate)
    }

    @Test
    fun `이전에 본 대표가 새 글을 중복으로 탈락시키지 않는다`() = runTest {
        val hn = FakeAdapter("hn") {
            listOf(
                item(id = "1", title = "New local inference runtime", rawScore = 300),
                item(id = "2", title = "New local inference runtime", rawScore = 100)
            )
        }
        val result = Pipeline(listOf(hn), MemorySeen(setOf("hn:1")), clock = { NOW }, zone = ZoneOffset.UTC)
            .run(SourcesFile(listOf(HnConfig())))

        assertEquals(listOf("hn:2"), result.items.map { it.key })
        assertEquals(listOf(DropReason.ALREADY_SEEN), result.dropped.map { it.reason })
    }

    @Test
    fun `출처 하나가 실패하거나 느려도 나머지는 계속한다`() = runTest {
        val broken = FakeAdapter("hn") { error("boom") }
        val slow = FakeAdapter("github") { delay(10_000); emptyList() }
        val result = Pipeline(listOf(broken, slow), MemorySeen(), clock = { NOW }, sourceTimeout = 50.milliseconds).run(file)

        assertEquals("boom", result.report.sources.first { it.source == "hn" }.error)
        assertNotNull(result.report.sources.first { it.source == "github" }.error)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `꺼진 출처는 수집하지 않는다`() = runTest {
        val hn = FakeAdapter("hn") { listOf(item(source = "hn", id = "1")) }
        val off = SourcesFile(listOf(HnConfig(enabled = false), GitHubConfig()))
        val result = Pipeline(listOf(hn), MemorySeen(), clock = { NOW }).run(off)

        assertEquals(listOf("github"), result.report.sources.map { it.source })
        assertEquals("어댑터 없음", result.report.sources.single().error)
        assertNull(result.items.firstOrNull())
    }
}

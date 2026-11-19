package collector.stage

import collector.NOW
import collector.config.GitHubConfig
import collector.config.PipelineConfig
import collector.config.SourceConfig
import collector.item
import collector.llm.*
import collector.model.*
import collector.prompt.FilePromptStore
import collector.prompt.PromptResolver
import collector.source.SourceAdapter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class TrackingTest {
    @TempDir lateinit var directory: Path
    private val idea = TrackedIdea("tracked", "card", "Local inference", "dev-tools", listOf("local-inference"),
        emptyList(), TrackedIdea.Status.TRACKING, NOW.minusSeconds(86400), null, 0.0, TrackedIdea.Trend.FLAT)

    @Test
    fun `추적 검색은 기준을 낮추고 스팸과 중복을 제외한 10건에 힌트를 붙인다`() = runTest {
        val adapter = object : SourceAdapter {
            override val id = "github"
            override suspend fun fetch(config: SourceConfig, now: Instant): List<Item> {
                assertEquals(30, config.minRawScore)
                assertEquals(listOf("\"local inference\" in:name,description"), (config as GitHubConfig).queries)
                val normal = (1..11).map { index ->
                    item(id, index.toString(), title = "repository-$index", rawScore = 80 - index)
                }
                val spam = (1..3).map { owner ->
                    item(id, "spam-$owner", title = "$owner/spam", rawScore = 100).copy(author = "owner-$owner")
                }
                return spam + normal.take(1) + normal.first().copy(externalId = "duplicate",
                    url = "http://www.example.com/1/?utm_source=test") + normal.drop(1) +
                    item(id, "low", title = "low", rawScore = 29)
            }
        }

        val result = TrackingSearch(PipelineConfig.defaults.tracking).fetch(idea, GitHubConfig(minRawScore = 100), adapter, NOW)

        assertEquals((1..10).map { "github:$it" }, result.map { it.key })
        assertTrue(result.all { it.trackHint == listOf("tracked") && it.profileHint == listOf("tech-signal") })
    }

    @Test
    fun `관련 항목이 없으면 호출하지 않는다`() = runTest {
        val unrelated = signal(item()).let { it.copy(classification = it.classification.copy(trackedIdeaIds = emptyList())) }
        val updater = updater { error("관련 없는 항목으로 모델을 호출했습니다.") }

        val result = updater.update(idea, null, listOf(unrelated), emptyList(), "2026-11-19")

        assertNull(result.update)
        assertNull(result.failure)
    }

    @Test
    fun `추적 업데이트는 제공된 항목 키만 인용한다`() = runTest {
        val updater = updater {
            """{"summary":"summary","changes":[
                {"kind":"TECH_RELEASE","note":"valid","itemKey":"hn:1"},
                {"kind":"RISK_CHANGE","note":"invalid","itemKey":"hn:unknown"}]}"""
        }

        val result = updater.update(idea, null, listOf(signal(item())), emptyList(), "2026-11-19").update

        assertNotNull(result)
        assertEquals(listOf("hn:1"), result.itemKeys)
        assertEquals("valid", result.changes.single().note)
    }

    @Test
    fun `추적 업데이트는 최근 요약 세 건만 요청에 포함한다`() = runTest {
        val previous = (14..18).map { day ->
            TrackingUpdate("old-$day", idea.id, "2026-11-$day", "previous-$day", emptyList(),
                emptyList(), 0.0, idea.trend)
        }
        val updater = updater { request ->
            val summaries = Json.parseToJsonElement(request.user).jsonObject.getValue("previous").jsonArray
                .map { it.jsonObject.getValue("summary").jsonPrimitive.content }
            assertEquals(listOf("previous-18", "previous-17", "previous-16"), summaries)
            """{"summary":"","changes":[]}"""
        }

        val result = updater.update(idea, null, listOf(signal(item())), previous, "2026-11-19")

        assertNull(result.failure)
    }

    private fun signal(item: Item) = Signal(item,
        ClassifiedItem(item.key, "tech-signal", 8.0, "reason", "summary", emptyList(), "runtime", "runtime", listOf(idea.id)))

    private fun updater(complete: (LlmRequest) -> String): TrackingUpdater {
        val provider = object : LlmProvider {
            override val id = ProviderId.ANTHROPIC
            override suspend fun listModels() = emptyList<ModelInfo>()
            override suspend fun complete(req: LlmRequest) = LlmResult(complete(req), id, req.model, 0, 0, 0, 0.0)
        }
        return TrackingUpdater(PromptResolver(FilePromptStore(directory)), LlmRouter(listOf(provider), CostGuard(0.5)))
    }
}

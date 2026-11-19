package collector.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration

/**
 * 출처별 설정. JSON의 "id" 값으로 타입을 구분한다(ConfigLoader.json의 classDiscriminator).
 * 검색 조건이 사이트마다 달라서 출처마다 타입을 따로 둔다. design.md §9
 */
@Serializable
sealed class SourceConfig {
    abstract val enabled: Boolean
    abstract val minRawScore: Int          // 출처 고유 지표의 최소값 (② 규칙 필터)
    abstract val maxItems: Int
    abstract val profile: List<String>     // 명시 배정 1개 또는 후보 여러 개

    /** 출처 id ("hn", "github" ...). 직렬화되지 않는 계산 속성 */
    abstract val sourceId: String

    /** 이 출처의 수집 기간 */
    abstract fun window(): Duration
}

@Serializable
@SerialName("hn")
data class HnConfig(
    override val enabled: Boolean = true,
    val queries: List<String> = listOf("LLM", "AI agent", "Show HN AI"),
    val windowHours: Int = 24,
    override val minRawScore: Int = 50,
    override val maxItems: Int = 60,
    override val profile: List<String> = listOf("tech-signal", "demand-signal", "market-signal")
) : SourceConfig() {
    override val sourceId: String get() = "hn"
    override fun window(): Duration = Duration.ofHours(windowHours.toLong())
}

@Serializable
@SerialName("github")
data class GitHubConfig(
    override val enabled: Boolean = true,
    val queries: List<String> = listOf("topic:llm", "topic:ai-agent", "topic:generative-ai"),
    val createdWithinDays: Int = 14,
    override val minRawScore: Int = 200,   // 스타 수
    override val maxItems: Int = 50,
    override val profile: List<String> = listOf("tech-signal")
) : SourceConfig() {
    override val sourceId: String get() = "github"
    override fun window(): Duration = Duration.ofDays(createdWithinDays.toLong())
}

/** sources.default.json 전체 */
@Serializable
data class SourcesFile(
    val sources: List<SourceConfig>,
    val excludeKeywords: List<String> = emptyList()
)

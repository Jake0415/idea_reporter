package collector.config

import collector.source.rssPrivatePattern
import collector.source.rssPrivateValues
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Duration

/**
 * 출처별 설정. JSON의 "id" 값으로 타입을 구분한다(ConfigLoader.json의 classDiscriminator).
 * 검색 조건이 사이트마다 달라서 출처마다 타입을 따로 둔다. design.md §9
 */
@Serializable
sealed class SourceConfig {
    abstract val enabled: Boolean
    abstract val minRawScore: Int          // 출처 고유 지표의 최소값 (② 규칙 필터)
    abstract val maxItems: Int             // 거르고 난 뒤 출처당 최대 건수 (② 출처 상한)
    abstract val fetchPerQuery: Int        // 검색어당 받아 오는 수. 스팸이 많아도 maxItems가 차도록 넉넉히
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
    val queries: List<String> = listOf("LLM", "AI agent", "Show HN AI", "GPT", "Claude", "open source model"),
    val windowHours: Int = 24,
    override val minRawScore: Int = 20,
    override val maxItems: Int = 60,
    override val fetchPerQuery: Int = 50,
    override val profile: List<String> = listOf("tech-signal", "demand-signal", "market-signal"),
    val queryOverrides: List<HnQueryOverride> = listOf("Ask HN", "alternative to", "looking for a tool", "frustrated with")
        .map { HnQueryOverride(it, 72, 5) }
) : SourceConfig() {
    override val sourceId: String get() = "hn"
    override fun window(): Duration = Duration.ofHours(windowHours.toLong())

    fun overrideFor(query: String?): HnQueryOverride? = queryOverrides.firstOrNull { it.query == query }
}

@Serializable
data class HnQueryOverride(val query: String, val windowHours: Int, val minRawScore: Int) {
    fun window(): Duration = Duration.ofHours(windowHours.toLong())
}

@Serializable
@SerialName("github")
data class GitHubConfig(
    override val enabled: Boolean = true,
    val queries: List<String> = listOf("topic:llm", "topic:ai-agent", "topic:generative-ai"),
    val createdWithinDays: Int = 14,
    override val minRawScore: Int = 200,   // 스타 수
    override val maxItems: Int = 50,
    override val fetchPerQuery: Int = 100, // 결과의 절반 이상이 스팸일 수 있어 넉넉히
    override val profile: List<String> = listOf("tech-signal")
) : SourceConfig() {
    override val sourceId: String get() = "github"
    override fun window(): Duration = Duration.ofDays(createdWithinDays.toLong())
}

@Serializable
@SerialName("github-issues")
data class GitHubIssuesConfig(
    override val enabled: Boolean = true,
    val queries: List<String> = listOf(
        "ollama", "\"local model\"", "mcp", "\"claude code\"", "vllm", "\"lm studio\"",
        "langchain", "\"open webui\"", "\"ai agent\"", "llm", "\"embedding model\""
    ),
    val titleIntents: Map<String, String> = mapOf(
        "\"local model\"" to "support", "vllm" to "\"add support\"",
        "\"ai agent\"" to "\"feature request\"", "llm" to "support",
        "\"embedding model\"" to "\"please add\""
    ),
    val ambiguousTerms: List<String> = listOf("mcp", "local model", "ai agent", "embedding model"),
    val minPositiveReactions: Int = 1,
    val windowHours: Int = 168,
    val minComments: Int = 2,
    override val minRawScore: Int = 5,
    override val fetchPerQuery: Int = 30,
    override val maxItems: Int = 15,
    val sourceTimeoutSeconds: Int = 90,
    val minRepositoryStars: Int = 20,
    val excludeLabels: List<String> = listOf(
        "good first issue", "hacktoberfest", "up-for-grabs", "first-timers-only", "ready-for-agent",
        "fabrik:", "stage:", "tracking", "epic", "meta"
    ),
    val commentItems: Int = 10,
    val includeDiscussions: Boolean = true,
    val sort: String = "reactions",
    override val profile: List<String> = listOf("demand-signal"),
    val titleSignalWords: List<String> = listOf(
        "support", "add support", "feature request", "please add", "is there a way", "integration", "allow", "option to", "configurable", "would be",
        "bug", "error", "fail", "fails", "failed", "broken", "not working", "doesn't work", "can't",
        "cannot", "unable", "timeout", "crash", "regression", "rejects", "drops", "lost", "403", "429", "500"
    ),
    val excludeTitlePatterns: List<String> = listOf("^track", "follow-up", "tracking issue", "\\bepic\\b", "meta issue", "roadmap")
) : SourceConfig() {
    override val sourceId: String get() = "github-issues"
    override fun window(): Duration = Duration.ofHours(windowHours.toLong())
}

@Serializable
@SerialName("hf")
data class HfConfig(
    override val enabled: Boolean = true,
    override val minRawScore: Int = 0,
    override val maxItems: Int = 30,
    override val fetchPerQuery: Int = 30,
    override val profile: List<String> = listOf("tech-signal"),
    val maxItemsPerKind: Int = 15
) : SourceConfig() {
    override val sourceId: String get() = "hf"
    override fun window(): Duration = Duration.ofDays(365_250)
}

@Serializable
@SerialName("reddit")
data class RedditConfig(
    override val enabled: Boolean = false,
    val subreddits: List<String> = listOf("LocalLLaMA", "SaaS", "ChatGPT", "artificial"),
    val minUpvotes: Int = 100,
    override val maxItems: Int = 30,
    override val fetchPerQuery: Int = 25,
    override val profile: List<String> = listOf("demand-signal"),
    val subredditProfiles: Map<String, List<String>> = mapOf("LocalLLaMA" to listOf("tech-signal", "demand-signal"))
) : SourceConfig() {
    override val sourceId: String get() = "reddit"
    override val minRawScore: Int get() = minUpvotes
    override fun window(): Duration = Duration.ofDays(1)
}

@Serializable
@SerialName("ph")
data class ProductHuntConfig(
    override val enabled: Boolean = false,
    val topic: String = "artificial-intelligence",
    val minVotes: Int = 50,
    override val maxItems: Int = 30,
    override val fetchPerQuery: Int = 30,
    override val profile: List<String> = listOf("market-signal")
) : SourceConfig() {
    override val sourceId: String get() = "ph"
    override val minRawScore: Int get() = minVotes
    override fun window(): Duration = Duration.ofDays(1)
}

@Serializable
data class RssFeed(
    val name: String,
    val url: String,
    @Serializable(with = RssProfilesSerializer::class) val profile: List<String>,
    val maxItems: Int = 10
) {
    constructor(name: String, url: String, profile: String, maxItems: Int = 10) :
        this(name, url, listOf(profile), maxItems)
}

internal object RssProfilesSerializer : JsonTransformingSerializer<List<String>>(ListSerializer(String.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonPrimitive && element.isString) JsonArray(listOf(element)) else element
}

@Serializable
@SerialName("rss")
data class RssConfig(
    override val enabled: Boolean = false,
    val windowHours: Int = 168,
    val feeds: List<RssFeed> = listOf(RssFeed("Hugging Face Blog", "https://huggingface.co/blog/feed.xml", "tech-signal"))
) : SourceConfig() {
    init {
        val names = mutableSetOf<String>()
        for ((index, feed) in feeds.withIndex()) {
            val privatePattern = feed.url.toHttpUrlOrNull()?.rssPrivateValues()?.rssPrivatePattern()
            require(privatePattern?.containsMatchIn(feed.name) != true) {
                "RSS 피드 ${index + 1}번 이름에 URL 비밀값 포함"
            }
            if (!names.add(feed.name)) throw DuplicateRssFeedNameException(feed.name)
        }
    }

    override val sourceId: String get() = "rss"
    override val minRawScore: Int get() = 0
    override val maxItems: Int get() = feeds.sumOf { it.maxItems.coerceAtLeast(0) }
    override val fetchPerQuery: Int get() = feeds.maxOfOrNull { it.maxItems } ?: 0
    override val profile: List<String> get() = feeds.flatMap { it.profile }.distinct()
    override fun window(): Duration = Duration.ofHours(windowHours.toLong())
}

internal class DuplicateRssFeedNameException(name: String) : IllegalArgumentException(
    if ("://" in name || name.any { it.isISOControl() }) "RSS 피드 이름 중복" else "RSS 피드 이름 중복: $name"
)

/** sources.default.json 전체 */
@Serializable
data class SourcesFile(
    val sources: List<SourceConfig>,
    val excludeKeywords: List<String> = emptyList()
)

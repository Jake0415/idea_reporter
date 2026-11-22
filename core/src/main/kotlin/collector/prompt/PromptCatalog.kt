package collector.prompt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class PromptInfo(
    val resourcePath: String,
    val name: String,
    val stage: String,
    val input: String,
    val output: String
)

object PromptCatalog {
    val entries: Map<String, PromptInfo> = buildEntries { path ->
        javaClass.getResourceAsStream("/$path")?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    }

    internal fun buildEntries(read: (String) -> String?): Map<String, PromptInfo> = buildMap {
        put("classify", PromptInfo("prompts/classify.md", "1차 분류", "classify", "수집 항목과 분류 기준", "분류 결과"))
        put("ideas", PromptInfo("prompts/ideas.md", "아이디어 작성", "generate", "카드 후보와 평가 기준", "아이디어 카드"))
        put("analysis-opportunity", PromptInfo("prompts/analysis-opportunity.md", "사업성 분석", "analysis", "테마 지표와 근거", "기회 분야"))
        put("track", PromptInfo("prompts/track.md", "관심 아이디어 추적", "track", "추적 아이디어와 새 항목", "업데이트 요약"))
        put("criteria", PromptInfo("prompts/criteria.default.md", "평가 기준", "generate", "사용자 기준", "카드 작성 지침"))
        for (profile in listOf("tech-signal", "demand-signal", "market-signal")) {
            val name = try {
                read("profiles/$profile/profile.json")?.let { text ->
                    val config = Json.parseToJsonElement(text) as? JsonObject
                    (config?.get("name") as? JsonPrimitive)
                        ?.takeIf { it.isString && it.content.isNotBlank() }?.content
                }
            } catch (_: Exception) {
                null
            } ?: profile
            put("profile.$profile.match", PromptInfo("profiles/$profile/match.md", "$name 포함 기준", "classify", "수집 항목", "분류에 맞는지 여부"))
            put("profile.$profile.analysis", PromptInfo("profiles/$profile/analysis.md", "$name 평가표", "classify", "매칭된 항목", "분류 점수"))
        }
    }

    internal fun requireEntry(id: String): PromptInfo =
        requireNotNull(entries[id]) { "알 수 없는 프롬프트 ID입니다." }

    fun defaultText(id: String): String {
        val path = requireEntry(id).resourcePath
        return checkNotNull(javaClass.getResourceAsStream("/$path")) { "프롬프트 기본값이 없습니다." }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}

package collector.prompt

data class PromptInfo(
    val resourcePath: String,
    val name: String,
    val stage: String,
    val input: String,
    val output: String
)

object PromptCatalog {
    val entries: Map<String, PromptInfo> = buildMap {
        put("classify", PromptInfo("prompts/classify.md", "1차 분류", "classify", "수집 항목과 프로필", "분류 결과"))
        put("ideas", PromptInfo("prompts/ideas.md", "아이디어 작성", "generate", "후보와 평가 기준", "아이디어 카드"))
        put("track", PromptInfo("prompts/track.md", "관심 아이디어 추적", "track", "추적 아이디어와 새 항목", "업데이트 요약"))
        put("criteria", PromptInfo("prompts/criteria.default.md", "평가 기준", "generate", "사용자 기준", "카드 작성 지침"))
        for (profile in listOf("tech-signal", "demand-signal", "market-signal")) {
            put("profile.$profile.match", PromptInfo("profiles/$profile/match.md", "$profile 매칭", "classify", "수집 항목", "프로필 적합 여부"))
            put("profile.$profile.analysis", PromptInfo("profiles/$profile/analysis.md", "$profile 분석", "classify", "매칭된 항목", "프로필 평가"))
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

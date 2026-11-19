package collector.profile

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** profiles/<id>/profile.json. design.md §5 */
@Serializable
data class ProfileConfig(
    val id: String,
    val name: String,                 // 화면 표시 이름(한국어)
    val description: String,
    val threshold: Double,            // ⑤ 이 점수 이상만 후보로 (0~10, 사용자 설정이 덮어씀)
    val maxCandidates: Int,           // ⑤ 이 프로필에서 카드 작성에 넘길 최대 후보 수
    val content: ContentBudget = ContentBudget()
)

/** AI 단계별로 넘길 본문 글자 수와 긴 글 자르는 방식 */
@Serializable
data class ContentBudget(
    val classifyMaxChars: Int = 1000,
    val generateMaxChars: Int = 4000,
    val sampling: Sampling = Sampling.PREFIX
)

@Serializable
enum class Sampling {
    @SerialName("prefix") PREFIX,                     // 앞부분만
    @SerialName("head-middle-tail") HEAD_MIDDLE_TAIL  // 앞·중간·끝 발췌 (긴 글·토론)
}

/** 설정 + 프롬프트 조각(포함 기준, 평가표) */
data class Profile(
    val config: ProfileConfig,
    val match: String,                // match.md
    val analysis: String              // analysis.md
) {
    val id: String get() = config.id
}

@Serializable
data class ProfileIndex(val profiles: List<String>, val default: String)

package collector.model

import kotlinx.serialization.Serializable

/** 항목이 탈락한 이유. 수집함(SC-05)과 출처 통과율(SC-07)에 쓴다. design.md §8 */
@Serializable
enum class DropReason {
    OUT_OF_WINDOW,           // ② 수집 기간 밖
    LOW_SOURCE_SCORE,        // ② 출처별 최소 점수 미달
    EXCLUDED_KEYWORD,        // ② 제외 키워드 포함
    SUSPECTED_SPAM,          // ② 여러 계정이 같은 이름·설명으로 찍어 낸 묶음
    DUPLICATE,               // ③ 같은 URL·비슷한 제목
    ALREADY_SEEN,            // ③ 이전 실행에서 이미 본 글
    BELOW_PROFILE_THRESHOLD, // ⑤ 프로필 임계값 미달 (2단계)
    SAME_TOPIC,              // ⑤ 주제 합치기 (2단계)
    OVER_QUOTA,              // ⑤ 상한 초과 (2단계)
    CLASSIFY_FAILED,         // ④ 분류 실패 (2단계)
    BUDGET_EXCEEDED          // 비용 상한 초과 (2단계)
}

@Serializable
data class DroppedItem(
    val item: Item,
    val reason: DropReason,
    val detail: String = ""   // 예: "점수 12 < 50", "중복: hn:123"
)

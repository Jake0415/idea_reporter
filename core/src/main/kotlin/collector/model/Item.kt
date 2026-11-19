package collector.model

import kotlinx.serialization.Serializable
import java.time.Instant

/** 모든 출처가 같은 형태로 돌려주는 수집 항목. design.md §8 */
@Serializable
data class Item(
    val source: String,                          // "hn", "github", ...
    val externalId: String,                      // 출처 내부 ID
    val title: String,
    val url: String,                             // 원문 URL(정규화 전)
    val rawScore: Int,                           // 출처 고유 지표: HN 점수, GitHub 스타 ...
    val score: Int = 0,                          // Scorer가 출처 안 백분위로 0~100 통일
    @Serializable(with = InstantSerializer::class)
    val publishedAt: Instant,
    val snippet: String = "",                    // 최대 300자, 수집한 본문 일부
    val author: String? = null,                  // 출처 줄 표시용
    val commentCount: Int = 0,
    val discussionUrl: String? = null,           // 토론 페이지가 따로 있는 출처만(HN·Reddit)
    val extra: Map<String, String> = emptyMap(), // 출처별 부가 정보: license, language ...
    val tags: List<String> = emptyList(),
    val alsoSeenIn: List<String> = emptyList(),  // 같은 URL·제목으로 다른 출처에도 등장
    val profileHint: List<String> = emptyList(), // 출처 설정의 명시 배정/후보 프로필
    val comments: List<Comment> = emptyList(),   // 수집한 댓글
    val trackHint: List<String> = emptyList(),
    val mergedUrls: List<String> = emptyList()   // ⑤ 주제 합치기로 붙은 중복 항목 URL(근거 유지)
) {
    /** 출처 안에서 유일한 키 */
    val key: String get() = "$source:$externalId"
}

@Serializable
data class Comment(val author: String, val text: String)

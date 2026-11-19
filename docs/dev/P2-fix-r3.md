# P2 수정 지시 — 3회차 (Claude·OpenAI 합의)

판정: Claude "통과"(낮음 3), OpenAI "수정 필요"(중간 3·낮음 1). 추적 분류 캐시 영역은 양쪽이 함께 지적. 규칙 동일(커밋·docs 수정·키 출력 금지). ideas.md의 라이선스 note 지시는 Claude가 제거함(렌더러가 표시).

## A. 추적 분류 캐시 정리 (공통 영역)
1. **재사용 14일 / 보관 30일 분리** (FullPipeline.kt:118): 분류 결과 재사용은 14일 이내만, 파일 보관·정리는 30일. 경계일 테스트.
2. **새 추적 아이디어 관련성** (FullPipeline.kt:121): 공통 분류 결과(프로필·점수·주제)와 추적 관련성 판정(`trackedIdeaIds`)을 분리 저장. 캐시 적중 항목이라도 **이전에 평가하지 않은 활성 추적 아이디어**가 있으면 그 아이디어들에 대해서만 다시 판정(분류 호출에 `tracked`를 그 아이디어로 한정). 테스트.
3. **카드 후보는 캐시 재사용 제외** (FullPipeline.kt:124-127): 오늘 일반 수집 후보(cardKeys)에 든 항목은 항상 새로 분류.

## B. 채택
4. **[OpenAI 중간] 실패 실행 보관 위치** (Main.kt:132, FileHistorySearch.kt:48): 발행 실패·복원된 실행은 `out/runs/failed/`에 보관하고 `cardRun()`·이력 검색·피드백 대상에서 제외. CLI 실패 경로 회귀 테스트.
5. **[Claude 낮음] 캐시 취소 전파** (Enricher.kt:52-62): 실행 범위 스코프 또는 `Mutex`+Map으로 바꿔 한쪽 취소가 다른 쪽에 전파되지 않게.
6. **[Claude 낮음·품질] HN 상위 댓글 순서**: 댓글 순서를 HN Firebase API(`https://hacker-news.firebaseio.com/v0/item/{id}.json`의 `kids`, 순위순)로 정하고 본문은 Algolia 캐시를 써도 됨. 분류용 상위 댓글 3개 → **5개**(각 300자). 테스트(순서 보존).
7. **[품질] HN 수요 검색어**: `sources.default.json`의 hn queries에 `Ask HN`, `alternative to`, `looking for a tool`, `frustrated with` 추가(기존 유지). Ask HN 항목은 프로필 후보에 demand-signal 포함(기본 후보 3종이므로 그대로). ConfigLoader 테스트 갱신.

## 완료 기준
- `:core:test --rerun-tasks` 통과. 실제 전체 실행 1회($0.50 상한): 카드 수·근거 유형 수, HN 분류(프로필별), 비용 표. 1~7 처리 결과 한 줄씩.

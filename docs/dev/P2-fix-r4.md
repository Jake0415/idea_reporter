# P2 수정 지시 — 4회차 (마무리)

판정: Claude "통과"(낮음 3), OpenAI "수정 필요"(중간 1). Firebase 실패 시 댓글 유실은 양쪽 공통. 규칙 동일(커밋·docs·프롬프트 수정·키 출력 금지, sources.default.json 수정 허용).

1. **[공통] Firebase 순위 조회 실패 대체** (Enricher.kt:71): `ranking == null`이면 Algolia `children` 순서로 댓글 유지(최대 5개). 실패 결과를 빈 댓글로 캐시하지 않음. Firebase만 실패하는 테스트.
2. **[Claude 낮음] 분류 누락 항목 재요청**: 응답에서 빠지거나 중복된 항목만 모아 1회 작은 묶음으로 재요청, 그래도 없으면 `CLASSIFY_FAILED`. 테스트.
3. **[Claude 낮음·품질] 수요형 HN 검색어 조건**: `HnConfig`에 검색어별 조건 `queryOverrides: [{query, windowHours, minRawScore}]` 추가. 기본값: `Ask HN`, `alternative to`, `looking for a tool`, `frustrated with`는 windowHours 72, minRawScore 5. `RuleFilter`가 항목이 들어온 검색어 조건을 쓰도록 항목에 `matchedQuery`(또는 같은 의미의 힌트) 보존. ConfigLoader·RuleFilter 테스트.

완료 기준: `:core:test --rerun-tasks` 통과. 실제 전체 실행 1회 — **이번에는 `--budget 1.0`**(당일 누계가 $0.41이라 상한 $0.50으로는 작성이 막힐 수 있음): 카드 수·근거 유형, HN 분류(프로필별, 수요형 검색어로 들어온 건수 따로), 비용. 1~3 처리 결과 한 줄씩.

# P2 수정 지시 — 2회차 (Claude·OpenAI 합의)

1회차 13건은 두 리뷰어가 대부분 반영 확인. 아래만 고친다. 규칙 동일(커밋·docs 수정·키 출력 금지). **이번 회차는 프롬프트 파일 2개를 Claude가 직접 고쳤다**(classify.md·ideas.md 입력 설명) — 그 내용에 맞춰 코드 입력 필드를 맞출 것.

## A. 공통 영역 — 실행 결과·상태 저장 (OpenAI 중간 + Claude 낮음 d)
1. `Main.kt:130,138` publishRun: 출력(JSON·HTML·TXT)과 상태(metrics·tracked·seen·topic history) 저장을 **하나의 복구 범위**로. 상태 저장 중 실패하면 이전 파일 세트 복원. 회귀 테스트(tracked 저장 실패 → 이전 세트 복원).
2. 카드 0개 재실행이라 결과를 교체하지 않을 때도 **추적 상태(tracked.json)·추적 업데이트·TopicHistory·MetricSnapshot은 저장**한다(카드와 독립). 테스트.

## B. 한쪽 지적, 채택
3. **[OpenAI 중간] 작성 시작 조건** (`IdeaGenerator.kt:27,51`): 대표 항목 유형이 아니라, **모델에 보내고 검증기가 허용하는 근거 목록(대표 + 병합 신호)** 기준으로 "tech-signal 1개 이상 + 2건 이상" 판정. 기술 신호가 병합 항목에만 있는 경우 테스트.
4. **[Claude 중간] HN 분류 입력** (`Enricher.kt:40-54`): snippet에 댓글을 이어 붙이지 말고 분류 입력에 **별도 필드** `storyText`(있으면, 1000자), `topComments`(내용만, 각 300자, 최대 3개)로 보낸다. 저장되는 `Item.snippet`은 원래 값 유지(300자). 작성 단계 signals에도 원래 Item + 보강 필드를 구분해서. (classify.md 입력 설명이 이 필드 이름으로 바뀌었음.)
5. **[Claude 낮음 a] 배지 alsoSeenIn**: "여러 곳에서 화제"는 인용 원본의 `source` + **Deduplicator가 붙인 원래 alsoSeenIn**(Selector 병합분 제외)으로 계산. 테스트.
6. **[Claude 낮음 b]** 추적 업데이트 `previous`는 최근 3개만(track.md 기준).
7. **[Claude 낮음 c]** 추적 검색 결과 중 이미 분류한 항목(무관·none 포함)은 **분류 결과를 키로 재사용**(14일 동안 매일 재분류 방지). `out/track-classified.json` 등에 날짜와 함께 보관, 30일 지나면 정리.
8. **[Claude 낮음 e]** HN Algolia `items/{id}` 응답을 실행 안에서 캐시(분류 전 보강·작성 전 보강 중복 호출 제거).
9. **[OpenAI 낮음] 기술 근거 라이선스**: 작성 signals 입력에 항목의 `license`를 명시 필드로 넣고, HTML·텍스트 리포트의 기술 근거 줄에 라이선스를 표시(수집 데이터 기준, 모델 출력에 의존하지 않음).

## 완료 기준
- `:core:test --rerun-tasks` 통과. 실제 전체 실행 1회($0.50 상한) — 카드 수, 각 카드의 기술/수요/경쟁 근거 수, HN 항목 분류 결과(프로필별 건수), 비용을 표로. 1~9 처리 결과 한 줄씩.

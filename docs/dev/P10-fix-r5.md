# P10 수정 지시 — 5회차(마지막) (실제 재수집: 67 → 2건, 가장 많이 뺀 단계 "의도어 없음")

규칙 동일(커밋·docs 수정·키 출력 금지, 실제 수집·LLM 실행 금지).
4회차의 제목 의도어 로컬 검증이 너무 좁다. GitHub 검색의 OR 조건이 제목을 실제로 제한하지 않아 대부분이 이 단계에서 빠졌고, 3회차 결과에서 적합했던 항목 대부분은 **불편 신고**(Claude Code 429·403, LM Studio 시간 초과, MCP 도구 누락, JSON 파싱 실패)였다. 불편도 수요 신호다.

1. 제목 경로 로컬 검증 단어 집합을 **요청 표현 또는 불편 표현**으로 넓힌다(설정 `titleSignalWords`, 대소문자 무시, 단어 경계):
   - 요청: `support`, `add support`, `feature request`, `please add`, `is there a way`, `integration`, `allow`, `option to`, `configurable`, `would be`
   - 불편: `bug`, `error`, `fail`, `fails`, `failed`, `broken`, `not working`, `doesn't work`, `can't`, `cannot`, `unable`, `timeout`, `crash`, `regression`, `rejects`, `drops`, `lost`, `403`, `429`, `500`
   - 제목에 검색어 그리고 위 단어 중 하나가 있으면 통과. 깔때기 단계 이름은 "요청·불편 표현 없음".
2. 라벨 경로는 지금처럼(라벨이 의도를 보장). 4회차의 나머지 규칙(즉시 실패 스타 0, 과거 채우기 대기, 내부 작업 추적 제외)은 유지.
3. 테스트: 불편 표현만 있는 제목 통과, 둘 다 없는 제목(`Disclose AI/LLM usage`) 제외, 설정 로드.

## 완료 기준
- `:core:test --rerun-tasks` 통과. 1~3 한 줄씩 + 테스트 개수.

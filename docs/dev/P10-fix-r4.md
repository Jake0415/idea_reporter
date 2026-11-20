# P10 수정 지시 — 4회차(마지막 수정) (Claude 통과·낮음 3, OpenAI 높음 0·중간 2·낮음 1)

규칙 동일(커밋·docs 수정·키 출력 금지, 실제 수집·LLM 실행 금지).
실제 재수집 67 → 13건, 적합 약 11건. 약한 항목: `Disclose AI/LLM usage`, `Track follow-up work for the AI/LLM transport refactor`, 제목 의도어 없는 `[Bug]: LLM response is not parseable JSON…`.

1. **[중간] 제목 의도 경로 로컬 검증** (OpenAI) — `title-intent` 경로로 들어온 항목은 제목에 검색어 **그리고** 의도어 집합 중 하나가 실제로 있어야 남긴다(대소문자 무시, 따옴표 구문은 구문 그대로). 없으면 깔때기에 "의도어 없음" 단계로 집계해 제외. 테스트(의도어 없는 응답 항목이 빠짐).
2. **[중간] 저장소 메타데이터 즉시 실패 vs 시간 초과 구분** (OpenAI) — 조회가 404·4xx·5xx로 **즉시 실패**하면 스타 0으로 보고 하한 판정(제외), **시간 초과로 미조회**인 경우만 판정 보류로 포함·경고. 테스트 둘.
3. **[낮음] 과거 채우기 대기 기준 원복** (OpenAI) — 과거 채우기(historical 모드)는 성공 응답에서 `Remaining == 0`일 때만 대기(이전 동작), 일일 모드는 `<= 2` 유지. 테스트.
4. **[낮음] 내부 작업 추적형 제외** (Claude) — 설정 `excludeTitlePatterns`(기본: `^track`, `follow-up`, `tracking issue`, `\bepic\b`, `meta issue`, `roadmap`, 대소문자 무시)와 제외 라벨에 `tracking`, `epic`, `meta` 추가. 테스트.

## 완료 기준
- `:core:test --rerun-tasks` 통과. 1~4 한 줄씩 + 테스트 개수.

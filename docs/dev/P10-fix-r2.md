# P10 수정 지시 — 2회차 (Claude 통과·낮음 2 + 균형 제안, OpenAI 높음 1·중간 3·낮음 1 + 균형 제안)

규칙 동일(커밋·docs 수정·키 출력 금지, 실제 수집·LLM 실행 금지).
실제 재수집: github 5.7초로 복구, github-issues 3건(2건 적합, jsbeeb `embedding` 무관). 목표: 하루 5~10건, 적합률 70% 이상.

## 높음
1. **추적 검색이 정기 저장소 검색보다 한도를 먼저 소진** (OpenAI) — `GitHubSearch`에 **우선순위**를 둔다: 정기 저장소 수집 > 정기 이슈 수집 > 추적 검색. 같은 실행에서 정기 저장소 수집이 끝나기 전에는 추적·이슈 검색이 시작되지 않게(또는 큐 우선순위). 한도 대기는 우선순위 낮은 작업에서만 발생하도록. 테스트: 추적 아이디어 있음 + 한도 헤더(Remaining 낮음)에서 정기 저장소 수집이 45초 안에 끝남.

## 중간
2. **검색 경로 재설계(양·정밀도)** (양쪽):
   - 검색어마다 **두 경로 중 하나**: (a) 라벨 경로 — `<용어> in:title reactions:>=2 label:enhancement,feature,"feature request","type: feature","kind/feature",proposal,idea` (b) 제목 의도 경로 — `<용어> <의도어> in:title reactions:>=2`(의도어: `support`, `"add support"`, `"feature request"`, `"please add"`, `"is there a way"` 중 검색어별로 지정). 서버 반응 하한은 2로.
   - 기본 검색어(다의어 제거, 고유 명사 중심, 이슈 검색 ≤ 12회): `ollama`, `"local model"`, `mcp`, `"claude code"`, `vllm`, `"lm studio"`, `langchain`, `"open webui"`, `"ai agent"`, `llm`, `"embedding model"` — 경로 배정은 설정으로(라벨 경로 6 + 제목 의도 경로 6 = 12 이하가 되게 조합). `copilot`·`rag`·단독 `embedding` 제거.
   - **다의어 방어:** 저장소 캐시에 `topics`·설명 첫 200자 추가. 설정 `ambiguousTerms`(`llm` 제외, 예: `embedding model`, `ai agent`)로 들어온 항목은 저장소 topics·설명에 AI 용어(`llm`, `ai`, `machine-learning`, `deep-learning`, `rag`, `langchain`, `ollama`, `agent`, `generative-ai`, `nlp`)가 있을 때만 남김.
   - 토론 검색: 3회 예산을 검색어 순환 배정(날짜 기반으로 매일 다른 3개) — 모든 검색어가 며칠에 한 번은 토론도 검색. 토론도 같은 두 경로 규칙(라벨 대신 카테고리 `Ideas`/`Feature Requests`/`Q&A`면 라벨 경로로 간주).
   - `windowHours` 72 → **168**.
3. **시간 초과 시 스타·소유자 검사 건너뜀** (OpenAI) — 저장소 메타데이터 검사를 검색 직후 후보마다 바로 적용(캐시에 있는 저장소는 즉시 판정). 시간 초과로 끝나도 **캐시로 판정 가능한 후보는 판정 적용**, 캐시 없는 후보는 "판정 보류"로 포함하되 경고 집계. 테스트(캐시 저스타 후보 + 조회 실패 후보 + 타임아웃).
4. **첫 검색부터 타임아웃이면 성공 0건으로 기록** (OpenAI) — 확보 결과가 0이고 오류/시간 초과로 끝났으면 출처 결과에 `error`(실패)로 기록, 결과가 있으면 부분 성공 + 경고. GitHub 저장소·이슈 출처 둘 다. 테스트.

## 낮음
5. 토론 댓글 정렬: 업보트와 반응을 합산(`upvoteCount + reactions.total`)해 정렬. 테스트(둘 다 오는 응답).
6. 점수식의 긍정 반응과 서버 `reactions:>=2`의 차이는 그대로 두되, 긍정 반응 0인 항목은 `minRawScore`와 별도로 제외(설정 `minPositiveReactions` 1).

## 완료 기준
- `:core:test --rerun-tasks` 통과. 1~6 한 줄씩(2번은 최종 기본 검색어·경로 배정 표) + 테스트 개수.

# P9 — S3 요청형 사업성 분석 (테마 지정)

기준: `docs/business-scout-plan.md` v3 §5(요청형 사업성 분석)·§6(모델), 시안 `mockup/trend.html` SC-20~23. S4(반대 검토·개발 의뢰서·전체 분석·추가 질문)는 이번 범위가 아니다.
규칙: 커밋 금지, docs 수정 금지(이 지시서 제외), 키 출력·로그 금지, **실제 LLM 호출 금지(테스트는 가짜 공급자)**. 새 프롬프트 파일 작성은 허용(아래 4). 작업 폴더 `D:\project\ai-idea-collector`.

## 확인된 모델 정보 (2026-11-20, OpenAI 공식 모델 페이지 developers.openai.com/api/docs/models/gpt-6.2-sol)
- 모델 ID `gpt-6.2-sol`. 100만 토큰당 입력 $2.00, 캐시 입력 $0.10, 출력 $10.00(272K 입력 이하). 272K 초과 요청은 입력·캐시 2배, 출력 1.5배.
- 추론 토큰은 출력으로 청구. 추론 강도 low·medium·high·xhigh·max 지원(`none`은 거부). structured outputs 지원, Responses·Chat Completions 모두 지원. 최대 출력 128K.

## 할 일
1. **모델·가격·설정**
   - `models.default.json`에 `gpt-6.2-sol`(OPENAI, inputUsd 2.00, cachedInputUsd 0.10, outputUsd 10.00, `priceVerified: true`) 추가. 272K 초과 할증은 추정 비용 계산에서 반영(입력 추정이 272K를 넘으면 할증 단가)하고, 실제 비용 계산도 가능하면 반영.
   - 설정 `analysis` 블록(PipelineConfig 또는 별도 `analysis.default.json`): `model`(OPENAI/gpt-6.2-sol), `effort`(HIGH), `maxOutputTokens`(32000), `timeoutMillis`(600000), `requestBudgetUsd`(1.00), `monthlyBudgetUsd`(5.00), `threshold`(3.5), 가중치(수요 강도 0.25·지불 의사 0.20·시기 0.15·경쟁 공백 0.15·1인 실현성 0.15·방어력 0.10), 관문 수치(수요 근거 2, 출처 2, 데이터 14일), 허용 라이선스는 트렌드 설정 재사용.
   - `LlmRouter`에 단계 `analysis` 추가: 설정 모델로 라우팅, **대체 공급자 재시도 없음**(사용자 결정: 분석은 OpenAI만). 추론 강도는 설정값. OpenAiProvider가 이 모델에 `reasoning_effort`(또는 Responses API의 `reasoning.effort`)와 JSON 스키마 구조화 출력을 정상 전달하는지 확인하고, 필요하면 고친다(기존 동작 회귀 금지).
   - **예산 분리:** 분석 비용은 일일 카드 예산과 별도. 별도 원장 `out/cost-ledger-analysis`(기존 FileCostLedger 재사용)로 요청 1건 상한·월 상한을 사전 예약 방식으로 지킨다. 일일 카드 파이프라인 원장에는 넣지 않는다.
2. **입력 만들기** `collector.analysis.AnalysisInputBuilder`
   - 기준 트렌드 리포트: `out/trend/<날짜>.json`(기본 가장 최근). 요청한 테마 ID(1~3개, 리포트의 themes에 있어야 함; 없으면 오류).
   - 테마마다: 리포트의 지표(주별 건수·증가율·신규·출처·수요·시장·OSS 속도·라이선스·언어·과열·내 반응·예시)와 **근거 항목**(리포트 evidence + 같은 테마의 이번 주·지난 3주 관측에서 점수 상위, 프로필별 최대 5개, 테마당 최대 15개). 근거 항목은 실행 파일에서 찾아 `itemId`, 출처, 제목, URL, 프로필, 점수, 분류 요약, 본문 앞 600자, 상위 댓글 3개(작성자·내용 300자), GitHub이면 스타·라이선스·언어를 담는다. 입력 크기 상한(문자 수 설정)을 넘으면 근거를 점수 낮은 순으로 줄인다.
   - 함께: `criteria`(프롬프트 저장소의 criteria), 최근 4주 카드 제목·분류, 피드백(★·관심 없음 이유), 데이터 상태(기간·데이터 부족·과거 채우기 일수).
3. **비용 추정·확인** — 입력 토큰 ≈ 문자 수 ÷ 3(한글 섞임 보수적), 출력 추정 = `maxOutputTokens`의 50%(추론 포함)로 상수화, 단가표로 계산. `Main --analyze`는 **`--yes` 없으면** 추정 비용·이번 달 사용액/상한·요청 상한을 출력하고 끝(호출 0회). 월 상한 또는 요청 상한을 넘을 추정이면 `--yes`여도 실행하지 않고 이유 출력.
   - 같은 트렌드 리포트 날짜 + 같은 테마 집합의 분석이 이미 있으면 그 결과 경로와 요약을 출력하고 끝(`--force`면 새로 실행).
4. **프롬프트** `prompts/analysis-opportunity.md`(새 기본 프롬프트, 사용자 수정 가능 — 기존 프롬프트 저장소·해시·버전 규칙 그대로 등록). 내용(한국어): 역할(1인 개발자에게 개발을 의뢰할 AI 사업 발굴 담당), 입력 설명, 할 일 — 테마당 기회 분야 1~2개를 "뜨는 기술 × 사람들의 불편 × 비어 있는 시장"으로 정리하고 6항목 평가(1~5점, 항목마다 한 줄 근거와 인용), 쓰는 규칙(입력에 있는 itemId·URL만 인용, 숫자는 입력 지표만, 업체·커뮤니티 주장 구분, 근거 없으면 낮은 점수와 "근거 부족", 억지로 높이지 말 것, `criteria` 제외 분야면 `excludedByCriteria: true`), "무엇이 바뀌면 의뢰 가능한가" 한 줄. 출력은 구조화 출력 스키마(코드가 강제).
   - 출력 스키마(Opportunity): `themeId`, `name`, `targetCustomer`, `problem`, `whyNow`, `payer`, `materials`(refs), `demandRefs`, `techRefs`, `marketRefs`(refs = itemId·url·note), `scores`(6개 고정 키: demand·willingnessToPay·timing·competitionGap·soloFeasibility·defensibility, 각 score·reason·refs), `excludedByCriteria`, `excludedReason`, `whatWouldChange`, `risks`(목록).
5. **검증·관문·점수** `collector.analysis.OpportunityJudge`
   - 인용 검증: 입력 근거 집합의 (itemId, url)만 인정. 무효 인용은 제거하고 개수를 사유별로 기록(P6 카드 탈락 사유 방식). 블록-프로필 일치(demandRefs는 demand-signal 등) 검사.
   - 점수 검증: 6개 키 모두, 1~5 정수로 보정(범위 밖은 보정 경고), 누락이면 그 기회 분야는 `INVALID`로 기록하고 목록에서는 이유와 함께 맨 아래.
   - 관문(코드): 유효 수요 근거 ≥ 2(서로 다른 itemId), 인용 출처 종류 ≥ 2, 데이터 일수 < 14면 `잠정` 표시(통과는 가능), 재료 중 GitHub 항목이 있으면 허용 라이선스 ≥ 1, `excludedByCriteria`면 탈락. 결과는 통과/탈락 사유 목록.
   - 최종 점수 = 가중 합(코드, 소수 1자리). 결정: 관문 통과 && 점수 ≥ threshold → `REQUEST_CANDIDATE`(S4에서 반대 검토·의뢰서), 아니면 `NOT_VIABLE` + 이유(가장 약한 항목 또는 관문 사유). 분석 전체 판단 = 후보가 있으면 "의뢰 후보 n건(반대 검토·의뢰서는 다음 단계)", 없으면 "사업성 미달" + 가장 가까운 분야와 모자란 점.
6. **저장·표시**
   - `out/analysis/<분석ID>.json`(`AnalysisRun`: id(날짜-시각-짧은 해시), createdAt, trendDate, themes, model, effort, 추정 비용, 실제 비용, LLM 호출 기록, 입력 요약(근거 수·문자 수), 기회 분야 목록(원문·검증 결과·관문·점수·결정·무효 인용 집계), 전체 판단, issues). 원자 저장.
   - 결과 리포트 `.txt`·`.html`: 판단 칸 → 점수 순 목록(한 줄: 점수 · 이름 · `의뢰 후보` 또는 `가장 약함: 항목(점수)` / `관문 탈락: 사유`) → 사용량·비용. HTML escape.
   - 간단 보기 `Main --analysis-detail <분석ID> <번호|기회 ID>`: 저장분만으로 출력(LLM 0회) — 한 줄 요약·대상 고객, 6항목 점수와 근거, 관문 결과, 근거 링크 최대 3개, 무엇이 바뀌면 의뢰 가능한가. `Main --analysis-list`: 지난 분석 목록(날짜·테마·판단·비용, 이번 달 합계/상한).
7. **PC 실행** `Main --analyze <테마ID…> [--date yyyy-MM-dd] [--yes] [--force]` — 실행 잠금, OpenAI 키는 기존 비밀값 공급자(`OPENAI_API_KEY`), 키 없으면 명확한 오류. 사용법 문구 갱신.

## 테스트 (가짜 공급자)
- 라우팅: `analysis` 단계가 OpenAI sol·effort HIGH로 가고 대체 공급자로 넘어가지 않음. OpenAiProvider 요청 본문에 추론 강도·JSON 스키마가 들어감.
- 가격표·272K 할증 추정, `--yes` 없으면 호출 0회, 월/요청 상한 초과 추정이면 실행 안 함, 별도 원장(일일 원장 불변).
- 입력: 테마 없음 오류, 근거 수 상한·크기 상한 축소, 실행 파일에서 근거 상세 채움.
- 검증: 무효 인용 제거·집계, 블록-프로필 불일치, 점수 보정·누락 INVALID.
- 관문 각각, 가중 합, 결정·전체 판단(후보 있음/미달).
- 중복 분석 재사용·`--force`, 저장·렌더(escape), 간단 보기·목록이 LLM 0회.

## 완료 기준
- `:core:test --rerun-tasks` 통과. 1~7 한 줄씩 + 테스트 개수.

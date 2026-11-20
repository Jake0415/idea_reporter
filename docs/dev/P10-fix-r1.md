# P10 수정 지시 — 1회차 (Claude 중간 2·낮음 3, OpenAI 높음 1·중간 3·낮음 1 + 실제 수집 품질)

규칙 동일(커밋·docs 수정·키 출력 금지, 실제 수집·LLM 실행 금지 — 테스트는 MockWebServer).

실제 수집(out/2026-11-20-collect.json, github-issues 67건): 제목에 AI 용어가 있는 이슈 10건(15%)뿐 — GitHub 이슈 검색이 기본으로 댓글까지 검색해 댓글 속 `llm` 한 단어로도 걸림. 반응 0개 28건(42%)이고 상위권(댓글 128·68·59·47)이 전부 개인 저장소의 AI 에이전트 작업 메모. Bitwarden·Windows 시작 화면·.NET 빌드 등 무관 이슈 다수.

## 높음
1. **공유 속도 제한이 일일 GitHub 저장소 수집을 실패시킬 수 있음** (양쪽) — 실제로 github 수집 5.8초 → 17초, 토큰 없으면 45초 제한 초과 가능.
   - 고정 요청 간격을 없애고 **응답 헤더 기반**으로: `X-RateLimit-Remaining` ≤ 2이거나 403·429일 때만 리셋/`Retry-After`까지 대기. 뮤텍스는 요청 순서만 정하고 대기 중 다른 출처를 막지 않게(또는 대기는 해당 출처 코루틴에서만).
   - **일일 GitHub 저장소 검색 우선:** 이슈 출처는 같은 실행의 GitHub 저장소 수집이 끝난 뒤 시작하거나(파이프라인에서 순서 보장), 저장소 검색 요청이 큐에서 우선.
   - 기존 GitHub 저장소 수집은 시간 초과·대기가 나도 **그때까지의 부분 결과를 반환**.
   - 테스트(가짜 시계): 토큰 없이 이슈 6건 + 저장소 3건 동시 → 저장소 수집이 45초 안에 끝남, 긴 `Retry-After`에서도 저장소 부분 결과 보존.

## 중간
2. **정밀도** (양쪽, 실제 데이터 기준)
   - 검색어 재설계(`in:title`, AI 용어는 제목에서만, 요청 의도 조건, 서버 쪽 반응 하한): 기본 `queries`를
     `llm in:title reactions:>=1`, `ollama in:title reactions:>=1`, `"ai agent" in:title reactions:>=1`, `mcp in:title reactions:>=1`, `"claude code" in:title reactions:>=1`, `"local model" in:title reactions:>=1`, `rag in:title reactions:>=1`, `embedding in:title reactions:>=1`, `copilot in:title reactions:>=1`
     로 하고, 공통 조건으로 `label:enhancement,"feature request",feature` (라벨 OR)를 붙인 검색 1회 + 라벨 없이 `"feature request" OR "support for" OR "please add" OR "is there a way" in:title` 조건 검색 1회 중 어느 방식이 GitHub 문법상 유효한지 테스트로 고정하고 택한다(검색어당 요청 수가 늘면 검색어 수를 줄여 하루 총 검색 요청 ≤ 15).
   - 점수식 `rawScore = 2 × 긍정 반응(+1·heart·hooray·rocket, 토론은 upvote) + min(댓글 수, 10)`.
   - **저장소 스타 하한 50 + 캐시:** 남은 이슈의 고유 저장소를 `GET /repos/{owner}/{repo}`(코어 API, 검색 한도와 별개)로 조회해 `out/cache/github-repos.json`에 7일 보관(스타·언어·라이선스·소유자 유형만, 이슈 작성자 정보 없음). 조회 실패는 스타 0과 구분(실패면 하한 판정 보류 → 포함하되 경고). 스타 50 미만 제외.
   - **소유자 자기 메모 제외:** 수집 중에만 작성자 로그인과 저장소 소유자를 비교해, 작성자 = 소유자(개인 계정)이고 스타 < 50이면 제외(외부 사용자의 긍정 반응 ≥ 3이면 예외). 로그인은 비교 후 버림.
   - **제외 라벨(설정 `excludeLabels`):** `good first issue`, `hacktoberfest`, `up-for-grabs`, `first-timers-only`, `ready-for-agent`, 접두 `fabrik:`, `stage:`.
   - `maxItems` 40 → **15**, `minRawScore` 5 유지, `minComments` 2 유지.
   - 결과 항목에 어떤 검색어로 들어왔는지 `extra.matchedQuery` 기록(품질 분석용).
3. **시간 제한** (OpenAI·Claude 낮음) — 이슈 출처도 파이프라인 출처 시간 제한을 받되, 별도 설정값(`sourceTimeoutSeconds` 출처별, github-issues 기본 60초)을 쓰고 시간 초과 시 그때까지 결과 반환. `created:>=` 시각은 초 단위 `YYYY-MM-DDTHH:MM:SSZ`.
4. **분석 출력 라벨** (OpenAI) — 분석 텍스트·HTML 출력 인용에 `sourceLabel` 표시(`GitHub 이슈 · owner/repo`). 분석 출처 다양성 관문이 `hn`과 `github-issues`를 서로 다른 출처로 셈을 테스트.

## 낮음
5. 댓글은 최대 10개 후보를 받아 봇 제거 후, 반응 수가 있으면 그 순으로 3개 선택. GraphQL 응답에 `data`와 `errors`가 함께 오면 `data`를 쓰고 경고. 쿼리의 OR가 의도대로 묶이도록 Claude·OpenAI 같은 모델명 조건은 독립 쿼리로.

## 완료 기준
- `:core:test --rerun-tasks` 통과. 1~5 한 줄씩 + 테스트 개수.

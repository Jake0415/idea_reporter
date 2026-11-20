# P8 — S2 과거 4주 채우기 (HN·GitHub)

기준: `docs/business-scout-plan.md` §4-1(과거 채우기), S1 트렌드 코드 `collector.trend`.
목적: 데이터가 2일뿐이라 트렌드 리포트가 "데이터 부족". HN·GitHub는 API로 과거를 조회할 수 있으니 지난 4주를 채워 증가율·◆가 바로 나오게 한다.
규칙: 커밋 금지, docs·프롬프트 수정 금지(이 지시서 제외), 키 출력·로그 금지, **실제 수집·LLM 실행 금지(테스트는 MockWebServer·가짜 공급자)**. 작업 폴더 `D:\project\ai-idea-collector`.

## 할 일
1. **저장 위치·분리** — 채운 데이터는 `out/backfill/<yyyy-MM-dd>.json`(그날 생성/게시된 항목, `PipelineResult` 형식: `items`, `classified`, `dropped`, `report`(runDate, startedAt, 비용·LLM 호출 기록, `issues`)). 매일 파이프라인의 기록(`seen.json`, `out/<날짜>.json`, `metrics.json`, 카드·추적·피드백)은 **절대 건드리지 않는다**. FileHistorySearch(카드 작성용 과거 기록)는 backfill 폴더를 읽지 않는다(지금처럼).
2. **TrendInput 연동** — 날짜별 대표 관측: 그날 실제 실행(본 파일·state·runs)이 있으면 그것, **없을 때만** backfill 파일. backfill도 재등장 원본 분류 색인에 포함(실제 실행보다 후순위가 아니라 가장 이른 분류 규칙 그대로). 리포트 데이터 상태에 "과거 채우기 n일(HN·GitHub만, 점수는 채운 시점 기준)" 표시, `TrendReport`에 `backfillDays` 필드(기본 0).
   - 증가율 공정성: backfill 날은 HN·GitHub만 있으므로, **증가율·◆·시장 급증 계산에 쓰는 주별 일평균은 출처를 맞춘다** — 비교 대상 주 중 하나라도 backfill 날이 섞이면, 그 테마의 증가율은 HN·GitHub 항목만으로 계산한다(표의 건수 칸은 전체 그대로, 증가율 칸 옆에 `HN·GH 기준` 표시). 테스트.
3. **수집기** `collector.backfill.Backfill`
   - 기간: `--backfill 4w`(또는 `Nd`) → 기준일 전날부터 과거로 N일. 이미 실제 실행이나 backfill 파일이 있는 날은 건너뜀(`--force`면 backfill 파일만 다시 만듦, 실제 실행 날은 항상 건너뜀).
   - **HN:** 기존 `HackerNewsAdapter`의 검색어·`queryOverrides`·`minRawScore`·`fetchPerQuery` 설정을 그대로 쓰되, 시간 창을 그날 00:00~24:00(UTC)로 지정하는 경로를 추가(Algolia `numericFilters=created_at_i>…,created_at_i<…`). 기존 일일 동작은 바뀌지 않아야 한다.
   - **GitHub:** 기존 `GitHubAdapter` 검색어에 `created:<그날>`을 붙여 하루 단위로 조회, `minRawScore`·`fetchPerQuery` 설정 그대로. 토큰은 기존 비밀값 공급자(`GITHUB_TOKEN`, 환경 변수 → .env)를 쓰고, **응답 헤더 `X-RateLimit-Remaining`/`Reset`과 403·429를 존중해 대기**(검색 API는 분당 제한이 작다). 토큰 없으면 경고 후 더 느리게.
   - 정제는 일일 파이프라인과 같은 순서로: 규칙 필터·스팸 묶음·중복 제거·출처 상한(SourceCap). **이미 본 항목 필터(seen)는 적용하지 않는다.**
   - 하루 단위로 수집 결과를 `out/backfill/collected/<날짜>.json`에 먼저 저장(재실행 시 재사용, `--force`면 다시 수집).
4. **분류(비용 발생)** — 기존 `Classifier`(분류 단계 모델·라우팅·묶음·재요청·예산 사전 예약 그대로)로 날짜별 분류. 추적·카드·보강·작성 단계는 하지 않는다.
   - **`--dry-run`:** 수집만 하고(수집 파일 저장) 분류는 하지 않은 채, 날짜별 항목 수·합계와 **추정 분류 비용**(가격표 × 항목당 평균 입력·출력 토큰 추정, 계산식은 코드 상수로 두고 테스트)을 출력한다.
   - **분류 실행은 `--yes`가 있어야만** 한다. 없으면 dry-run과 같은 출력 후 "실행하려면 --yes" 안내로 끝남.
   - 예산: `--budget <usd>`(기본은 일일 예산과 별도의 `backfillBudgetUsd` 기본 0.5). 예산이 막히면 그때까지 끝난 날짜는 저장하고 나머지는 다음 실행으로(이어 하기). 비용은 backfill 파일 `report`와 콘솔에 날짜별·합계로 남긴다. 이 비용은 일일 비용 원장(CostGuard 당일 누계)에 **포함**한다(실제 지출이므로).
   - 날짜별 결과는 원자 저장, 분류 실패 항목은 `dropped`(CLASSIFY_FAILED·BUDGET_EXCEEDED)로 남김.
5. **PC 실행** `Main --backfill <4w|Nd> [--dry-run] [--yes] [--force] [--budget <usd>] [--date yyyy-MM-dd]` — 실행 잠금 사용, 끝에 요약 한 줄(채운 날 수·항목 수·비용). 사용법 문구 갱신. 끝나면 `--trend`를 자동 실행하지는 않는다(안내만).

## 테스트
- HN 시간 창 요청 파라미터, GitHub `created:` 쿼리·속도 제한 헤더 대기(가짜 시계), 토큰 없음 경고.
- 실제 실행 날 건너뜀, 기존 backfill 날 건너뜀/`--force`, 수집 파일 재사용.
- seen 미적용, 일일 파이프라인 파일 미변경(seen.json·metrics.json·out/<날짜>.json 그대로).
- dry-run은 분류 호출 0회 + 추정 비용 출력, `--yes` 없으면 분류 안 함.
- 예산 차단 시 끝난 날만 저장, 다음 실행에서 이어 함.
- TrendInput: 실제 실행 우선·backfill 대체, backfillDays, 출처 맞춘 증가율(`HN·GH 기준`), 재등장 색인 포함.
- 기존 HN·GitHub 일일 동작 회귀 없음.

## 완료 기준
- `:core:test --rerun-tasks` 통과. 1~5 한 줄씩 + 테스트 개수.

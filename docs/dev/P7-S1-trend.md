# P7 — S1 기술 트렌드 리포트 (코드만, LLM 0원)

기준 문서: `docs/business-scout-plan.md` §4(① 기술 트렌드 리포트), 화면 참고 `mockup/trend.html`(SC-17·18·19, M-02).
규칙: 커밋 금지, docs·프롬프트 수정 금지(이 지시서 제외), 키 출력 금지, **LLM 호출 금지(이 단계는 코드만)**, 실제 수집 실행 금지. 작업 폴더 `D:\project\ai-idea-collector`.

## 설계 결정 (플랜 §4-1 조정)
- **일별 집계 파일을 따로 만들지 않는다.** 필요한 데이터가 이미 실행 결과에 있다: `out/<yyyy-MM-dd>.json`(PipelineResult: `classified`, `items`, `dropped`, `cards`), `out/metrics.json`(MetricSnapshot 30일, 이미 본 항목 포함 모든 수집 항목), `out/feedback.json`. 리포트는 이 파일들을 읽어 계산한다. 매일 파이프라인 코드는 바꾸지 않는다.
- 실행 파일은 이름이 정확히 `yyyy-MM-dd.json`인 것만 읽는다(`-collect.json`, `-run1.json` 등 무시). 같은 날 상태 파일(`out/state/<날짜>.json`)이 있으면 그것을 우선(FileHistorySearch.stateRuns와 같은 규칙).
- **재등장:** `dropped`의 `ALREADY_SEEN` 항목은 그 itemKey가 처음 분류된 실행의 `classified`에서 프로필·주제를 찾아 같은 테마로 센다(못 찾으면 테마 없음으로 제외하고 개수만 데이터 상태에 표시).

## 할 일
1. **패키지 `collector.trend`** (core, 순수 Kotlin)
   - `TrendInput` 읽기: 기준일(기본 오늘)부터 과거 28일 실행 파일, metrics.json, feedback.json, 기존 themes.json.
   - `ThemeMapper`(코드 묶기): 주제 키(이미 정규화됨)를 테마로 묶는다. 규칙 — (a) 주제명 단어(불용어·2자 이하 제외) Jaccard ≥ 0.5, 또는 (b) 의미 있는 태그 2개 이상 공유(GitHub topics·HF 태그·분류 태그). union-find로 묶고, 테마 이름은 가장 많이 나온 주제명, 테마 ID는 결정적(대표 주제 키 기반). 결과를 `out/trend/themes.json`에 `aliases: {topicKey: themeId}`, `names: {themeId: name}`, `overrides: {topicKey: themeId}`(사용자 수정, 있으면 최우선)로 저장하고 다음 실행에서 기존 매핑을 유지(이미 배정된 주제 키는 테마를 바꾸지 않음).
   - `TrendAnalyzer`(순수 함수): 이번 주(기준일 포함 7일)와 그 앞 3주, 테마별:
     - 주별 항목 수 4개(새 분류 + 재등장, itemKey 기준 중복 없음), 새 항목 수
     - 증가율 = 이번 주 ÷ 앞 3주 평균. 앞 3주 합이 0이면 "신규". **데이터 기간이 14일 미만이면 증가율은 null + 리포트 전체에 `insufficientData` 표시**
     - 출처 종류, 수요(demand-signal) 수, 시장(market-signal) 수
     - OSS 속도: 테마의 GitHub 항목별 하루 스타 증가 — 이번 주 안의 스냅샷 2개 이상이면 (마지막-처음)/일수, 아니면 rawScore ÷ 생성 후 일수(최소 1일). 중앙값. 포크/스타 비율 중앙값(extra `forks`)
     - 라이선스 분포(허용: MIT, Apache-2.0, BSD-2/3-Clause, ISC, MPL-2.0), 언어 분포
     - 내 반응: 이 테마 항목을 인용한 카드에 대한 ★·관심 없음 수(feedback.json)
     - 대표 근거: 프로필별 점수 상위 3개(itemKey, 제목, URL, 출처, 점수) — 다음 단계(사업성 분석) 입력용
   - 지는 테마: 앞 3주 평균 ≥ 2 이고 증가율 ≤ 0.8, 하락 큰 순 5개. 뜨는 테마: 이번 주 ≥ 2건, 증가율(신규는 최상위) 순 10개.
   - **오픈소스 트렌드:** 이번 주 GitHub 항목(새+재등장) 하루 스타 증가 상위 10(이름·설명·언어·라이선스·포크 비율·테마), 언어·라이선스 분포.
   - **과열:** (a) 이번 주 `SUSPECTED_SPAM` 탈락을 같은 이름(소유자 제외 저장소 이름)·설명으로 묶어 20개 이상인 묶음(이름, 개수, 날짜), (b) 테마의 시장 신호가 앞 3주 평균의 3배 이상이고 3건 이상이면 테마에 `overheated`.
   - `CandidateMarker`(◆): 증가율 ≥ 1.5(신규 포함) **그리고** 수요 ≥ 1 **그리고** 출처 ≥ 2 **그리고** 과열 아님. 증가율 null(데이터 부족)이면 ◆ 없음.
   - 숫자 기준은 `trend.default.json` 리소스(설정 클래스 `TrendConfig`)로 둔다: 기간 28일·주 7일, 최소 데이터 14일, Jaccard 0.5, 공유 태그 2, 과열 묶음 20, 시장 급증 3배·3건, ◆ 1.5·1·2, 상위 개수 10·5·10, 허용 라이선스 목록.
2. **결과 저장** `out/trend/<기준일>.json`: `TrendReport`(기준일, 기간, insufficientData, dataDays, 테마 행 목록, 지는 테마, 저장소 목록, 언어·라이선스 분포, 과열 목록, 요약 3줄, 재등장 중 테마 못 찾은 개수). 직렬화 가능, 기본값으로 이후 필드 추가 호환.
3. **리포트 렌더러** `TrendReportRenderer`: HTML(메일용 인라인 스타일, 기존 HtmlRenderer처럼 모든 문자열 escape)과 텍스트. 구성은 플랜 §4-5·시안 M-02: 제목 `[기술 트렌드] <M월 N주> — 뜨는 테마 n · 분석 후보 ◆m`, 요약 3줄(코드 템플릿: 가장 뜬 테마, 가장 빠른 저장소, 과열 경고 — 해당 없으면 줄 생략), 뜨는 테마 표, 지는 테마, 저장소 표, 언어·라이선스, 과열, 데이터 상태(기간·데이터 부족 문구·재등장 미매핑 수). 데이터 부족이면 증가율 칸에 "—"와 상단 안내.
4. **PC 실행** `Main --trend [--date yyyy-MM-dd]`: 위 계산 → `out/trend/<기준일>.json/.html/.txt` 원자 저장, themes.json 갱신, 콘솔에 제목 한 줄. LLM·네트워크 사용 없음. 사용법 문구에 추가.

## 테스트
- 4주치 가상 실행 파일(임시 폴더)로: 주별 개수·증가율·신규·지는 테마 판정, 재등장 매핑(이전 실행 분류로 테마 배정), 파일 이름 필터(`-collect`, `-run1` 무시), state 파일 우선.
- ThemeMapper: 단어 겹침·태그 공유로 묶임, 결정적 ID, 기존 themes.json 매핑 유지, overrides 우선.
- 데이터 13일 → 증가율 null·insufficientData·◆ 없음.
- OSS 속도(스냅샷 2개 / 생성일 대체), 포크 비율, 라이선스·언어 분포.
- 과열: 스팸 묶음 20개 기준, 시장 급증 테마.
- ◆ 조건 각각의 경계.
- 렌더러: 제목 형식, HTML escape(테마 이름에 `<script>`), 데이터 부족 문구.
- Main `--trend`가 LLM 공급자 없이(키 없이) 동작.

## 완료 기준
- `:core:test --rerun-tasks` 통과. 1~4 한 줄씩 + 테스트 개수 보고.

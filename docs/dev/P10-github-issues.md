# P10 — 수요 신호 출처: GitHub 이슈·토론 검색

배경: Reddit은 Data API 사전 승인·마이닝 금지로 보류(design.md §9). 공식 약관 검토 결과 GitHub 이슈·토론 검색이 1순위(API 수집은 금지 대상 아님, AI 조항은 상용 모델 학습에만 해당, 개인정보는 최소 저장). 사업성 분석이 출처 다양성 관문에서 HN 하나로 떨어지는 문제를 줄이는 것이 목적.
규칙: 커밋 금지, docs 수정 금지(이 지시서 제외), 키 출력·로그 금지, **실제 수집·LLM 실행 금지(테스트는 MockWebServer)**. 작업 폴더 `D:\project\ai-idea-collector`.

## 할 일
1. **어댑터** `collector.source.GitHubIssuesAdapter`, 출처 ID `github-issues`(표시 이름 "GitHub 이슈").
   - **이슈:** REST `GET /search/issues`, 검색어마다 `is:issue` + 설정 검색어 + `created:>=<기준 시각 − windowHours>` + `comments:>=<minComments>`, `sort=reactions`(설정), `per_page`=fetchPerQuery. PR 제외(`pull_request` 필드 있으면 버림).
   - **토론:** GraphQL `search(type: DISCUSSION, query: …)`로 같은 검색어(토큰이 있을 때만; 없으면 토론은 건너뛰고 경고 한 줄 — 이슈는 계속).
   - 점수 `rawScore` = 반응 수(reactions.total_count / discussion reactions·upvote) + 댓글 수. `minRawScore` 미만 버림.
   - 항목: 제목, URL(html_url), 게시 시각, 저장소 이름(`owner/repo`)과 저장소 스타가 응답에 있으면 extra, 라벨 목록 → `tags`, 본문 앞 3,000자(`extra.body`, 코드 블록은 줄여서), `profileHint` = 설정(기본 `demand-signal`).
   - **상위 댓글:** 점수 상위 `commentItems`개(기본 10) 항목만 REST로 댓글 최대 3개(반응 많은 순 가능하면, 아니면 처음 3개) 가져와 `comments`에 넣는다(각 500자).
   - **개인정보 최소화(약관 §8):** 작성자 로그인·아바타·프로필 URL은 **저장하지 않는다**(`author = null`, 댓글 작성자는 `"익명"`). 본문·댓글 안의 `@멘션`은 `@user`로 바꾼다. 봇 계정(`[bot]` 접미사, `type == Bot`) 글·댓글 제외.
   - 속도 제한: 기존 GitHubAdapter의 검색 대기 로직(`X-RateLimit-Remaining/Reset`, `Retry-After`, 403·429, 요청 간격)을 재사용·공유. 토큰은 기존 `GITHUB_TOKEN` 비밀값 공급자. 검색 응답 `incomplete_results: true`면 결과는 쓰되 실행 기록 경고(일일 수집 규칙과 같게).
   - 실패 격리: 검색어 하나 실패해도 나머지 계속, 출처 결과에 부분 실패 경고.
2. **설정** `sources.default.json`에 `github-issues` 추가(기본 **켬**):
   - `queries`(기본): `"feature request" llm`, `"feature request" "ai agent"`, `"is there a way" llm`, `"would be great" llm`, `"support for" ollama`, `"please add" "claude" OR "openai"` — 검색 문법 유효성은 테스트로 고정(따옴표·OR 인코딩).
   - `windowHours` 72, `minComments` 2, `minRawScore` 5, `fetchPerQuery` 30, `maxItems` 40, `commentItems` 10, `includeDiscussions` true, `profile` ["demand-signal"].
   - SourceCap·규칙 필터·스팸 묶음·중복 제거·seen은 다른 출처와 같은 경로.
3. **파이프라인 연결** — 어댑터 등록(Main·출처 팩토리), 분류 입력에 출처 이름이 들어가면 `GitHub 이슈`로 표시. 분류 프롬프트(classify.md)는 바꾸지 않는다(출처 이름이 입력에 들어가는 방식이면 그대로). 트렌드: 출처 종류에 `github-issues`가 별도로 세어지는지 확인(출처 다양성 관문에서 HN과 다른 출처로 인정). 과거 채우기 출처 맞춤 비교 집합(`hn`, `github`)에는 넣지 않는다(과거 채우기 대상 아님).
4. **카드·분석 표시** — 근거 출처 표시가 `GitHub 이슈 · owner/repo`처럼 보이게(렌더러가 출처 ID를 그대로 쓰면 이름 표를 추가).

## 테스트 (MockWebServer)
- 검색 쿼리 문자열(is:issue, created, comments, 따옴표·OR 인코딩), PR 제외, 점수 계산·minRawScore, 라벨 → tags, 본문 자르기.
- 토론 GraphQL: 토큰 있으면 요청, 없으면 건너뛰고 경고·이슈는 계속.
- 개인정보: author null, 댓글 작성자 "익명", 본문 `@멘션` 치환, 봇 제외.
- 상위 댓글은 commentItems개만 추가 요청.
- 속도 제한 대기 공유, incomplete_results 경고, 검색어 하나 실패 시 부분 결과.
- 설정 기본값 로드, 파이프라인에서 출처가 수집·분류 입력에 들어감(가짜 공급자), 트렌드 출처 수에 별도 집계.
- 기존 출처·일일 파이프라인 회귀 없음.

## 완료 기준
- `:core:test --rerun-tasks` 통과. 1~4 한 줄씩 + 테스트 개수.

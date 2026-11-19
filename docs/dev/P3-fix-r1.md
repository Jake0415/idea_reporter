# P3 수정 지시 — 1회차 (Claude·OpenAI 합의)

두 리뷰어 모두 "수정 필요". 규칙 동일(커밋·docs·프롬프트 수정·키 출력·HTML 스크레이핑 금지).

## A. 공통
1. **Product Hunt "오늘" 기준** (ProductHuntAdapter.kt:31·55): UTC 자정 대신 `since = now - config.window()`(기본 24시간 이동 창)을 `postedAfter`와 필터에 모두 적용. 실행 시각별 테스트(UTC 00:05, 한국 자정 직전·직후).

## B. RSS (양쪽이 각각 지적)
2. **[OpenAI 높음] 피드 URL·오류 본문 노출** (RssAdapter.kt:64, Http.kt:37): `externalId`에 피드 URL 대신 `피드 이름(또는 설정 id) + 항목 guid/link 해시`. RSS 요청 실패는 상태 코드만(`includeErrorBody = false`). 쿼리에 가짜 토큰이 든 피드 URL로 결과·오류 어디에도 토큰이 없는지 테스트.
3. **[Claude 중간] 피드별 격리** (RssAdapter.kt:27-30): 피드마다 try/catch(`CancellationException` 재던짐), 실패 피드는 건너뛰고 이름을 결과(출처 오류 요약)에 남김. 전부 실패할 때만 예외. 테스트(2개 중 1개 오류).
4. **[OpenAI 중간] 피드별 상한 위치** (RssAdapter.kt:67, Pipeline.kt:81): 어댑터에서 자르지 말고 공통 필터·스팸 필터 이후 `SourceCap`에서 피드별 최신순 상한. 제외된 자리를 다음 유효 항목이 채우는 테스트.
5. **[Claude 낮음] RSS 프로필 목록**: 피드 `profile`을 목록(후보)으로 받기(기존 단일 값도 읽기 호환). **바이트 스트림 파싱**(`InputSource(byteStream)`)으로 XML 선언 인코딩 존중.

## C. Reddit
6. **[OpenAI 중간] LocalLLaMA 후보 프로필** (RedditAdapter.kt:49, ProfileLoader.kt:57): 하드코딩 대신 `RedditConfig.subredditProfiles: Map<String, List<String>>`(기본 LocalLLaMA → tech·demand)로, `validateRouting`이 이것도 검증. 누락 프로필 거부 테스트.
7. **[Claude 낮음·품질] 수요 근거 전달**: 본문 전문(최대 3,000자)을 `Item.extra` 또는 보강 필드로 보관해 작성 단계 signals에 전달(snippet은 300자 유지). **Reddit 상위 댓글(최대 5개, 각 300자)을 분류 입력 `topComments`에도** 넣는다(HN과 같은 필드).
8. **[Claude 낮음]** 토큰 요청 401·500 응답 본문에 토큰·시크릿 형태 문자열이 있어도 예외 메시지에 안 들어가는 회귀 테스트. User-Agent를 Reddit 권장 형식(`<platform>:<app id>:<version>`, 계정명 없이)으로.

## D. Hugging Face
9. **[양쪽 낮음]** models·spaces **종류별 상한**(기본 각 15) 후 합치기. MockWebServer로 `sort`·`direction`·`limit`·`expand[]` 파라미터 검증, 두 응답 합산이 상한을 넘는 파이프라인 테스트.

## 완료 기준
- `:core:test --rerun-tasks` 통과. HF·RSS 실제 호출 1회씩(건수만 보고). 1~9 처리 결과 한 줄씩.

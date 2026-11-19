# P3 개발 브리프 — 출처 4종 (Hugging Face · Reddit · Product Hunt · RSS)

> 개발: Codex · 리뷰: Claude + OpenAI 동시 → 합의된 지적만 수정 · 최대 5회

## 먼저 읽을 것
- `docs/design.md` §9(출처 표), 기존 `collector.source.*`, `collector.config.SourceConfig`, `sources.default.json`, 어댑터 테스트 방식(`core/src/test/.../source/AdapterTest.kt` + `fixtures/`)

## 만들 것
- `HfConfig`/`HuggingFaceAdapter`: 공개 API(키 없음) — 트렌딩 모델·Spaces(정렬 파라미터는 공식 문서로 확인). rawScore = 좋아요 또는 트렌딩 점수, extra에 license·pipeline_tag. 프로필 `tech-signal`. 기본 켜짐.
- `RedditConfig`/`RedditAdapter`: **공식 OAuth API**(`oauth.reddit.com`, client credentials). 키 이름 `REDDIT_CLIENT_ID`, `REDDIT_CLIENT_SECRET`(SecretProvider). 키가 없으면 어댑터는 "키 없음" 오류로 끝나고 파이프라인은 계속(기본 꺼짐). HTML 스크레이핑 금지. 서브레딧별 day top, minUpvotes, discussionUrl, 프로필 demand-signal(LocalLLaMA는 tech·demand 후보).
- `ProductHuntConfig`/`ProductHuntAdapter`: GraphQL API v2, 키 `PRODUCTHUNT_TOKEN`. 키 없으면 같은 방식으로 건너뜀(기본 꺼짐). 토픽 artificial-intelligence, 오늘 게시, votesCount, 프로필 market-signal.
- `RssConfig`/`RssAdapter`: 피드 목록(name, url, profile, maxItems). RSS 2.0·Atom 파싱(JDK XML, 외부 엔티티 비활성화로 XXE 방지), 기간·피드별 최대 건수. 기본 피드 1~2개(AI 관련 공개 블로그 RSS, 예: Hugging Face 블로그 RSS)를 기본 꺼짐으로 예시.
- `sources.default.json`·`SourceConfig` 갱신, `ProfileLoader.validateRouting` 통과.
- `CommentFetcher`: Reddit 댓글(키 있을 때) 추가.

## 테스트
- 각 어댑터: 공식 API의 **실제 응답 저장본**을 fixtures로(키 필요한 Reddit·PH는 공식 문서 예시 응답 형태로 작성하고 출처를 주석에), MockWebServer로 파싱·요청 형태 테스트. 키 없음 → 예외 메시지 확인. RSS: RSS 2.0·Atom·XXE 차단 테스트.
- 실제 실행: HF·RSS는 키 없이 실제 호출 1회 확인(결과 요약을 답변에).

## 하지 말 것
- 커밋·푸시 금지, `docs/` 수정 금지, 키 출력·저장 금지, HTML 스크레이핑 금지.

## 완료 기준
- `:core:test` 통과, HF·RSS 실제 수집 성공, Reddit·PH는 키 없을 때 파이프라인이 멈추지 않음.

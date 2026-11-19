# P2 개발 브리프 — 분류·추리기·보강·카드 작성·검증·추적·리포트

> 개발: Codex · 리뷰: Claude + OpenAI(Codex review) 동시 → 합의된 지적만 수정 · 수정 반복 최대 5회
> 전제: P1(LLM 연결부·프롬프트 저장소)이 완료되어 있다.

## 먼저 읽을 것
- `docs/design.md` §5(프로필), §7(파이프라인 표), **§7-1(단계 세부 규칙 9개)**, **§7-2(관심 추적)**, §7-3(프롬프트), §8(타입), §10(설정)
- `docs/menu.md` §5(카드 블록), §6(메일 리포트)
- 프롬프트: `core/src/main/resources/prompts/{classify,ideas,track,criteria.default}.md`, `profiles/*/{match,analysis}.md`, `categories.json`

## 환경
- `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"; .\gradlew.bat :core:test`
- 키는 `.env`. **키 값은 절대 출력·저장 금지.**

## 만들 것 (core)

### 1) `collector.config.PipelineConfig` + 리소스 `pipeline.default.json` (design §10 값 그대로)

### 2) `collector.stage.Classifier` (④, design §7-1 ①④)
- 프로필 3종(`ProfileLoader`) + 프롬프트(`PromptResolver`의 `classify` + 각 프로필 match/analysis)를 시스템 메시지로, 항목 25건씩 묶어 사용자 메시지(itemId, source, title, snippet ≤ `classifyMaxChars`, rawScore, alsoSeenIn, candidates=`profileHint`, `recentTopics`, `tracked`)로 보낸다.
- 출력 JSON 스키마: `ClassifiedItem` 목록(design §8 — profile, score, reason, summary, tags, topicKey, topicLabel, trackedIdeaIds).
- 빠진 항목 → `CLASSIFY_FAILED`, `none` → `NOT_RELEVANT`. JSON 파싱 실패 시 1회 재시도.

### 3) `collector.stage.Selector` (⑤, §7-1 ③④⑨)
- 프로필 임계값 → `topicKey` 합치기(대표 = 정렬 1순위, 나머지 URL·댓글을 `mergedUrls`에) → 프로필별 `maxCandidates` + 대표 태그 `maxCandidatesPerTag`. 정렬: AI 점수 → 출처 점수 → alsoSeenIn 수 → 원점수. 탈락은 각각 `BELOW_PROFILE_THRESHOLD`, `SAME_TOPIC`, `OVER_QUOTA`.

### 4) 보강 (⑥)
- `CommentFetcher`: HN은 Algolia `items/{id}` API로 상위 댓글 5개(작성자·본문 500자). 다른 출처는 일단 빈 목록.
- `Enricher`: 최종 후보 본문 일부(원문 HTML에서 태그 제거, 프로필 `generateMaxChars`, `sampling` prefix/head-middle-tail). 실패하면 snippet만.
- `HistorySearch` 인터페이스 + `FileHistorySearch`(PC: `out/*.json`의 항목·카드에서 제목 핵심어+태그 단어 일치 점수, 최근 90일, 후보당 3건).

### 5) `collector.stage.IdeaGenerator` (⑦) + `CitationValidator`·`CardValidator` (⑧)
- 시스템: `ideas` 프롬프트. 사용자 메시지: today, ideaCount, maxCardsPerCategory, categories(`categories.json`), criteria(`criteria` 프롬프트), signals(후보: itemId·profile·제목·출처·점수·요약·태그·URL·본문 일부·댓글·mergedUrls), history, feedback(최근 10개 — `out/feedback.json`), recentCards(최근 7일 카드 제목).
- 출력: `IdeaCard` 목록(design §8). `id`·`runDate`는 코드가 채움.
- 검증: 모든 SignalRef URL이 이번 실행 수집 항목(대표 URL 또는 mergedUrls)에 있어야 함 — 없으면 그 근거 제거, 기술 신호 1개 이상 + (수요 또는 경쟁) 1개 이상이 안 남으면 카드 버림. 분류는 `categories.json` id만, 분류별 상한 초과분은 뒤에서부터 버림. 필수 필드 빈 값이면 버림.
- **배지는 코드가 계산**(§7-1 ⑤): 여러 곳에서 화제, 연속 등장(`TopicHistory`), 급상승(HN 시간당 점수, GitHub `MetricSnapshot` 비교).

### 6) 관심 추적 (§7-2)
- `TrackedStore` 인터페이스 + `JsonFileTrackedStore`(PC: `out/tracked.json`). PC에서는 `out/feedback.json`의 STAR가 추적 시작.
- `TrackingKeywords`(코드 추출), `TrackingSearch`(추적 아이디어별 HN·GitHub 전용 검색어, `minRawScoreFactor` 0.3, 결과에 `trackHint`), `TrackingUpdater`(⑧-1: 관련 항목이 있는 아이디어만 `track` 프롬프트로 분류 모델 1회 → `TrackingUpdate`), `StrengthCalculator`(7일 가중 합·추이, 코드 계산), 30일 업데이트 없으면 QUIET.

### 7) 리포트·실행
- `HtmlRenderer`(menu §6): 제목 줄, 깔때기, 실패 출처, 목차, 카드 요약(한 줄 설명·오늘 고른 이유·핵심 근거 2개·가장 큰 위험·앱 링크 `ideacollector://card/{id}`), 추적 업데이트 칸, 비용·시간. 배지는 인라인 스타일. HTML과 일반 텍스트 둘 다.
- `Pipeline` 확장: ①~⑨ 전체. 진행 콜백. 같은 날 재실행 = 오늘 결과 교체, 이미 본 글은 **이전 날짜에 본 것만** 제외(SeenStore에 날짜 저장 — 기존 JsonFileSeenStore 형식 확장, 이전 형식도 읽기), 실행 잠금(`out/run.lock`).
- `RunReport`에 단계별 건수·출처·탈락 이유·llmCalls(프롬프트 id·버전 포함)·카드 수·추적 업데이트 수.
- `Main`: 전체 실행 → `out/<날짜>.json`(항목·탈락·분류·카드·추적·리포트), `out/<날짜>.html`, `out/<날짜>.txt`. 옵션 `--keep-unseen`, `--no-llm`(1단계까지만), `--budget <usd>`.

## 테스트
- 기존 테스트 유지 + 각 단계 단위 테스트(가짜 `LlmProvider`로 JSON 응답 주입): 분류 배치·누락·none, 선택 정렬·합치기·상한, 인용 검증(가짜 URL 제거·카드 버림), 분류 상한, 배지 계산, 추적 키워드·강도·QUIET, HTML 렌더(필수 칸 존재), 재실행 규칙(이전 날짜만 제외).
- 끝나면 실제 키로 `Main` 전체 실행 1회(비용 상한 $0.50) → 결과 요약(카드 수·비용·실패)을 마지막 답변에.

## 하지 말 것
- 커밋·푸시 금지, `docs/` 수정 금지(제안은 답변에), 키 출력·저장 금지, 프롬프트 파일 내용 변경 금지(개선 제안은 답변에).

## 완료 기준
- `:core:test` 통과, 실제 전체 실행 1회 성공(카드 1개 이상 또는 0개면 그 이유가 실행 기록에), `out/<날짜>.html` 생성.

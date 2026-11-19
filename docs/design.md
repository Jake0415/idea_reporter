# 설계 — AI 아이디어 수집기

> 메뉴·화면 설계는 [menu.md](menu.md). 이 문서는 데이터 흐름·모듈·타입의 기준이다.

## 1. 목표
- 정보 검색의 번거로움을 없애고, 매일 아침 **AI 서비스 아이디어 카드 5개**를 근거 링크와 함께 받는다.
- 가볍게: 서버 운영 없음, 월 LLM 비용 약 $3~8 목표.
- 결과물은 "기사 요약"이 아니라 **여러 신호(기술 × 수요 × 경쟁)를 엮은 아이디어**다.

## 2. 방식 비교 요약 (C안 채택 이유)

| 관점 | A. Claude 검색 전부 | B. 코드 수집 + LLM 마무리 | C. 단계별 모델 분리 (채택) |
|---|---|---|---|
| 사이트별 검색어 | Claude가 매번 정함 | 설정으로 고정 | 설정으로 고정 |
| 정제 | LLM(토큰 소모) | 코드(0원) | 코드(0원) |
| 누락 위험 | 높음 | 낮음 | 낮음 |
| 월 예상 비용 | $15~40 | $3~6 | $2~5 (+보충 검색 시 소폭) |

## 3. Horizon 설계 차용

[Thysrael/Horizon](https://github.com/Thysrael/Horizon) (MIT, Python, 개인용 AI 뉴스 브리핑)의 설계를 참고해 **Kotlin으로 새로 구현**한다. 코드는 가져오지 않는다. 프롬프트나 코드를 일부라도 옮기면 MIT 저작권 표시를 `THIRD_PARTY_NOTICES.md`에 남긴다.

| 차용 | Horizon 원형 | 우리 적용 |
|---|---|---|
| 프로필 | 프로필마다 `match.md`·`analysis.md`·`enrichment.md` + `profile.json` | 신호 종류별 프로필 3종(§5), 평가표 0~10을 파일로 분리 |
| 프로필별 임계값 | `profile_settings.threshold` | 프로필별 임계값 + 출처별 최소 점수(규칙 필터) 병행 |
| 주제 중복 제거 | 프로필 안에서 LLM으로 같은 주제 합침 | 1차 분류 호출 안에서 `topicKey`를 함께 받아 추가 호출 없이 합침 |
| 분류별 상한 | `digest.category_groups`, `max_items` | 카드 분류별 최대 개수, 프로필별 후보 상한 |
| 댓글 수집 | HN·Reddit 상위 댓글 N개 | 최종 후보만 상위 댓글 수집 → 수요 신호의 핵심 근거 |
| 과거 기록 검색 | 지난 브리핑 BM25 검색(`history_search`) | SQLite 전문 검색(FTS)으로 과거 항목·카드 검색 → "과거 언급", 반복 주제 |
| 인용 검증 | 도구가 돌려준 출처만 인용 허용 | 카드 링크는 **이번 실행에서 수집된 항목 URL만** 허용, 나머지는 거부 |
| 본문 길이 예산 | `analysis_max_chars`, `sampling` | 프로필별 분류·작성 입력 글자 수, 앞부분/앞·중간·끝 샘플링 |
| 출처 품질 지표 | HorizonHub 구상: 평균 점수, 통과율 | 출처별 30일 수집 건수·통과율·평균 점수 |
| 범용 RSS | RSS/Atom 스크레이퍼 | RSS 어댑터 추가(블로그, 각종 피드) |

**우리만의 것(Horizon에 없음):** LLM 전 규칙 필터, 여러 신호를 엮는 아이디어 종합 단계, ★·관심 없음 피드백 반영, 일일 비용 상한, 안드로이드 앱, 한국어 결과물.

## 4. 모듈

```
ai-idea-collector/
├── core/      순수 Kotlin(JVM). 수집·정제·LLM·리포트. 저장소·메일은 인터페이스만
├── app/       안드로이드. WorkManager·Room(FTS)·Compose·Keystore·SMTP
├── profiles/  (core 리소스) 프로필별 평가 기준 파일
└── runner/    (선택) JVM 실행 파일. GitHub Actions용
```

## 5. 프로필 (신호 종류)

아이디어 = **새로 가능해진 기술** × **사람들이 원하는 것**, 그리고 **이미 나온 경쟁 제품**으로 차별점을 본다.

| 프로필 id | 이름 | 대상 | 평가표에서 보는 것 | 기본 배정 출처 |
|---|---|---|---|---|
| `tech-signal` | 기술 신호 | 새 모델·오픈소스·API·논문 | 1인이 가져다 쓸 수 있는가, 라이선스, 성숙도, 새로 가능해진 일 | GitHub, HF, arXiv |
| `demand-signal` | 수요 신호 | 불편·요청·"이런 거 있나요" | 문제의 구체성, 반복성, 돈을 낼 의향의 단서 | Reddit |
| `market-signal` | 경쟁 제품 | 새로 출시된 AI 제품 | 반응, 빈틈(불만 댓글), 따라 하기 쉬운 정도 | Product Hunt |

- HN·RSS처럼 섞인 출처는 후보 `["tech-signal","demand-signal","market-signal"]` 중에서 1차 분류가 배정한다(별도 호출 없음).
- 프로필 파일 구조:

```
core/src/main/resources/profiles/<id>/
├── profile.json    id, 이름, 임계값 기본값, 입력 글자 예산, 샘플링 방식
├── match.md        포함/제외 기준
└── analysis.md     0~10 평가표와 판단 요령
core/src/main/resources/prompts/
├── classify.md     1차 분류 공통 지시(배치, 출력 스키마)
├── ideas.md        아이디어 종합 지시(카드 스키마, 인용 규칙)
└── criteria.default.md  나의 평가 기준(1인 개발 범위, 대상 고객, 제외 분야, 수익화)
```

## 6. 디렉터리

```
core/src/main/kotlin/collector/
├── model/     Item, Comment, ClassifiedItem, IdeaCard, Feedback, DropReason, SourceStats, RunReport
├── config/    SourceConfig(출처별 sealed), PipelineConfig, ProfileConfig, ConfigLoader
├── profile/   ProfileLoader, ProfileRouter(명시 배정/후보 배정)
├── source/    SourceAdapter + HackerNews/GitHub/HuggingFace/Reddit/ProductHunt/Arxiv/Rss
├── refine/    UrlNormalizer, RuleFilter, Deduplicator, Scorer, QuotaSelector
├── llm/       LlmClient, Classifier, CommentFetcher, Enricher, IdeaGenerator, CitationValidator,
│              Supplement, CostGuard
├── history/   HistorySearch(인터페이스) — 과거 항목·카드 전문 검색
├── report/    HtmlRenderer, MarkdownRenderer
├── port/      SeenStore, ItemStore, IdeaStore, FeedbackStore, StatsStore, Mailer, SecretProvider
├── pipeline/  Pipeline, Stage
└── dev/       Main, JsonFile*Store, EnvSecretProvider (PC 실행 전용)

app/src/main/kotlin/collector/app/
├── work/    DailyCollectWorker, WorkScheduler
├── data/    AppDatabase(Room + FTS), Room*Store, RoomHistorySearch
├── mail/    SmtpMailer
├── secret/  KeystoreSecretProvider
└── ui/      menu.md의 화면 단위(SC-xx)로 구성
```

## 7. 파이프라인

| 단계 | 내용 | 하루 처리량 | LLM 비용 |
|---|---|---|---|
| ① Collect | 어댑터 병렬 실행, 출처 하나 실패해도 계속 | 300~500건 | 0 |
| ② Filter | 기간, 출처별 최소 점수, 제외 키워드 → 탈락 이유 기록 | → ~150 | 0 |
| ③ Dedupe | URL 정규화, 제목 유사도, 이미 본 글, 여러 출처 합치기(`alsoSeenIn`), 점수 0~100 통일 | → ~80 | 0 |
| ④ Classify | Haiku 5.5, 25건씩 묶어: 프로필 배정·0~10점·이유·한 줄 요약·태그·`topicKey` | 80 | ~$0.01 |
| ⑤ Select | 프로필별 임계값 → `topicKey`로 주제 합치기 → 프로필별·분류별 상한 | → 15~20 | 0 |
| ⑥ Enrich | 최종 후보 본문 샘플, HN·Reddit 상위 댓글 5개, 과거 기록 검색 / (선택) 보충 검색 | 15~20 | 0 (~$0.03) |
| ⑦ Generate | Opus 5.5: 신호를 엮어 카드 5개, 최근 피드백을 선호 예시로 포함 | 5 | ~$0.1~0.2 |
| ⑧ Validate | 인용 검증(수집 URL만), 카드 스키마 검증, 실패 시 1회 재시도 | — | (재시도 시) |
| ⑨ Deliver | HTML 메일, 저장(항목·카드·탈락 이유·출처 통계), SeenStore 갱신, 실행 기록 | — | 0 |

## 8. 핵심 타입

```kotlin
@Serializable
data class Item(
    val source: String, val externalId: String, val title: String, val url: String,
    val rawScore: Int, val score: Int = 0, val publishedAt: Instant,
    val snippet: String,                         // 최대 300자, 분류에는 이것만
    val tags: List<String> = emptyList(),
    val alsoSeenIn: List<String> = emptyList(),  // 여러 출처에 동시 등장
    val profileHint: List<String> = emptyList(), // 출처 설정의 명시 배정/후보
    val comments: List<Comment> = emptyList()    // ⑥에서 최종 후보만 채움
)

@Serializable
enum class DropReason { OUT_OF_WINDOW, LOW_SOURCE_SCORE, EXCLUDED_KEYWORD, DUPLICATE, ALREADY_SEEN,
    BELOW_PROFILE_THRESHOLD, SAME_TOPIC, OVER_QUOTA, CLASSIFY_FAILED, BUDGET_EXCEEDED }

@Serializable
data class ClassifiedItem(
    val externalId: String, val profile: String,  // tech-signal | demand-signal | market-signal | none
    val score: Double?,                           // 0~10, 실패 시 null
    val reason: String, val summary: String,      // 한 줄 이유, 한 줄 요약(한국어)
    val tags: List<String>, val topicKey: String  // 같은 주제끼리 같은 키
)

@Serializable
data class IdeaCard(
    val title: String,
    val oneLiner: String,                 // 한 줄 설명
    val whyToday: String,                 // 오늘 고른 이유(추천 이유)
    val problem: String,                  // 누구의 어떤 불편
    val techSignals: List<SignalRef>,     // 가능하게 한 기술(라이선스 포함)
    val demandSignals: List<SignalRef>,   // 수요 근거(댓글 인용 포함)
    val competitors: List<SignalRef>,     // 경쟁 제품, 빈틈
    val history: List<String>,            // 과거 언급(날짜)
    val effort: String,                   // "1인 2주"
    val differentiation: String,
    val biggestRisk: String,              // 가장 큰 위험 1개
    val validationPlan: String,           // 2주·소액으로 검증하는 방법
    val category: String, val badges: List<String> // 급상승·여러 곳에서 화제·3일 연속 등
)

@Serializable
data class SignalRef(val itemId: String, val url: String, val note: String) // url은 수집 항목만

@Serializable
data class Feedback(val cardId: String, val kind: Kind, val reason: String?, val memo: String?, val at: Instant) {
    enum class Kind { STAR, NOT_INTERESTED }
}

@Serializable
data class SourceStats(val source: String, val days: Int, val fetched: Int, val passed: Int, val avgScore: Double?)
```

## 9. 출처 설정 (`sources.default.json` 초안)

| id | 방법 | 조건 | 1차 필터 | 프로필 |
|---|---|---|---|---|
| hn | Algolia 검색 API | queries: LLM, AI agent, Show HN AI / 24h | minScore 50 | 후보 3종 |
| github | 저장소 검색 API | topic:llm, ai-agent, generative-ai / 14일 내 생성, 스타순 | minStars 200 | tech-signal |
| hf | 모델·Spaces API | 트렌딩 정렬 | 상위 30 | tech-signal |
| reddit | 서브레딧 인기글(OAuth 권장) | LocalLLaMA, SaaS, ChatGPT, artificial / day | minUpvotes 100 | demand-signal (LocalLLaMA는 후보 2종) |
| ph | GraphQL API(토큰) | topic artificial-intelligence, 오늘 | minVotes 50 | market-signal |
| arxiv | 공개 API (기본 꺼짐) | cs.AI, cs.CL | 상위 30 | tech-signal |
| rss | RSS/Atom (피드 목록) | 사용자가 추가 | 피드별 최대 10 | 후보 3종 |

공통 제외 키워드: crypto, giveaway, hiring. 앱의 출처 설정 화면에서 수정한 값은 Room에 덮어써 저장한다.

## 10. 파이프라인 설정 기본값

```json
{
  "classifyModel": "claude-haiku-5-5",
  "generateModel": "claude-opus-5-5",
  "classifyBatchSize": 25,
  "profiles": {
    "tech-signal":   { "threshold": 6.0, "maxCandidates": 8 },
    "demand-signal": { "threshold": 6.0, "maxCandidates": 8 },
    "market-signal": { "threshold": 5.0, "maxCandidates": 4 }
  },
  "ideaCount": 5,
  "maxCardsPerCategory": 2,
  "commentsPerItem": 5,
  "feedbackExamples": 10,
  "historyDays": 90,
  "supplementSearch": { "enabled": false, "maxUses": 3 },
  "dailyBudgetUsd": 0.5
}
```

## 11. 안정성 규칙
- 출처별 격리: 실패·시간 초과 출처는 건너뛰고 메일 하단과 실행 기록에 표시.
- 탈락 이유는 모든 단계에서 `DropReason`으로 기록(수집함 화면, 출처 통과율 계산에 사용).
- CostGuard: 응답 토큰 사용량으로 비용 누적, 예산 초과 시 이후 LLM 단계 중단하고 그때까지 결과만 발송.
- LLM 응답은 구조화된 출력(JSON 스키마)으로 받고, 파싱 실패 시 1회만 재시도, 그래도 실패하면 점수 null로 저장.
- 인용 검증: 카드의 모든 URL이 이번 실행의 수집 항목에 있어야 함. 없으면 그 근거를 제거하고, 근거가 하나도 안 남는 카드는 버림.
- 날짜별 실행 키로 같은 날 재실행 시 중복 카드·메일 방지.
- 비밀값(Anthropic·GitHub·Reddit·Product Hunt 키, Gmail 앱 비밀번호)은 SecretProvider로만 접근. 앱=Android Keystore, runner=환경 변수.
- 어댑터는 fixtures 기반 테스트, LLM 단계는 가짜 LlmClient로 테스트.
- Claude Opus 5.5 호출에는 서버 측 거절 대비 fallback 설정을 기본으로 넣는다.

## 12. 알려진 제약
- 안드로이드 WorkManager는 실행 시각을 보장하지 않음(절전·제조사 배터리 관리). 정시가 필요하면 runner + GitHub Actions.
- Claude 웹 검색/읽기는 로그인 필요한 페이지(X·인스타 피드 등)를 읽지 못함.
- 웹 검색은 1,000회당 약 $10 + 토큰(가격 페이지에서 확인 필요).
- 과거 기록 검색은 단어 일치 기반이라 동의어·다른 언어 표현은 못 찾음(Horizon과 같은 한계).

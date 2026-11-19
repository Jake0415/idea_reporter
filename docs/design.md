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

[Thysrael/Horizon](https://github.com/Thysrael/Horizon) (MIT, Python, 개인용 AI 뉴스 브리핑)의 설계를 참고해 **Kotlin으로 새로 구현**한다. 아래 표의 근거는 [verification/horizon-rea-check.md](verification/horizon-rea-check.md)(커밋 `12d5fbd` 기준)에 있다. 코드는 가져오지 않는다. 프롬프트나 코드를 일부라도 옮기면 MIT 저작권 표시를 `THIRD_PARTY_NOTICES.md`에 남긴다.

| 차용 | Horizon 원형 | 우리 적용 |
|---|---|---|
| 프로필 | 프로필마다 `match.md`·`analysis.md`·`enrichment.md` + `profile.json` | 신호 종류별 프로필 3종(§5), 평가표 0~10을 파일로 분리 |
| 프로필별 임계값 | `profile_settings.threshold` | 프로필별 임계값 + 출처별 최소 점수(규칙 필터) 병행 |
| 주제 중복 제거 | 임계값 통과 후 프로필별 **별도 LLM 호출**로 같은 실제 사건을 합침(같은 제품의 다른 사건은 별개). 점수 높은 항목이 대표, 중복 항목의 본문·댓글은 대표에 붙임 | **변형:** 1차 분류 호출 안에서 `topicKey`를 함께 받아 추가 호출 없이 합침. 합치는 단위는 **대상**(제품·프로젝트·사건·문제)이고 최근 14일 키를 재사용해 날짜를 넘어 같은 주제를 알아본다(§7-1 ④). 카드 재료로는 같은 대상의 근거를 모으는 편이 낫기 때문. 합칠 때 중복 항목의 URL·댓글은 대표에 붙여 근거를 잃지 않음(`mergedUrls`) |
| 분류별 상한 | `digest.category_groups`, `max_items` — 임계값·주제 합치기 뒤, 작성 전에 적용 | 카드 분류별 최대 개수, 프로필별 후보 상한. 같은 순서로 **작성(Opus) 호출 전에** 적용해 비용 절약 |
| 태그 | 1차 분석 단계에서 구체적 주제 태그 3~5개 | 1차 분류(Haiku) 출력에 태그 3~5개 |
| 댓글 수집 | HN·Reddit 상위 댓글 5개를 **모든 수집 항목**에 대해 수집 단계에서 가져옴. Reddit은 API 키 없이 HTML 스크레이핑 우선 | **변형:** 최종 후보만, 공식 API로 가져옴(HTML 스크레이핑은 따르지 않음) → 수요 신호의 핵심 근거 |
| 과거 기록 검색 | 지난 **브리핑(결과물)만** BM25 검색(`history_search`), 항목당 1회, 후보 최대 3건, 기본 90일 | **확장:** SQLite 전문 검색(FTS)으로 과거 수집 항목 + 카드 둘 다 검색 → "과거 언급", 반복 주제. 인용은 직접 연결된 것만 최대 2건(차용) |
| 인용 검증 | 블록마다 그 블록의 도구가 돌려준 출처만 인용 허용, 아니면 거부 | **변형(같은 원리):** 카드 링크는 **이번 실행에서 수집된 항목 URL만** 허용, 나머지는 거부. 필수 블록 누락·모르는 블록이 있는 카드도 거부 |
| 본문 길이 예산 | `analysis_max_chars`, `enrichment_max_chars`, `sampling`(prefix / head-middle-tail) | 프로필별 분류·작성 입력 글자 수, 앞부분/앞·중간·끝 샘플링 |
| 출처 품질 지표 | HorizonHub **구상(미구현)**: 평균 점수, 통과율, 하루 평균 수집량, 30일 추세 | 출처별 30일 하루 평균 수집량·통과율·평균 점수 |
| 범용 RSS | RSS/Atom 스크레이퍼(입력). 별도로 결과물 RSS 피드도 발행 | **RSS 입력 어댑터**만 추가(블로그, 각종 피드). 결과물 피드 발행은 하지 않음 |

**우리만의 것(Horizon에 없음):** 출처별 점수·키워드 규칙 필터로 LLM 전에 대부분을 거르는 것(URL 중복 제거와 수집 기간 제한은 Horizon에도 있음), 여러 신호를 엮는 아이디어 종합 단계, ★·관심 없음 피드백 반영, 일일 비용 상한, 안드로이드 앱, 한국어 결과물.

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

- 작성 프롬프트(`ideas.md`)를 쓸 때 검증 보고서 §4-10의 차용 문구 후보(제목 15단어 이하·제품명 유지, 막연한 전망이면 생략, 추천 수는 합의 근거 아님, 과거 인용은 직접 연결만)를 반영한다.
- 프로필 기본값(구현됨): tech-signal 임계값 6.0·후보 8·앞부분 1000/4000자, demand-signal 6.0·8·앞중간끝 1500/6000자, market-signal 5.0·4·앞부분 1000/3000자. `ProfileLoader`가 시작할 때 값 범위·모르는 필드·빈 파일과 출처의 프로필 배정을 검증한다.
- HN·RSS처럼 섞인 출처는 후보 `["tech-signal","demand-signal","market-signal"]` 중에서 1차 분류가 배정한다(별도 호출 없음).
- 프로필 파일 구조:

```
core/src/main/resources/profiles/
├── index.json      프로필 목록과 기본 프로필(tech-signal)
└── <id>/
    ├── profile.json    id, 이름, 설명, threshold, maxCandidates, content(classifyMaxChars·generateMaxChars·sampling)
    ├── match.md        포함/제외 기준, 애매할 때
    └── analysis.md     0~10 평가표와 판단 요령(라이선스·업체 주장·추천 수 등)
core/src/main/resources/prompts/
├── classify.md     1차 분류 공통 지시(배치, 출력 스키마)
├── ideas.md        아이디어 종합 지시(카드 스키마, 인용 규칙)
└── criteria.default.md  나의 평가 기준(1인 개발 범위, 대상 고객, 제외 분야, 수익화)
```

## 5-1. LLM 공급자와 사용자 키

**Claude(Anthropic)와 OpenAI 두 공급자를 지원**하고, 사용자가 **자기 키를 직접 등록**하면 그 공급자가 연동된다. 앱에 개발자 키를 넣어 배포하지 않는다.

### 원칙
- 키는 사용자가 앱(SC-00, SC-11)에서 등록한다. 하나 이상 있어야 실행된다. 저장은 기기 보안 저장소(Android Keystore)에만, 화면에는 등록 여부와 끝 4자리만 보인다. PC·runner는 환경 변수 `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`.
- 키는 저장소·문서·로그·실행 기록·대화 기록에 남기지 않는다.
- **단계별로 공급자와 모델을 따로 고른다.** 예: 분류=Claude Haiku, 작성=OpenAI 상위 모델. 기본값은 등록된 키에 따라 정한다(둘 다 있으면 Claude 기준: 분류 `claude-haiku-5-5`, 작성 `claude-opus-5-5`).
- 모델 목록은 하드코딩하지 않고, 키 등록 시 각 공급자의 모델 목록 API로 받아 와 고르게 한다. OpenAI 기본 모델 id는 구현할 때 공식 문서로 확인해 정한다.

### 구조

```
core/src/main/kotlin/collector/llm/
├── LlmProvider.kt        공급자 공통 인터페이스
├── AnthropicProvider.kt  Anthropic Java SDK (com.anthropic:anthropic-java)
├── OpenAiProvider.kt     OpenAI Java SDK (com.openai:openai-java)
├── LlmRouter.kt          단계 → (공급자, 모델) 선택, 대체 공급자 처리
├── ModelCatalog.kt       공급자별 모델 목록·가격표(가격은 사용자가 고칠 수 있는 설정)
└── CostGuard.kt          호출별 토큰·비용 누적, 일일 상한
```

```kotlin
enum class ProviderId { ANTHROPIC, OPENAI }

interface LlmProvider {
    val id: ProviderId
    /** 키 확인 + 사용 가능한 모델 목록 (키 등록 시 호출) */
    suspend fun listModels(): List<ModelInfo>
    /** 구조화된 출력(JSON 스키마)으로 한 번 호출 */
    suspend fun complete(request: LlmRequest): LlmResult
}

data class LlmRequest(
    val model: String,
    val system: String,          // 고정 지시(캐시 대상): 프로필 평가표, ideas.md
    val user: String,            // 그날 데이터
    val jsonSchema: JsonObject,  // 출력 스키마 (ClassifiedItem 목록, IdeaCard 목록)
    val maxOutputTokens: Int,
    val effort: Effort = Effort.MEDIUM   // 공급자별 추론 강도 설정으로 변환
)

data class LlmResult(
    val json: String,
    val provider: ProviderId, val model: String,
    val inputTokens: Int, val outputTokens: Int, val cachedInputTokens: Int,
    val costUsd: Double,          // ModelCatalog 가격표로 계산
    val refused: Boolean = false  // 공급자가 거절한 경우
)

@Serializable
data class StageModel(val provider: ProviderId, val model: String)
```

### 공급자 차이를 흡수하는 곳

| 항목 | Claude | OpenAI | 처리 |
|---|---|---|---|
| 구조화된 출력 | `output_config.format`(JSON 스키마) | `response_format`(JSON 스키마, strict) | 각 Provider가 같은 `jsonSchema`를 자기 형식으로 변환 |
| 프롬프트 캐싱 | `cache_control` 지정 | 자동 캐싱 | system(고정)을 앞, user(그날 데이터)를 뒤에 두는 순서를 공통으로 지킴 |
| 추론 강도 | `output_config.effort` | 모델별 추론 설정 | `Effort`를 공급자 값으로 변환 |
| 거절 | `stop_reason: refusal` + 서버 측 대체(fallback) | 거절 응답 | `refused=true`로 통일 → LlmRouter가 대체 공급자로 1회 재시도 |
| 웹 검색(보충 검색, 선택) | 웹 검색 서버 도구 | 웹 검색 도구 | 6단계(선택)에서 공급자별로 구현 |
| 키 확인 | 모델 목록 API | 모델 목록 API | `listModels()` 성공 여부로 판정 |

### 대체 공급자
- 단계별로 "대체 공급자 사용"을 켤 수 있다(기본: 두 키가 모두 있으면 켬). 기본 공급자가 오류(인증 실패 제외)·거절·시간 초과면 대체 공급자의 같은 등급 모델로 1회 재시도한다.
- 인증 실패(키 만료·폐기)는 재시도하지 않고 실행 기록과 알림으로 "키를 확인하세요"를 띄운다.

### 실행 기록
- `RunReport`에 LLM 호출 기록 `LlmCall(stage, provider, model, promptId, promptVersion, inputTokens(캐시 포함), outputTokens, cachedInputTokens, cacheWriteTokens, costUsd, priceFallback, priceUnverified, fallbackUsed, error)` 목록을 추가한다(SC-10 실행 상세). `error`는 사유 코드만: truncated·invalid_json·empty·http_4xx·http_5xx·timeout·auth·key_missing·refused 등(키·응답 본문 금지).

### 구현 결정 (P1, 2026-11-19)
- SDK: `com.anthropic:anthropic-java:3.3.0`, `com.openai:openai-java:4.109.0`. OpenAI 기본: fast `gpt-5.4-mini`, strong `gpt-5.5`(가격 미검증 표시).
- 가격: 요청 모델이 가격표에 없으면 호출 전 차단(대체 없음). 응답 모델은 정확 일치 → 날짜 접미사 제거 일치 → 없으면 공급자 최고가 추정(`priceFallback`). Haiku 5.5는 입력 10만 토큰 초과 요금 구간 반영.
- 잘린 응답(max_tokens/length)·해석 실패는 비용을 기록한 실패로 처리해 대체 대상. 예산을 넘긴 호출의 결과는 반환하고 다음 호출부터 차단(동시 호출 시 소폭 초과 가능). 단계별 시간 제한: 분류·추적 60초, 작성 600초.

## 6. 디렉터리

```
core/src/main/kotlin/collector/
├── model/     Item, Comment, ClassifiedItem, IdeaCard, Feedback, DropReason, SourceStats, RunReport
├── config/    SourceConfig(출처별 sealed), PipelineConfig, ProfileConfig, ConfigLoader
├── profile/   ProfileLoader, ProfileRouter(명시 배정/후보 배정)
├── source/    SourceAdapter + HackerNews/GitHub/HuggingFace/Reddit/ProductHunt/Arxiv/Rss
├── refine/    UrlNormalizer, RuleFilter, Deduplicator, Scorer, QuotaSelector
├── llm/       LlmProvider, AnthropicProvider, OpenAiProvider, LlmRouter, ModelCatalog, CostGuard (§5-1)
├── stage/     Classifier, CommentFetcher, Enricher, IdeaGenerator, CitationValidator, Supplement
├── history/   HistorySearch(인터페이스) — 과거 항목·카드 전문 검색
├── report/    HtmlRenderer, MarkdownRenderer
├── prompt/    PromptStore, PromptResolver, PromptCatalog (§7-3)
├── track/     TrackingKeywords(키워드 추출), TrackingSearch(전용 검색어), TrackingUpdater(⑧-1), StrengthCalculator
├── port/      SeenStore, ItemStore, IdeaStore, FeedbackStore, StatsStore, TrackedStore, Mailer, SecretProvider
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
| ① Collect | 어댑터 병렬 실행, 출처 하나 실패해도 계속. **추적 전용 검색**(추적 아이디어별 맞춤 검색어, 낮은 인기도 기준)도 함께 실행해 `trackHint`를 붙임(§7-2) | 300~500건 (+추적 수십 건) | 0 |
| ② Filter | 기간, 출처별 최소 점수, 제외 키워드, **스팸 묶음**(같은 출처에서 3개 이상 계정이 같은 이름·설명으로 찍어 낸 저장소), **출처 상한**(거른 뒤 출처당 `maxItems`) → 탈락 이유 기록. 어댑터는 스팸을 감안해 검색어당 `fetchPerQuery`건을 넉넉히 받아 온다 | → ~150 | 0 |
| ③ Dedupe | URL 정규화, 제목 유사도, 이미 본 글, 여러 출처 합치기(`alsoSeenIn`), 점수 0~100 통일 | → ~80 | 0 |
| ④ Classify | Haiku 5.5, 25건씩 묶어: 프로필 배정·0~10점·이유·한 줄 요약·태그 3~5개·`topicKey`·**관련 추적 아이디어 id**(입력에 추적 목록을 함께 줌, 추가 호출 없음) | 80 | ~$0.01 |
| ⑤ Select | 무관(`none`) 제외 → 프로필별 임계값 → `topicKey`로 주제 합치기(중복 항목 URL·댓글은 대표에 붙임) → 프로필별 후보 상한 + 태그 다양성 상한. **비싼 ⑦ 작성 전에** 후보를 줄여 비용 절약. 카드 분류별 상한은 ⑦·⑧에서(§7-1) | → 15~20 | 0 |
| ⑥ Enrich | 최종 후보 본문 샘플, HN·Reddit 상위 댓글 5개, 과거 기록 검색 / (선택) 보충 검색 | 15~20 | 0 (~$0.03) |
| ⑦ Generate | 작성 모델: 신호를 엮어 카드 5개(분류는 고정 목록에서, 분류별 상한 지시), 최근 피드백·최근 카드 포함 | 5 | ~$0.1~0.2 |
| ⑧ Validate | 인용 검증(수집 URL만), 카드 스키마·필수 블록 검증, 분류별 상한 초과분 제거, 배지 계산(코드), 실패 시 1회 재시도 | — | (재시도 시) |
| ⑧-1 Track | 추적 아이디어마다 오늘 관련 항목이 있으면 싼 모델로 "무엇이 달라졌나" 요약(`TrackingUpdate`), 신호 강도·추이는 코드가 계산(§7-2) | 추적 수 이하 | ~$0.005/건 |
| ⑨ Deliver | HTML 메일(추적 업데이트 칸 포함), 저장(항목·카드·탈락 이유·출처 통계·추적 업데이트), SeenStore 갱신, 실행 기록, 추적 업데이트 알림 | — | 0 |

## 7-1. 단계 세부 규칙 (2단계 전 확정)

### ① 1차 분류 계약 (④ Classify)
- 프롬프트: `prompts/classify.md`(고정 지시) + 프로필별 `match.md`·`analysis.md`(고정) → 시스템 메시지. 그날 데이터는 사용자 메시지.
- 입력(25건씩 묶음): 항목마다 `itemId`(=`Item.key`), 출처, 제목, snippet(프로필 `classifyMaxChars` 이내), 원점수, `alsoSeenIn`, 후보 프로필(`profileHint`). 묶음 공통으로 `recentTopics`(최근 14일 주제 키·이름).
- 출력: 항목마다 `ClassifiedItem` 1개(빠진 항목은 `CLASSIFY_FAILED`).
- **무관 처리:** 후보 프로필 어디에도 맞지 않거나 AI와 무관하면 `profile = "none"` → 탈락 이유 `NOT_RELEVANT`.
- 후보 프로필이 하나뿐인 출처(GitHub 등)는 그 프로필로 평가하되, 맞지 않으면 `none` 가능.

### ② 카드 분류 목록 (고정)
`core/src/main/resources/categories.json`. 카드 `category`는 이 id 중 하나.

| id | 이름 |
|---|---|
| dev-tools | 개발 도구 |
| workflow-automation | 업무 자동화 |
| content-creation | 콘텐츠 제작 |
| voice-conversation | 음성·대화 |
| data-search | 데이터·검색 |
| education | 교육·학습 |
| commerce-marketing | 커머스·마케팅 |
| personal-productivity | 개인 생산성 |
| health-life | 건강·생활 |
| other | 기타 |

### ③ 상한 적용 위치
- ⑤ Select(작성 전): 프로필별 후보 상한(`maxCandidates`) + **태그 다양성 상한**(같은 첫 번째 태그를 가진 후보는 `maxCandidatesPerTag`개까지, 넘치면 `OVER_QUOTA`).
- ⑦ Generate: 프롬프트에 분류 목록과 `maxCardsPerCategory`를 주고 지키게 한다.
- ⑧ Validate: 그래도 넘치면 작성 순서상 뒤의 카드를 버린다.

### ④ 날짜를 넘는 주제 키
- `topicKey` = **대상 단위**(제품·프로젝트·사건·문제), 영문 소문자 kebab-case(예: `gemini-4-neon`, `meeting-notes-pain`). `topicLabel` = 한국어 짧은 이름.
- 분류 입력에 최근 14일(`recentTopicDays`)의 `topicKey`·`topicLabel` 목록을 주고, **같은 대상이면 기존 키를 그대로 쓰게** 한다. 새 대상이면 새 키.
- 같은 날 같은 `topicKey`는 하나의 후보로 합치고 근거(URL·댓글)는 모두 대표에 붙인다. (Horizon은 같은 제품의 다른 사건을 나누지만, 우리는 카드 재료로 근거를 모으는 쪽이 낫다고 보고 대상 단위로 합친다 — §3 변형.)
- 저장: `TopicHistory(topicKey, topicLabel, runDate, itemKeys)` → 반복 주제(SC-04), 연속 등장 배지, 최근 카드 중복 판단.

### ⑤ 배지 (코드가 계산, LLM은 쓰지 않음)
| 배지 | 규칙 |
|---|---|
| 여러 곳에서 화제 | 카드 근거 항목들의 출처(`source` + `alsoSeenIn`)가 서로 다른 2곳 이상 |
| 연속 등장 | 카드 근거의 `topicKey` 중 하나가 최근 3일 중 2일 이상 `TopicHistory`에 있음 |
| 급상승 | HN: 게시 후 시간당 점수 ≥ 10 · GitHub: 하루 스타 증가 ≥ 100(이전 실행의 `MetricSnapshot`과 비교, 이력이 없으면 생성 후 하루 평균 스타 ≥ 100) |
- 수집할 때마다 `MetricSnapshot(itemKey, rawScore, at)`을 저장한다(급상승 계산용, 30일 보관).
- 기준값은 `PipelineConfig.badges`로 조정 가능.

### ⑥ 같은 날 다시 실행
- 하루에 실행 결과는 하나: 같은 날 다시 실행하면 **오늘의 카드를 새 결과로 교체**한다. 이전 결과는 실행 기록(SC-10)에 남긴다.
- 메일은 **하루 1회**(첫 성공 실행)만 보낸다. 다시 보내기는 앱의 수동 동작으로만.
- 실행 중에는 잠금: 자동 실행과 "지금 실행"이 겹치지 않는다(앱=WorkManager 고유 작업, PC=잠금 파일).
- **이미 본 글은 "이전 날짜"에 본 것만 뺀다.** `SeenStore`가 본 날짜를 함께 저장해서, 같은 날 재실행은 그날 수집분을 다시 쓸 수 있게 한다.

### ⑦ 과거 기록 검색 (⑥ Enrich)
- 대상: 최종 후보 15~20건만.
- 검색어: 후보 제목의 핵심어(불용어 제외, 상위 3개) + 태그 → 코드가 만든다(AI 호출 없음).
- 범위: 최근 90일(`historyDays`)의 수집 항목과 카드. 후보당 최대 3건(요약 300자).
- 구현: 앱=Room FTS, PC=`out/*.json`의 항목·카드를 단어 일치 점수로 검색.
- 작성 프롬프트 규칙: 직접 연결된 것만, 최대 2건 인용(ideas.md).

### ⑧ 피드백 입력과 반영
- 이유 목록(고정): `already-exists`(이미 있음), `too-big`(너무 큼), `not-my-field`(내 분야 아님), `weak-evidence`(근거 약함), `other`(기타 + 메모).
- 앱: SC-01·02에서 입력. PC: `out/feedback.json`을 손으로 작성 — `[{"cardId": "...", "kind": "STAR|NOT_INTERESTED", "reason": "too-big", "memo": "..."}]`.
- 작성 프롬프트에는 최근 10개(`feedbackExamples`)를 `제목 · 분류 · 반응 · 이유` 한 줄씩 넣는다. 최근 카드는 7일(`recentCardDays`) 제목 목록.

### ⑨ 후보 정렬
- 프로필 안: **AI 점수 내림차순 → 출처 점수(0~100) → 여러 곳 등장 수 → 원점수**.
- 상한(프로필·태그)은 이 순서로 앞에서부터 채운다.

### ⑩ 수요 후보 최소 보장 (P4)
- 임계값을 넘은 수요 신호가 있으면 최종 후보에 최소 `minDemandCandidates`(기본 2, 0이면 끔)건을 보장한다. 단, 임계값을 넘은 수요 신호의 **서로 다른 주제 수**까지만 채울 수 있다. 보충분은 태그·프로필 상한을 넘을 수 있다.
- 부족할 때만 부족분만큼: ① 상한에 걸려 빠진 수요 대표를 먼저 넣고 ② 그래도 모자라면 **수요가 아닌 신호(tech·market)가 대표인 주제** 중 병합된 수요 신호를 수요 점수 순으로 대표로 승격한다. 원래 대표는 `mergedUrls`·병합 신호로 남고 주제 정렬 위치는 유지.

### ⑪ 분류 응답 검증 (P4)
- 주제 키는 거부 전에 정규화(소문자, 허용 밖 문자 → `-`, 60자). 점수가 0~10 밖이면 잘라 쓰고 `scoreClampWarnings`로 센다(null은 실패).
- 빠진 항목·중복·**후보 밖 프로필·점수 없음(null)**은 한 번만 재요청(허용 프로필 + `none` 명시, 공급자 대체 없음). 그래도 안 되면 `CLASSIFY_FAILED`와 구체 사유, 예산에 막히면 `BUDGET_EXCEEDED`.
- 묶음은 첫 묶음을 단독으로 끝낸 뒤(프롬프트 캐시 생성을 노림, 생성 여부는 확인하지 않음) 나머지를 **최대** `classifyConcurrency`(기본 3)개까지 동시에 실행한다(빈자리가 나면 다음 묶음 시작). 결과는 원래 순서로 합친다. 예산 경계에서는 어느 묶음이 `BUDGET_EXCEEDED`가 될지가 실행 타이밍에 따라 달라질 수 있다(허용).

### ⑫ 카드 중복 제거 (P4)
- 검증 후 카드를 작성 순서대로 보며, 앞서 **채택된** 카드와 비교해 뒤 카드를 제외(실행 기록 경고): (a) 정규화한 전체 근거 집합이 같거나 (b) 비어 있지 않은 수요 근거 집합이 같고 기술 근거 주제가 겹칠 때(이때는 근거가 포함 관계여도 제외). 그 밖에 근거가 포함 관계이기만 하면 둘 다 남긴다. 분류별·`ideaCount` 상한보다 먼저 적용.

## 7-2. 관심 추적 (★ = 추적 시작)

### 시작·종료
- 카드에 ★ → `TrackedIdea` 생성. 해제 → `status = STOPPED`(기록은 남김). 최대 `maxTracked`(10)개, 넘으면 가장 오래 조용한 것을 멈출지 묻는다.
- **추적 키워드 자동 추출(코드, AI 호출 없음):** 카드 제목 핵심어 상위 3개 + 카드 태그 + 근거 항목들의 `topicKey` + 경쟁 제품 이름. 사용자가 SC-14에서 추가·삭제.
- 30일(`quietDays`) 동안 업데이트가 없으면 `status = QUIET`(조사는 계속, 목록 아래로).

### 매일 조사
1. **오늘 수집분에서 찾기(추가 호출 없음):** ④ 분류 입력에 추적 목록(`trackedId`, 제목, 키워드)을 함께 주고, 항목마다 관련된 `trackedIdeaIds`를 받는다. 관련 항목은 프로필 임계값과 상관없이 추적 근거로 보관한다(카드 후보 선택과는 별개).
2. **전용 검색:** 추적 아이디어마다 출처별 맞춤 검색어(키워드 조합)로 ① 수집 때 함께 조회한다. 일반 수집보다 인기도 기준을 낮춘다(`minRawScoreFactor` 0.3 — 예: HN 20점 → 6점). 이미 본 글은 추적 근거로만 다시 쓸 수 있다(새 카드 후보로는 제외). 아이디어당 출처별 1회(`queriesPerSource`).
3. **(L) 주간 심층 조사:** 주 1회 공급자 웹 검색(보충 검색과 같은 도구)으로 경쟁 제품·시장 변화를 정리. 아이디어당 검색 `maxUses` 3, 비용은 일일 예산에 포함.

### 업데이트 만들기 (⑧-1 Track)
- 그날 관련 항목이 1건 이상인 추적 아이디어만, 분류 모델로 1회 호출 → `TrackingUpdate`(요약 1~2문장, 변화 목록). 프롬프트: `prompts/track.md`(3단계에서 작성). 인용 검증은 카드와 같다(수집된 URL만).
- 변화 종류: `NEW_COMPETITOR`(새 경쟁 제품), `DEMAND_UP`(수요 증가), `DEMAND_DOWN`(수요 감소/불만 해소), `TECH_RELEASE`(관련 기술 새 버전), `RISK_CHANGE`(위험 변화), `OTHER`.
- **신호 강도(코드 계산):** 최근 7일 관련 항목을 프로필별 가중치로 합산(수요 1.5, 경쟁 1.0, 기술 1.0) × 항목 AI 점수/10. **추이:** 최근 7일 대 그 전 7일 — 20% 이상 증가 `UP`, 20% 이상 감소 `DOWN`, 그 외 `FLAT`.

### 보여 주기
- 오늘 화면 추적 줄(SC-01), 추적 탭(SC-13·14), 메일 "추적 업데이트" 칸, 알림(업데이트가 있을 때 하루 1회 묶어서).

## 7-3. 프롬프트 편집 (사용자 수정 가능)

### 대상 (프롬프트 id)
| id | 파일(기본값) | 쓰는 단계 |
|---|---|---|
| `classify` | `prompts/classify.md` | ④ 1차 분류 |
| `profile.<p>.match`, `profile.<p>.analysis` (p = tech-signal·demand-signal·market-signal) | `profiles/<p>/match.md`, `analysis.md` | ④ 1차 분류(시스템 메시지에 붙음) |
| `ideas` | `prompts/ideas.md` | ⑦ 카드 작성 |
| `track` | `prompts/track.md` | ⑧-1 추적 업데이트 |
| `criteria` | `prompts/criteria.default.md` | ⑦ 카드 작성(사용자 메시지) — SC-09 양식으로도 편집 |

### 규칙
- **기본값은 앱 리소스**(AI가 작성, 앱과 함께 배포). **사용자 수정본이 있으면 그것을 쓴다.** 수정본은 기기 저장소(앱=Room, PC=`config/prompts/<id>.md`, 커밋 안 됨).
- 출력 형식(JSON 스키마)·인용 검증·필수 블록 검증은 **코드가 강제**하므로 프롬프트를 고쳐도 깨지지 않는다. 프롬프트는 판단 기준·글쓰기 규칙만 바꾼다.
- 저장할 때마다 버전을 남긴다(최근 10개). 되돌리기 = 수정본 삭제(기본값 사용).
- **기본값 변경 감지:** 수정본에 그때의 기본값 해시(`baseHash`)를 저장해 두고, 앱 업데이트로 기본값이 바뀌면 "기본값 새로 바뀜"으로 표시해 비교 후 선택하게 한다.
- 검증: 빈 내용 금지, 2만 자 상한. 프롬프트 맨 위 `<!-- -->` 설명 주석은 모델에 보내기 전에 제거한다.
- **시험 실행:** 최근 실행에서 고른 5건(분류) 또는 최근 후보(카드·추적)로 저장 전 수정본을 1회 돌려 결과·토큰·비용을 보여 준다. 실행 기록에는 "시험"으로 남고 일일 예산에 포함된다.
- 각 실행의 `RunReport`에 단계별로 쓴 프롬프트 id·버전(기본값이면 `default`)을 남겨, 결과가 달라졌을 때 어느 수정 때문인지 추적할 수 있게 한다.

### 구조
```
core: prompt/PromptStore(인터페이스), PromptResolver(기본값+수정본 → 실제 텍스트, 주석 제거), PromptCatalog(id·파일·설명·입력 설명)
app:  RoomPromptStore     PC: FilePromptStore(config/prompts/)
```

```kotlin
@Serializable data class PromptOverride(val id: String, val text: String, val version: String /* 숫자 또는 "unknown"(이력 불일치) */, val baseHash: String, val editedAt: Instant)
@Serializable data class PromptVersion(val id: String, val version: Int, val text: String, val savedAt: Instant)
```

## 8. 핵심 타입

```kotlin
@Serializable
data class Item(
    val source: String, val externalId: String, val title: String, val url: String,
    val rawScore: Int, val score: Int = 0, val publishedAt: Instant,
    val snippet: String,                         // 최대 300자, 분류에는 이것만
    val author: String?, val commentCount: Int,  // 출처 줄 표시용
    val discussionUrl: String?,                  // 토론 페이지가 따로 있는 출처만(HN·Reddit)
    val extra: Map<String, String>,              // 출처별 부가 정보: license, language, forks ...
    val tags: List<String> = emptyList(),
    val alsoSeenIn: List<String> = emptyList(),  // 여러 출처에 동시 등장
    val profileHint: List<String> = emptyList(), // 출처 설정의 명시 배정/후보
    val comments: List<Comment> = emptyList(),   // ⑥에서 최종 후보만 채움
    val mergedUrls: List<String> = emptyList(),  // ⑤ 주제 합치기로 붙은 중복 항목 URL (근거 유지)
    val trackHint: List<String> = emptyList()    // 추적 전용 검색으로 들어온 경우 그 추적 아이디어 id (§7-2)
)

@Serializable
enum class DropReason { OUT_OF_WINDOW, LOW_SOURCE_SCORE, EXCLUDED_KEYWORD, SUSPECTED_SPAM, DUPLICATE, ALREADY_SEEN, NOT_RELEVANT,
    BELOW_PROFILE_THRESHOLD, SAME_TOPIC, OVER_QUOTA, CLASSIFY_FAILED, BUDGET_EXCEEDED }

@Serializable
data class ClassifiedItem(
    val externalId: String, val profile: String,  // tech-signal | demand-signal | market-signal | none
    val score: Double?,                           // 0~10, 실패 시 null
    val reason: String, val summary: String,      // 한 줄 이유, 한 줄 요약(한국어)
    val tags: List<String>,                       // 구체적 주제 태그 3~5개 (첫 번째가 대표 태그 → 다양성 상한)
    val topicKey: String,                         // 대상 단위 kebab-case, 최근 14일 키 재사용 (§7-1 ④)
    val topicLabel: String,                       // 한국어 짧은 주제 이름
    val trackedIdeaIds: List<String> = emptyList() // 관련된 추적 아이디어 (§7-2)
)

@Serializable
data class TrackedIdea(
    val id: String, val cardId: String, val title: String, val category: String,
    val keywords: List<String>,                   // 자동 추출 + 사용자 편집
    val competitors: List<String>,                // 추적 중 발견된 경쟁 제품 누적
    val status: Status,                           // TRACKING | QUIET | STOPPED
    val startedAt: Instant, val lastUpdateAt: Instant?,
    val strength: Double, val trend: Trend        // 코드 계산 (§7-2)
) { enum class Status { TRACKING, QUIET, STOPPED }; enum class Trend { UP, FLAT, DOWN } }

@Serializable
data class TrackingUpdate(
    val id: String, val trackedId: String, val runDate: LocalDate,
    val summary: String,                          // 무엇이 달라졌나 1~2문장
    val changes: List<Change>,
    val itemKeys: List<String>,                   // 근거 항목 (수집된 것만)
    val strength: Double, val trend: TrackedIdea.Trend
) { @Serializable data class Change(val kind: String, val note: String, val itemKey: String?) }

@Serializable
data class TopicHistory(val topicKey: String, val topicLabel: String, val runDate: LocalDate, val itemKeys: List<String>)

@Serializable
data class MetricSnapshot(val itemKey: String, val rawScore: Int, val at: Instant)   // 급상승 배지용, 30일 보관

@Serializable
data class IdeaCard(
    val id: String, val runDate: LocalDate, // 메일 앱 링크 ideacollector://card/{id}
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
    val caveats: List<String>,            // 확인 필요: 단일 출처, 업체 주장, 커뮤니티 주장 등
    val category: String,                 // categories.json의 id (§7-1 ②)
    val badges: List<String>,             // 코드가 계산: 여러 곳에서 화제·연속 등장·급상승 (§7-1 ⑤)
    val tags: List<String>
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
| hn | Algolia 검색 API | queries: LLM, AI agent, Show HN AI, GPT, Claude, open source model / 24h, 검색어당 50. **수요형 검색어**(Ask HN, alternative to, looking for a tool, frustrated with)는 72h·5점 이상(`queryOverrides`) | minScore 20, 최대 60 | 후보 3종 |
| github | 저장소 검색 API | topic:llm, ai-agent, generative-ai / 14일 내 생성, 스타순, 검색어당 100 | minStars 200, 최대 50 | tech-signal |
| hf | 모델·Spaces API | 트렌딩 정렬 | 종류별 15(`maxItemsPerKind`) 후 출처 상한 | tech-signal |
| reddit | 서브레딧 인기글(OAuth, 키 필요 — 기본 꺼짐) | LocalLLaMA, SaaS, ChatGPT, artificial / day. 본문 전문 3,000자는 작성 단계, 상위 댓글 5개는 분류 입력 | minUpvotes 100 | demand-signal (`subredditProfiles`: LocalLLaMA → tech·demand) |
| ph | GraphQL API(토큰 — 기본 꺼짐) | topic artificial-intelligence, 최근 24시간 이동 창 | minVotes 50 | market-signal |
| arxiv | 공개 API (기본 꺼짐) | cs.AI, cs.CL | 상위 30 | tech-signal |
| rss | RSS/Atom (피드 목록, **기본 켜짐**, 48h) | HF Blog(tech, 5), Simon Willison `ai` 태그 피드(tech·demand, 5), Latent Space(tech, 3) | 피드별 `maxItems`(최신순, 필터 뒤 SourceCap에서) | 피드별 프로필 목록 |

**피드 이용 범위:** 각 사이트가 제공하는 공식 피드만 읽고, 웹페이지는 긁지 않는다. 출시 피드(Product Hunt 공개 피드, BetaList)는 기본 목록에서 뺐다 — PH는 피드 주소를 안내하지만 일반 약관에 크롤링·복제·저장 제한이 있고, BetaList 약관은 개인 열람 범위로 좁아 **자동 수집·저장·외부 LLM 전송의 허용 범위가 확인되지 않았다**. 시장 신호가 더 필요하면 사용자가 약관을 확인한 뒤 피드를 직접 추가하거나, PH API(토큰, `ph` 출처)를 쓴다.

공통 제외 키워드: crypto, giveaway, hiring. 앱의 출처 설정 화면에서 수정한 값은 Room에 덮어써 저장한다.

**RSS 안전 규칙(P3):** 피드마다 격리(한 피드 실패는 "RSS 부분 실패" 경고만, 전부 실패할 때만 출처 실패). 피드별 기한 = min(남은 출처 시간, 피드당 상한), 출처 기한이 먼저 끝나도 완료된 피드는 반환하고 진행 중 HTTP 호출은 취소. 피드 URL의 비밀 후보(쿼리 값 6자 이상·userinfo)는 결과의 모든 문자열에서 `[redacted]`로 치환, 오류에는 상태 코드만. 피드 이름은 설정 라벨이며 중복 이름·비밀값 포함 이름은 설정 단계에서 거부.

## 10. 파이프라인 설정 기본값

```json
{
  "models": {
    "classify": { "provider": "ANTHROPIC", "model": "claude-haiku-5-5" },
    "generate": { "provider": "ANTHROPIC", "model": "claude-opus-5-5" },
    "fallback": true
  },
  "classifyBatchSize": 25,
  "profiles": {
    "tech-signal":   { "threshold": 6.0, "maxCandidates": 8 },
    "demand-signal": { "threshold": 5.0, "maxCandidates": 8 },
    "market-signal": { "threshold": 5.0, "maxCandidates": 4 }
  },
  "classifyConcurrency": 3,
  "ideaCount": 5,
  "maxCardsPerCategory": 2,
  "maxCandidatesPerTag": 2,
  "minDemandCandidates": 2,
  "recentTopicDays": 14,
  "recentCardDays": 7,
  "badges": { "hnPointsPerHour": 10, "githubStarsPerDay": 100, "streakDays": 3, "streakMin": 2 },
  "tracking": { "maxTracked": 10, "quietDays": 30, "queriesPerSource": 1, "minRawScoreFactor": 0.3,
                "strengthWeights": { "demand-signal": 1.5, "market-signal": 1.0, "tech-signal": 1.0 },
                "weeklyDeepDive": { "enabled": false, "maxUses": 3 } },
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
- 같은 날 재실행: 오늘 결과 교체, 메일 하루 1회, 실행 잠금, 이미 본 글은 이전 날짜분만 제외(§7-1 ⑥).
- 비밀값(Anthropic·OpenAI·GitHub·Reddit·Product Hunt 키, Gmail 앱 비밀번호)은 SecretProvider로만 접근. 앱=Android Keystore, runner=환경 변수. 키 값은 로그·실행 기록·예외 메시지에 남기지 않는다.
- 어댑터는 fixtures 기반 테스트, LLM 단계는 가짜 LlmClient로 테스트.
- Claude Opus 5.5 호출에는 서버 측 거절 대비 fallback 설정을 기본으로 넣는다. 공급자를 넘는 대체는 LlmRouter가 맡는다(§5-1).

## 12. 알려진 제약
- 안드로이드 WorkManager는 실행 시각을 보장하지 않음(절전·제조사 배터리 관리). 정시가 필요하면 runner + GitHub Actions.
- Claude 웹 검색/읽기는 로그인 필요한 페이지(X·인스타 피드 등)를 읽지 못함.
- 웹 검색은 1,000회당 약 $10 + 토큰(가격 페이지에서 확인 필요).
- 모델 가격은 공급자가 자주 바꾼다. 가격표는 설정값으로 두고, 비용 표시는 "추정"으로 표기한다.
- 과거 기록 검색은 단어 일치 기반이라 동의어·다른 언어 표현은 못 찾음(Horizon과 같은 한계).

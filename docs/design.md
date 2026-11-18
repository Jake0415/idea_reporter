# 설계 — AI 아이디어 수집기

## 1. 목표
- 정보 검색의 번거로움을 없애고, 매일 아침 **AI 서비스 아이디어 카드 5개**를 근거 링크와 함께 받는다.
- 가볍게: 서버 운영 없음, 월 LLM 비용 약 $3~8 목표.

## 2. 방식 비교 요약 (C안 채택 이유)

| 관점 | A. Claude 검색 전부 | B. 코드 수집 + LLM 마무리 | C. 단계별 모델 분리 (채택) |
|---|---|---|---|
| 사이트별 검색어 | Claude가 매번 정함 | 설정으로 고정 | 설정으로 고정 |
| 정제 | LLM(토큰 소모) | 코드(0원) | 코드(0원) |
| 누락 위험 | 높음 | 낮음 | 낮음 |
| 월 예상 비용 | $15~40 | $3~6 | $2~5 (+보충 검색 시 소폭) |

## 3. 모듈

```
ai-idea-collector/
├── core/    순수 Kotlin(JVM). 수집·정제·LLM·리포트. 저장소·메일은 인터페이스만
├── app/     안드로이드. WorkManager·Room·Compose·Keystore·SMTP
└── runner/  (선택) JVM 실행 파일. GitHub Actions용
```

## 4. 디렉터리

```
core/src/main/kotlin/collector/
├── model/     Item, ClassifiedItem, IdeaCard, RunReport
├── config/    SourceConfig, PipelineConfig, ConfigLoader
├── source/    SourceAdapter + HackerNews/GitHub/HuggingFace/Reddit/ProductHunt/Arxiv
├── refine/    UrlNormalizer, RuleFilter, Deduplicator, Scorer
├── llm/       LlmClient, Classifier, Enricher, IdeaGenerator, Supplement, CostGuard
├── report/    HtmlRenderer, MarkdownRenderer
├── port/      SeenStore, IdeaStore, Mailer, SecretProvider
└── pipeline/  Pipeline, Stage
core/src/main/resources/
├── sources.default.json
└── prompts/   classify.md, ideas.md, criteria.default.md
core/src/test/ fixtures/(출처별 실제 응답) + 단위 테스트

app/src/main/kotlin/collector/app/
├── work/   DailyCollectWorker, WorkScheduler (하루 주기, Wi-Fi·충전 조건)
├── data/   AppDatabase, RoomSeenStore, RoomIdeaStore
├── mail/   SmtpMailer
├── secret/ KeystoreSecretProvider
└── ui/     TodayScreen, ArchiveScreen, SourcesScreen, CriteriaScreen, RunLogScreen
```

## 5. 파이프라인

| 단계 | 내용 | 하루 처리량 | LLM 비용 |
|---|---|---|---|
| ① Collect | 어댑터 병렬 실행, 출처 하나 실패해도 계속 | 300~500건 | 0 |
| ② Filter | 24시간 이내, 출처별 최소 점수, 제외 키워드 | → ~150 | 0 |
| ③ Dedupe | URL 정규화, 제목 유사도, 이미 본 글 제외, 점수 0~100 통일 | → ~80 | 0 |
| ④ Classify | Haiku 5.5, 25건씩 묶어 구조화 출력 | → 20~30 | ~$0.01 |
| ⑤ Enrich | 최종 후보 10건 본문 앞부분 읽기 / (선택) 보충 검색 | 10 | 0 (~$0.03) |
| ⑥ Generate | Opus 5.5, 카드 5개 + 근거 링크 | 5 | ~$0.1~0.2 |
| ⑦ Deliver | HTML 메일, 저장, SeenStore 갱신, 실행 기록 | — | 0 |

## 6. 핵심 타입

```kotlin
@Serializable
data class Item(
    val source: String, val externalId: String, val title: String, val url: String,
    val rawScore: Int, val score: Int = 0, val publishedAt: Instant,
    val snippet: String,            // 최대 300자, LLM에는 이것만
    val tags: List<String> = emptyList()
)

interface SourceAdapter {
    val id: String
    suspend fun fetch(config: SourceConfig, since: Instant): List<Item>
}

@Serializable
data class ClassifiedItem(
    val externalId: String, val relevant: Boolean, val category: String,
    val buildable: Int,             // 1인 개발 가능성 1~5
    val reason: String
)

@Serializable
data class IdeaCard(
    val title: String, val problem: String, val signals: List<String>,
    val enablingTech: String, val effort: String, val differentiation: String,
    val links: List<String>
)
```

## 7. 출처 설정 (`sources.default.json` 초안)

| id | 방법 | 조건 | 1차 필터 |
|---|---|---|---|
| hn | Algolia 검색 API | queries: LLM, AI agent, Show HN AI / 24h | minScore 50 |
| github | 저장소 검색 API | topic:llm, ai-agent, generative-ai / 14일 내 생성, 스타순 | minStars 200 |
| hf | 모델·Spaces API | 트렌딩 정렬 | 상위 30 |
| reddit | 서브레딧 인기글(OAuth 권장) | LocalLLaMA, SaaS, ChatGPT, artificial / day | minUpvotes 100 |
| ph | GraphQL API(토큰) | topic artificial-intelligence, 오늘 | minVotes 50 |
| arxiv | 공개 API (기본 꺼짐) | cs.AI, cs.CL | 상위 30 |

공통 제외 키워드: crypto, giveaway, hiring. 앱의 출처 설정 화면에서 수정한 값은 Room에 덮어써 저장한다.

## 8. 파이프라인 설정 기본값

```json
{
  "classifyModel": "claude-haiku-5-5",
  "generateModel": "claude-opus-5-5",
  "classifyBatchSize": 25,
  "shortlistSize": 10,
  "ideaCount": 5,
  "supplementSearch": { "enabled": false, "maxUses": 3 },
  "dailyBudgetUsd": 0.5
}
```

## 9. 안정성 규칙
- 출처별 격리: 실패·시간 초과 출처는 건너뛰고 메일 하단과 실행 기록에 표시.
- CostGuard: 응답 토큰 사용량으로 비용 누적, 예산 초과 시 이후 LLM 단계 중단하고 그때까지 결과만 발송.
- LLM 응답은 구조화된 출력(JSON 스키마)으로 받고, 파싱 실패 시 1회만 재시도.
- 날짜별 실행 키로 같은 날 재실행 시 중복 카드·메일 방지.
- 비밀값(Anthropic·GitHub·Reddit·Product Hunt 키, Gmail 앱 비밀번호)은 SecretProvider로만 접근. 앱=Android Keystore, runner=환경 변수.
- 어댑터는 fixtures 기반 테스트, LLM 단계는 가짜 LlmClient로 테스트.
- Claude Opus 5.5 호출에는 서버 측 거절 대비 fallback 설정을 기본으로 넣는다.

## 10. 알려진 제약
- 안드로이드 WorkManager는 실행 시각을 보장하지 않음(절전·제조사 배터리 관리). 정시가 필요하면 runner + GitHub Actions.
- Claude 웹 검색/읽기는 로그인 필요한 페이지(X·인스타 피드 등)를 읽지 못함.
- 웹 검색은 1,000회당 약 $10 + 토큰(가격 페이지에서 확인 필요).

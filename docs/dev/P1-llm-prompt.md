# P1 개발 브리프 — LLM 연결부 + 프롬프트 저장소

> 개발: Codex · 리뷰: Claude + OpenAI(Codex review) 동시 → 합의된 지적만 수정 · 수정 반복 최대 5회

## 먼저 읽을 것
- `CLAUDE.md`(작업 규칙, 키 취급 규칙), `docs/design.md` §5-1(LLM 공급자), §7-3(프롬프트 편집), §8(타입)
- 기존 코드: `core/src/main/kotlin/collector/**`, 테스트 `core/src/test/**`

## 환경
- Windows, PowerShell. 빌드: `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"; .\gradlew.bat :core:test`
- 비밀값은 저장소 루트 `.env`(커밋 안 됨)에 있다: `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GITHUB_TOKEN`. `collector.dev.DotEnv`로 읽는다.
- **키 값을 출력·로그·파일·테스트 픽스처·예외 메시지에 절대 쓰지 말 것.** 존재 여부(있음/없음)만 출력.

## 만들 것 (core 모듈)

### 1) `collector.llm`
- `ProviderId { ANTHROPIC, OPENAI }`, `Effort { LOW, MEDIUM, HIGH }`
- `LlmRequest(model, system, user, jsonSchema: JsonObject, schemaName, maxOutputTokens, effort)`
- `LlmResult(json, provider, model, inputTokens, outputTokens, cachedInputTokens, costUsd, refused)`
- `interface LlmProvider { val id; suspend fun listModels(): List<ModelInfo>; suspend fun complete(req): LlmResult }`
- `LlmAuthException`(인증 실패, 재시도 금지), 그 밖의 실패는 일반 예외
- **AnthropicProvider** — 공식 SDK `com.anthropic:anthropic-java:3.3.0`
  - 모델: `claude-haiku-5-5`(분류·추적), `claude-opus-5-5`(작성). `.model(String)` 사용.
  - 구조화된 출력: `OutputConfig.builder().format(JsonOutputFormat.builder().schema(...))`(수동 스키마). 추론 강도: `OutputConfig.effort`.
  - 시스템 메시지: `.systemOfTextBlockParams(...)` + `CacheControlEphemeral`(캐싱). 사용자 메시지는 그날 데이터.
  - **보내지 말 것:** `thinking` disabled, `budget_tokens`, `temperature`/`top_p`/`top_k`(최신 모델에서 400).
  - `stopReason == refusal` → `refused = true`. 토큰: `usage().inputTokens/outputTokens/cacheReadInputTokens`.
  - `claude-opus-5-5`: 서버 측 거절 대비 — beta 엔드포인트에 `server-side-fallback-2026-07-01` 베타 + `fallbacks: "default"`. 빌더 메서드가 없으면 `putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))`. SDK 클래스 이름은 추측하지 말고 컴파일 오류/`javap`로 확인.
  - 테스트용으로 base URL을 바꿀 수 있게.
- **OpenAiProvider** — 공식 SDK `com.openai:openai-java`(Maven Central 최신 안정 버전을 확인해 고정)
  - JSON 스키마 구조화된 출력(strict). 토큰·캐시 토큰 수집. 거절이면 `refused = true`. base URL 변경 가능.
  - 모델 목록 API로 키 확인.
- **ModelCatalog** — 리소스 `models.default.json`: 공급자별 모델·가격(100만 토큰당 입력/출력/캐시 입력 USD)·등급(`fast`/`strong`). Claude: haiku-5-5 $0.10/$0.50, opus-5-5 $4.00/$20.00, 캐시 입력 opus $0.20. OpenAI: **실제 `/v1/models` 목록을 조회해** fast·strong에 맞는 현재 모델 id를 고르고, 가격은 확실하지 않으면 `"priceVerified": false`로 표시(비용은 추정).
- **LlmRouter** — 단계(`classify`, `generate`, `track`, `test`) → `StageModel(provider, model)`. 기본값: 두 키가 있으면 Claude 기준. 실패(인증 제외)·거절·시간 초과면 **다른 공급자의 같은 등급 모델로 1회** 재시도(`fallback` 켜졌고 키가 있을 때). 인증 실패는 즉시 전파.
- **CostGuard** — 일일 예산(`dailyBudgetUsd`) 누적, 초과 시 `BudgetExceededException`. 모든 호출을 `LlmCall(stage, provider, model, promptId, promptVersion, inputTokens, outputTokens, cachedInputTokens, costUsd, fallbackUsed, error)`로 기록 → `RunReport.llmCalls` 필드 추가(기본 빈 목록, 기존 테스트 유지).

### 2) `collector.prompt` (design §7-3)
- `PromptCatalog`: id → 기본값 리소스 경로·이름·단계·입력/출력 설명. id: `classify`, `ideas`, `track`, `criteria`, `profile.<p>.match`, `profile.<p>.analysis`(p=tech-signal, demand-signal, market-signal).
- `PromptStore` 인터페이스: `get(id): PromptOverride?`, `save(id, text): PromptOverride`(버전+1, 최근 10개 기록), `reset(id)`, `versions(id)`.
- `FilePromptStore`(PC): `config/prompts/<id>.md` + `config/prompts/<id>.versions.json`.
- `PromptResolver`: 수정본이 있으면 수정본, 없으면 기본값. 모델에 보내기 전 **맨 앞 `<!-- ... -->` 주석 제거**. `baseHash`(기본값 sha256)로 "기본값 새로 바뀜" 판정. 검증: 빈 내용 금지, 2만 자 상한. 결과에 `(text, promptId, version | "default")`.

### 3) 실제 호출 확인 (PC)
- `collector.dev.Main`에 `--llm-smoke` 옵션: 각 공급자에 아주 짧은 요청 1회(작은 JSON 스키마)로 호출해 공급자·모델·토큰·추정 비용·거절 여부만 출력. 키는 `.env`.

## 테스트
- 기존 테스트 전부 통과 유지.
- 새 단위 테스트(네트워크 없이): 두 공급자의 요청 형태(MockWebServer로 base URL 대체 — 스키마·모델·시스템/사용자 메시지·금지 파라미터 없음 확인)와 응답 파싱, 라우터 대체 규칙(가짜 공급자: 오류→대체 1회, 인증 실패→대체 없음, 거절→대체), CostGuard, PromptResolver(주석 제거·수정본 우선·기본값 변경 감지·검증), FilePromptStore(버전 10개 유지).
- 끝나면 `--llm-smoke`를 실제로 실행해 두 공급자 모두 성공하는지 확인하고, 결과 요약(키 제외)을 마지막 답변에 적는다.

## 하지 말 것
- 커밋·푸시하지 않는다(리뷰 후 Claude가 커밋).
- `docs/` 문서는 고치지 않는다(필요한 설계 변경은 답변에 제안으로).
- 키를 어디에도 출력·저장하지 않는다.

## 완료 기준
- `.\gradlew.bat :core:test` 통과, `--llm-smoke` 두 공급자 성공, 마지막 답변에 변경 파일 목록·결정 사항·남은 문제.

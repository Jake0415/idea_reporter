# R1 — core 실행 런타임 분리 (폰 실행 전환 1단계)

근거: [docs/phone-runner-plan.md](../phone-runner-plan.md). 사용자 결정(2026-11-24)에 따라 폰이 매일 실행하므로, core의 일일 실행·분석·트렌드 조립을 **작업 위치에 의존하지 않는 런타임**으로 분리한다. PC `Main`·Actions 스크립트와 앱이 같은 코드를 쓴다.

## 규칙
- 커밋하지 않는다. `docs/`는 고치지 않는다. 비밀값·주소를 기록하지 않는다.
- 실제 LLM 호출, 메일 발송, 외부 수집은 하지 않는다. 테스트는 가짜 공급자와 MockWebServer로 한다.
- **동작은 바꾸지 않는다.** PC `Main`의 모든 명령(`--scheduled`, `--analyze`, `--trend`, `--backfill`, `--mail*`, `--data-*`, `--set-budget` 등)은 결과가 같아야 한다. 기존 core 테스트 364개가 그대로 통과해야 한다. 테스트를 고쳐야 하면 이유를 보고한다.
- 안드로이드 의존성은 넣지 않는다(순수 JVM).

## 1. `collector.runtime.CollectorPaths`
- 다음 경로를 한 객체로 묶는다.
  - `outDir`
  - `configDir`(그 안의 `prompts/`, `pipeline.local.json`, `sources.local.json`, `analysis.local.json`, `trend` 설정 등)
  - `cacheDir`(기본값 `outDir/cache`)
  - `debugDir`(기본값 `outDir/debug`)
- PC 기본값은 `CollectorPaths.workingDirectory()`이다. `out/`과 `config/`는 지금과 같다.
- **상대 경로를 하드코딩한 곳을 모두 주입식으로 바꾼다.** 아래는 확인된 곳이고, 이 밖에도 `File("out`·`File("config`·`Path.of("config` 등을 전부 찾는다.
  - `dev/Main.kt`의 `File("config/prompts")`, `File("config/sources.local.json")`, `File("config/analysis.local.json")`, `File(".env")`
  - `source/GitHubIssuesAdapter.kt`의 `File("out/cache/github-repos.json")`, `File("out/debug")`
  - `config/PipelineConfig.kt`의 `LOCAL_PATH = "config/pipeline.local.json"`와 `PipelineConfig.load()`
  - `prompt/FilePromptStore.kt`의 기본 생성자 `Path.of("config/prompts")`
  - `data/DataSync.kt`의 `PipelineConfig.load()` 기본 인자 등
- 다른 데서 기본값 경로가 여전히 필요하면 `CollectorPaths`를 통해서만 받는다.

## 2. `collector.runtime.CollectorRuntime`
- 생성자 인자
  - `paths: CollectorPaths`
  - `secrets: SecretProvider`
  - `clock: Clock`
  - 주입 가능한 요소(테스트용): 출처 어댑터 목록, LLM 공급자 목록, `Mailer`, 실행 잠금
- 공개 API. 모두 `suspend`이고, 결과는 화면이 쓸 수 있는 요약 타입으로 돌려준다.
  - `runScheduled(options)`: PC `--scheduled`와 같은 순서(일일 → 일요일 트렌드 → 메일)이며 종료 코드 의미도 같다.
    - `options`에 `mail: Boolean`, `batchOverride: Boolean?`(폰에서 Batch를 끄는 용도), `deadline`, `onStage: (stage) -> Unit`(진행 단계 알림: 수집 중·분류 중·작성 중·메일 중)을 둔다.
  - `runDaily(options)`
  - `estimateAnalysis(themeIds)`: 추정 비용과 예약 가능 여부를 돌려준다.
  - `runAnalysis(themeIds, approvedMaxUsd)`: PC `--analyze --yes`와 같으며 상한 규칙을 유지한다.
  - `publishTrend(date)`
  - `sendMail(kind, key, resend)`
  - `mailTest()`
  - `budgetStatus()`
  - `setBudget(changes)`
- 실행 잠금은 지금의 `withRunLock(out)`을 쓴다. 이미 실행 중이면 `RunLockedException`을 낸다.
- **취소:** 코루틴 취소(서비스가 끊길 때)에도 원장 예약·체크포인트가 안전하게 남아야 한다. 기존 `NonCancellable` 처리를 확인하고, 필요하면 보강한다.
- **진행 단계 콜백:** 파이프라인 단계 경계에서 `onStage`를 부른다. 이 값은 앱이 알림 문구로 쓴다.

## 3. PC `Main`·스크립트
- `Main`의 명령 처리는 `CollectorRuntime`을 호출하는 얇은 껍데기로 줄인다. `.env`는 `CollectorPaths.workingDirectory()` 기준으로 읽는다.
- `scripts/`와 workflow는 바뀌는 명령이 없어야 한다. 바뀌면 함께 고친다.

## 4. 테스트
- `CollectorRuntime`을 **임시 폴더**(작업 위치와 다른 곳)로 만들어 `runScheduled`를 실행한다(가짜 공급자·출처·메일러).
  - `out`·`config`가 그 폴더 안에만 생기는지 확인한다.
  - 작업 위치에는 파일이 하나도 생기지 않는지 확인한다.
- `onStage` 호출 순서를 확인한다.
- 실행 중 코루틴을 취소한 뒤 다시 실행하면 원장·체크포인트가 일관되는지 확인한다.
- `batchOverride=false`이면 Batch 엔드포인트 호출이 0건인지 확인한다.
- 기존 테스트 364개가 통과해야 한다.

## 완료 기준
- 아래가 통과한다: `gradlew.bat -PwithoutApp :core:test --rerun-tasks`, `:core:installDist`, `--help` 수준의 CLI 실행.
- 보고 내용:
  - 찾아서 바꾼 하드코딩 경로 목록
  - `CollectorRuntime` API 요약
  - `Main`에서 옮긴 범위
  - 테스트 개수

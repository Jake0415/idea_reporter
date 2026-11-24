# R1 수정 지시 — 1회차 (Claude 통과·중간 3·낮음 6, OpenAI 높음 3·중간 4·낮음 2)

규칙은 R1과 같다. 커밋·docs 수정·실제 호출 금지. PC 동작 보존.

## 높음
1. **안드로이드 API 26에서 없는 Java API를 없앤다.** (OpenAI)
   - 사용자 폰은 Android 16(API 36)이고 앱 minSdk는 26이다. core가 쓰는 Java 11+ API 중 안드로이드에 늦게 들어온 것은 런타임에 `NoSuchMethodError`를 낸다.
     - `Files.writeString`·`Files.readString`(Android 36.1부터)
     - `HexFormat`(API 34부터)
     - 그 밖의 비슷한 API
   - **core 전체에서 찾아** Kotlin 표준 함수나 API 26에서 되는 방식으로 바꾼다.
     - `File.writeText`·`readText`
     - `Files.write(path, bytes)`
     - 직접 hex 변환 등
   - 대상 예시: `FileHistorySearch.kt`의 원자적 쓰기, `TrendPublisher.kt`, `FilePromptStore.kt`, `RssAdapter.kt`
   - 같은 실수가 다시 들어오지 않게 **검사 테스트**를 둔다. core 소스(또는 클래스 파일)에서 금지 API 목록(`Files.writeString`, `Files.readString`, `HexFormat`, `String.repeat`는 제외 여부 확인, `List.of`/`Map.of`/`Set.of`, `String.isBlank`/`strip`/`lines`(Java 11 String API), `Optional.isEmpty`, `java.net.http`, `Path.of`)을 찾아 실패시킨다.
     - 목록을 정할 때 Android API 26에서 실제로 없는 것만 넣는다. 근거는 developer.android.com API level로 확인한다.
     - Kotlin의 같은 이름 확장 함수는 해당하지 않으니 오탐이 없게 한다.
   - 가능하면 Animal Sniffer 같은 시그니처 검사 도구(안드로이드 API 26 시그니처)를 `:core`에 붙인다. 붙이지 못하면 위 검사 테스트로 대신한다.
2. **취소 후 Batch 재개 테스트를 고친다.** (OpenAI) MockWebServer가 받은 제출 본문의 `custom_id`를 결과 응답에 그대로 쓰게 한다. 요청 횟수와 정산 금액을 실제로 검증한다.
3. **런타임에서 LLM·메일 구현을 주입할 수 있게 한다.** (OpenAI)
   - `CollectorRuntime`의 공급자·메일러 생성을 **팩토리 인자**로 받는다. 기본값은 지금 구현(`AnthropicProvider`, `OpenAiProvider`, `SmtpMailer`)이다.
   - Batch·보충 검색이 `AnthropicProvider`를 직접 참조하지 않게, 필요한 기능을 인터페이스로 뺀다. 예: `BatchCapableProvider`, `WebSearchProvider`.
   - 앱은 R2에서 SDK를 다시 포함하지만, 테스트와 대체 구현을 위해 결합을 끊어 둔다.

## 중간
4. **취소를 실패로 기록하지 않는다.** (Claude) 실행 중 `CancellationException`은 먼저 걸러 그대로 다시 던진다. `runs/failed/`에 실패 기록을 남기지 않는다. 다른 `catch (e: Exception)`에서도 취소를 삼키는 곳이 없는지 확인한다.
5. **JUL 초기화를 PC에서만 한다.** (Claude)
   - `SmtpMailer`의 `LogManager.reset()`은 JVM 전역 설정을 지운다. 안드로이드에서는 logcat 핸들러가 사라진다.
   - 바꾸는 방식: 라이브러리 로거(`jakarta.mail`, `org.eclipse.angus`)만 레벨을 OFF로 두고 `useParentHandlers=false`로 막는다. 전역 reset은 PC `Main` 시작 때 한 번만 하게 옮긴다(옵션 주입).
   - P11의 "주소가 로그에 나오지 않음" 테스트는 그대로 통과해야 한다.
6. **작업 위치 기준 기본값을 없앤다.** (Claude) 아래 4곳의 기본값을 지우고 필수 인자로 바꾼다. 호출하는 쪽은 `CollectorPaths`에서 넘긴다.
   - `PipelineConfig.kt:64`
   - `FilePromptStore.kt:21`
   - `GitHubIssuesAdapter.kt:40-41`
   - `DataSync.kt:171`
7. **`batchOverride=false`일 때 결과는 있는데 정산되지 않은 Batch** (OpenAI) 체크포인트가 `settled=false`이고 결과 파일이 있으면, Batch를 꺼도 **복구·정산은 수행**한다(결과 재사용 포함). 즉시 호출은 그 뒤에 남은 항목에만 한다.
8. **메일 인계 파일 삭제는 잠금 안에서 한다.** (OpenAI) `mail/scheduled.json` 삭제를 실행 잠금을 잡은 뒤로 옮긴다. PC와 런타임에서 중복으로 지우는 것도 정리한다.
9. **onStage와 취소 확인.** (OpenAI)
   - Batch 복구 단계에도 `onStage`(RECOVER 등)를 부른다.
   - `MAIL` 콜백을 부른 뒤에는 `ensureActive()`로 취소를 확인하고 발송하지 않는다.
   - 메일러가 없으면 MAIL 단계를 알리지 않는다. MAIL은 실제 발송 직전에 알린다. (Claude 낮음)
10. **분석 견적 일관성.** (OpenAI) 자동 맞춤안을 재사용할 때도 견적 결과(금액·예약 가능 여부)를 같은 규칙으로 돌려준다. 기본안 재사용과 같은 형식이다.

## 낮음
11. stateHook 기본값을 PC 전용 함수에서 **무동작(no-op)**으로 바꾼다. PC `Main`·Actions가 명시적으로 넘긴다.
12. backfill의 `LocalDate.now()`·`println`은 `clock`·`print` 주입으로 바꾼다.
13. `publicationMailJournal`(Actions의 `../data` 경로)은 `CollectorPaths`에서 빼고 PC `Main` 쪽으로 옮긴다.
14. `--trend`·`--budget-*`는 `.env`가 필요 없으면 읽지 않게 지연 평가한다.
15. 테스트를 보강한다.
    - CWD 격리: 실행 전에 작업 위치에 표식 파일을 만들어 비교한다.
    - GitHubIssues cache/debug 경로 격리
    - 당일 미결 Batch에서 `batchOverride=false`
    - 메일 콜백 취소
    - 분석 견적·승인 상한

## 완료 기준
- `gradlew.bat -PwithoutApp :core:test --rerun-tasks`, `:core:installDist` 통과.
- 1~15를 한 줄씩 보고하고, 금지 API 목록과 근거, 테스트 개수를 적는다.

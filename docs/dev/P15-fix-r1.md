# P15 수정 지시 — 1회차 (OpenAI 높음 1·중간 4·낮음 2, Claude 높음 2·중간 3·낮음 5)

규칙은 P15와 같다. 커밋·docs 수정·실제 GitHub 호출·push 금지, 계측 테스트는 emulator-5554만.

참고: Claude가 직접 실행해 보니 `:core:test`는 Gradle 9.3.1에서 364개 모두 통과했다. JUnit launcher 지적은 실제로 깨지지 않았지만, 아래 1번처럼 명시는 해 둔다.

## 높음
1. **PC·Actions의 core 빌드를 앱과 분리한다.** (Claude)
   - `settings.gradle.kts`에서 `:app`은 안드로이드 SDK가 있을 때만 포함한다. 판단 기준은 `ANDROID_HOME`, `ANDROID_SDK_ROOT` 또는 `local.properties`의 `sdk.dir`가 있고 그 경로가 실제로 있는 경우다.
   - 아니면 `-PwithoutApp`이나 환경 변수로 끌 수 있게 한다.
   - Actions(`daily.yml`·`analyze.yml`·`actions-data.sh`)는 앱을 구성하지 않게 한다(`-PwithoutApp` 또는 `ANDROID_HOME` 미설정 상태와 같게).
   - Kotlin 2.3.10과 AGP 플러그인 해석이 core 전용 빌드에서 불필요하게 일어나지 않게 한다. 플러그인은 `apply false`로 두고 app에서만 적용한다.
   - core에 `testRuntimeOnly("org.junit.platform:junit-platform-launcher")`를 명시한다(버전 카탈로그).
   - wrapper에 `distributionSha256Sum`을 추가한다. 값은 Gradle 공식 체크섬으로 확인한다.
   - 확인 방법: `ANDROID_HOME`을 지운 셸에서 `gradlew :core:test`와 `:core:installDist`가 통과한다.

## 중간
2. **보내기 대기열 실패를 구분한다.** (OpenAI)
   - 토큰 없음, 401, 권한 없음 403 → 요청 상태를 `NEEDS_AUTH`로 두고 재시도하지 않는다. 앱 상태에 "토큰 확인 필요"를 표시한다(5단계 화면에서 쓸 수 있게 노출). 토큰을 다시 등록하면 다시 보낸다.
   - 한도 초과(403 rate limit, 429) → `Retry-After`·`X-RateLimit-Reset`을 존중해 그 시각 이후로 미룬다.
   - 404·422(저장소 없음·검증 실패), 그리고 일정 횟수(예: 5회) 넘게 실패한 경우 → `FAILED`. 낙관적 표시를 되돌리고 사유를 남긴다.
   - 5xx·네트워크 오류 → 백오프로 재시도한다.
   - 301·리다이렉트, 비공개 저장소 404에서 무한 재시도하지 않는다(Claude).
3. **대기열 복구와 저장소 묶기.** (OpenAI)
   - 앱 시작 때와 주기 동기화 때 `PENDING` 요청이 있으면 `OutboxWorker`를 다시 예약한다.
   - 요청 행에 대상 저장소(owner/repo)를 저장한다. 저장소를 바꾸면 이전 저장소의 대기 요청은 보내지 않는다. "이전 저장소 요청 n건 취소됨" 상태로 둔다.
4. **분석 dispatch 중복과 유실.** (OpenAI)
   - dispatch 직전에 상태를 `DISPATCHING`(+시각)으로 저장한다.
   - 응답이 유실되면 바로 다시 dispatch하지 않는다. 다음 동기화에서 `inbox-results/<requestId>`를 확인하고, 그래도 없으면 dispatch 후 10분이 지나야 재시도를 허용한다.
   - 서버 결과가 `analysis_pending`이나 대기 상태이고 `SENT` 후 30분이 지나도 `running`이 없으면 다시 dispatch할 수 있게 한다(같은 requestId, 서버가 멱등).
   - 테스트: 수락 후 응답 유실, 취소된 워크플로.
5. **크기 상한.** manifest와 파일을 스트리밍으로 읽으면서 상한을 적용한다.
   - 파일당 기본 5MB, 동기화 1회 전체 50MB
   - manifest에 적힌 크기가 상한을 넘으면 받지 않는다.
   - 첫 동기화는 파일 단위로 나눠 반영한다. 일관성은 manifest sha 단위로 유지하고, 받은 파일은 임시 저장한 뒤 트랜잭션으로 반영한다.
6. **스키마 버전과 디코드 실패.** (Claude)
   - manifest `schemaVersion`이 앱이 아는 값보다 크면, 재시도하지 않고 동기화 상태를 `APP_UPDATE_REQUIRED`로 두고 알림 한 번을 보낸다.
   - 알 수 없는 artifact 하나가 디코드에 실패해도 동기화 전체를 멈추지 않는다. 그 파일만 건너뛰고 issue로 남긴다.
   - 결과 파일의 schemaVersion 검사도 같은 방식으로 처리한다.
7. **Keystore 다시 등록.** (Claude)
   - `NEEDS_REGISTRATION` 상태에서 다시 등록하면 기존 키를 지우고 새로 만든다.
   - `ProviderException`, `KeyPermanentlyInvalidatedException`을 포함한 Keystore 예외를 잡아 상태로 바꾼다. Worker가 죽지 않게 한다.
   - AES 키 크기는 256으로 명시한다.
   - DataStore 읽기 실패도 `NEEDS_REGISTRATION` 또는 오류 상태로 바꾼다(OpenAI).
8. **R8 안전장치.** release minify에서 core에 남은 LLM·메일 클래스가 제외된 jar를 참조하므로 경고·실패를 막는다. 최소 범위의 `-dontwarn` 규칙을 `proguard-rules.pro`에 넣고, 앱이 그 클래스를 런타임에 부르지 않는다는 점을 주석으로 남긴다. `assembleRelease`로 확인한다.

## 낮음
9. `dataExtractionRules`(Android 12+)를 추가해 비밀값 저장 파일과 DB를 기기 이전에서 뺀다.
10. 테스트를 추가한다.
    - 인증·한도·영구 오류에 따른 상태 전이
    - 앱 시작 시 대기열 재예약
    - 저장소 변경
    - 스키마 상향
    - 알 수 없는 artifact 건너뛰기
    - 키 재등록
    - 크기 상한
    - 고유 작업 정책(WorkManager 테스트 드라이버로 실제 `enqueueUniqueWork`)

## 완료 기준
- `:core:test`(ANDROID_HOME 없는 셸 포함), `:app:testDebugUnitTest`, `:app:connectedDebugAndroidTest`(emulator-5554), `:app:assembleDebug`, `:app:assembleRelease`가 통과해야 한다.
- 1~10 결과를 한 줄씩 보고하고, 테스트 개수를 적는다.

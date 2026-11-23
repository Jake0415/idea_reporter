# P15 — 4단계: `app` 기반 (보기·조작 앱: 동기화·Room·Keystore·WorkManager)

## 사용자 결정 (2026-11-23)
- 수집과 메일은 GitHub Actions만 한다(P14).
- **앱은 보기·조작 앱**이다.
  - `data` 브랜치를 받아 보여 준다.
  - 사용자 조작은 data 브랜치 `inbox/`에 요청 파일로 쓰거나 workflow를 실행해 GitHub에 다시 올린다.
  - **폰에서는 LLM 호출·수집·메일 발송을 하지 않는다.**
- 검증은 에뮬레이터와 자동 테스트로 한다.

## 설계 기준
- `docs/dev/P14-actions-runner.md`: data 브랜치 구조와 inbox 계약. 계약 코드는 `core`의 `collector.data`에 있다.
- `docs/menu.md`(SC·F 번호)
- `docs/design.md` 용어집
- `docs/design-tokens.md` + `mockup/tokens.css`

## 규칙
- 커밋하지 않는다. `docs/`는 고치지 않는다. 비밀값·주소를 기록하지 않는다.
- 실제 GitHub API 호출·실제 push·실제 workflow 실행은 하지 않는다. 테스트는 MockWebServer로 한다.
- 로직은 가능한 한 `core`에 두고, **안드로이드 의존성은 `app`에만** 둔다.
  - `core`의 PC·Actions 동작은 바뀌지 않아야 한다.
  - `:core:test`는 계속 통과해야 한다.
- 안드로이드 SDK
  - `ANDROID_HOME`(`C:\Users\ysh\AppData\Local\Android\Sdk`)에 설치된 최신 안정 플랫폼·빌드 도구로 `compileSdk`·`targetSdk`를 정하고, 고른 버전을 보고한다.
  - `local.properties`는 커밋하지 않는다(`.gitignore` 확인).
- 라이브러리
  - 안정 버전만 쓰고, 고른 버전을 보고한다.
  - 버전 카탈로그 `gradle/libs.versions.toml`을 쓴다(없으면 만든다).

## 1. 모듈 구성
- `settings.gradle.kts`
  - `include(":app")`를 추가한다.
  - 저장소 목록에 `google()`을 추가한다.
- `app` 모듈
  - 플러그인: `com.android.application`, Kotlin Android, Compose 컴파일러
  - `namespace`·`applicationId` = `app.ideacollector`, `minSdk 26`
  - Compose BOM, `implementation(project(":core"))`
- **`core`를 안드로이드에서 쓸 수 있는지 확인한다.** 앱이 쓰는 것은 다음 세 가지뿐이다.
  - 모델·직렬화(카드·추적·실행 기록·트렌드·분석 결과 JSON)
  - `collector.data` 계약
  - 렌더링·용어 같은 순수 로직
- 앱이 쓰지 않는 무거운 의존성(Anthropic/OpenAI SDK, Angus Mail 등)은 앱 APK에서 빠지게 한다. 방법은 아래 중 하나로 하고, 고른 방법과 APK 크기를 보고한다.
  - `core`를 `core-model`(직렬화·계약·순수 로직)과 나머지로 나누기
  - Gradle `exclude`
- release 빌드(`minifyEnabled true`, R8 keep 규칙 포함)도 빌드되게 한다.
- 딥 링크 `ideacollector://card/<id>`, `ideacollector://track/<id>`를 intent-filter로 받는다. 화면 연결은 5단계에서 한다.

## 2. GitHub 연동 (`app/github`)
- 읽기
  - 공개 저장소라 토큰 없이 읽을 수 있다. 다만 한도를 위해 토큰이 있으면 쓴다.
  - 경로: `manifest.json` → 바뀐 파일만(sha256 비교) 받는다.
  - 받는 곳: GitHub REST(contents·git blobs) 또는 raw. 고른 방식과 이유를 보고한다.
  - ETag/조건부 요청으로 데이터를 아낀다.
- 쓰기
  - inbox 요청: Contents API로 `inbox/<ISO시각>-<uuid>.json`을 만든다. 파일 이름이 고유하므로 충돌이 없다.
  - workflow 실행: `POST /repos/{owner}/{repo}/actions/workflows/{file}/dispatches`(analyze.yml).
- **보내기 대기열**
  - 오프라인이거나 실패하면 요청을 Room 대기열에 쌓는다.
  - WorkManager가 네트워크 연결 시 보낸다. 같은 `requestId`로 재시도한다(멱등).
- 처리 결과: 다음 동기화 때 `out/inbox-results/<requestId>.json`을 읽어 요청 상태를 갱신한다. 상태는 대기 → 보냄 → 반영됨/거부(사유).
- 저장소 이름(owner/repo)은 사용자가 설정에서 입력한다. 코드에 하드코딩하지 않는다.
- 필요한 토큰 권한(fine-grained: 이 저장소만, Contents read/write, Actions write)을 상수로 두어 5단계 안내 화면에서 보여 줄 수 있게 한다.

## 3. 저장소 (`app/data`)
- `AppDatabase`(Room)
  - 스키마 export를 켜고 `app/schemas`에 저장한다.
  - 버전 1. 이후 자동 마이그레이션 테스트가 가능한 구조로 만든다.
- **저장할 것**
  - 동기화한 카드, 추적, 추적 업데이트, 실행 기록, 트렌드 리포트, 분석 결과, 비용 요약
  - 보내기 대기열, 요청 상태, 동기화 상태(manifest sha, 마지막 동기화 시각)
  - 앱 설정: 저장소 이름, 동기화 주기, 알림 여부
- **로컬 즉시 반영:** ★·관심 없음 등은 보내기 전에도 화면에 바로 보이게 낙관적으로 저장한다. 거부되면 되돌리고 사유를 남긴다.
- **보관함 검색은 Room FTS4**로 한다(카드 제목·한 줄 설명·근거 메모).

## 4. 비밀값 (`app/secret`, `KeystoreSecretProvider`)
- 저장 대상: GitHub 토큰 하나(이름 `GITHUB_TOKEN`). 이후 추가를 고려해 이름 기반 API로 만든다.
- 저장 방식
  - Android Keystore에 AES-GCM 키를 만든다(내보내기 불가).
  - 암호문은 DataStore에 둔다.
  - `EncryptedSharedPreferences`는 쓰지 않는다.
- API: `set`, `clear`, `has`, `last4`
- 값은 로그·예외·Room·백업에 남기지 않는다.
- 백업 제외: `android:allowBackup="false"` 또는 `dataExtractionRules`로 뺀다.
- 키가 사라져 복호화에 실패하면 "토큰 다시 등록 필요" 상태가 되어야 한다. 크래시하면 안 된다.

## 5. WorkManager (`app/work`)
- `SyncWorker`(주기)
  - 기본 매일 07:40 근처와 앱을 열 때 실행한다. Actions 지연을 고려한 시각이다.
  - 주기 설정을 바꿀 수 있다.
  - 제약: 네트워크.
- `OutboxWorker`
  - 대기열이 있으면 네트워크 연결 시 보낸다. 백오프를 쓴다.
- 고유 작업 이름으로 중복 실행을 막는다.
- 긴 작업이 아니므로 foreground 서비스는 쓰지 않는다.
- 알림
  - 새 daily 결과를 받으면 "오늘 카드 n개"를 알린다(추적 업데이트가 있으면 함께).
  - 분석 결과가 오면 알린다.
  - 알림 채널을 만들고, `POST_NOTIFICATIONS` 요청 함수는 5단계 화면에서 쓰도록 둔다.
  - 알림 내용에 비밀값·주소를 넣지 않는다.

## 6. 최소 화면 (5단계 전 자리만)
- `MainActivity`
  - Compose 하단 탭 4개: 오늘·추적·보관함·설정. 아이콘만 보이고, 스크린리더 라벨은 유지한다.
  - 각 탭은 "준비 중" 빈 화면이다.
- 테마: `mockup/tokens.css` 값으로 Compose `MaterialTheme` 색(라이트·다크)만 먼저 만든다.
- 개발용 숨은 화면(debug 빌드만)
  - 저장소 이름·토큰 입력
  - "지금 동기화"
  - 동기화 상태
  - 테스트 요청 보내기

## 7. 테스트
- `:app:testDebugUnitTest`(필요 시 Robolectric)
  - manifest 비교: 바뀐 파일만 받는다.
  - 동기화 → Room 반영(실제 core JSON 픽스처 사용)
  - inbox 요청 직렬화가 `collector.data` 계약과 같다.
  - 대기열 재시도·멱등
  - 결과 반영: 반영됨·거부 시 되돌림
  - FTS 검색
  - 주기 계산
- `:app:connectedDebugAndroidTest`(에뮬레이터)
  - Keystore 왕복, 키 손상 처리
  - Room 실제 DB
  - WorkManager 테스트 드라이버로 Sync·Outbox 실행(MockWebServer)
  - 에뮬레이터가 없으면 건너뛰고 보고한다.
- 빌드: `:app:assembleDebug`, `:app:assembleRelease`(서명 없이), `:core:test`

## 완료 기준
- 위 빌드·테스트가 통과한다. 계측 테스트는 실행했는지 여부를 명시한다.
- 보고할 것
  - SDK·라이브러리 버전
  - `core` 분리·제외 방식과 APK 크기
  - 동기화 방식
  - Room 테이블 요약
  - 테스트 개수(core·app 단위·계측)

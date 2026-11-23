# R2 — 폰에서 실행 (정각 알람·포그라운드 서비스·예비 실행)

## 근거와 사용자 결정 (2026-11-24)
- 근거: [docs/phone-runner-plan.md](../phone-runner-plan.md) §2~§4. R1의 `CollectorRuntime`을 앱에서 쓴다.
- 사용자 결정:
  - 폰이 매일 07:00(사용자가 바꿀 수 있음)에 실행한다. [지금 실행]도 지원한다.
  - 폰에서는 Batch를 기본으로 끈다.
  - 사업성 분석도 폰에서 실행한다.

## 규칙
- 커밋하지 않는다. `docs/`는 고치지 않는다. 비밀값·주소를 기록하지 않는다.
- 실제 LLM 호출, 메일, 외부 수집을 하지 않는다. 테스트는 MockWebServer·가짜 SMTP·가짜 어댑터로 한다.
- 계측 테스트는 `emulator-5554`에서만 돌린다. 실제 폰에는 설치하지 않는다.
- 사용자 문구는 한국어로, `strings.xml`에 둔다. 용어집(`docs/design.md` 앞부분)을 따른다.

## 1. core를 앱에 포함
- P15에서 `app` 의존성에서 뺀 `anthropic-java`, `openai-java`, `jakarta.mail-api`, `angus-mail`의 `exclude`를 없앤다.
- 안드로이드에서 실제로 호출되는지 **계측 테스트로** 확인한다.
  - Anthropic·OpenAI SDK: MockWebServer를 base URL로 써서 1회 호출
  - Angus Mail: 에뮬레이터 안 가짜 SMTP(로컬 소켓, STARTTLS 없이 테스트 전용 설정)로 1통
  - 인증서 검증을 끄는 코드는 운영 경로에 두지 않는다. 테스트 전용 설정은 `androidTest`에만 둔다.
- R8: P15에서 넣은 `-dontwarn`을 정리하고 필요한 keep 규칙을 넣는다. release APK에서 위 호출 경로가 동작하는지 계측 테스트를 release 설정(또는 minify한 debug 변형)으로 한 번 돌린다.
- APK 크기 변화를 보고한다.
- `restrictLibraryLogs`(JUL 초기화)가 안드로이드에서 문제를 일으키지 않는지 확인한다.

## 2. 앱 저장 위치와 비밀값
- `CollectorPaths`
  - `outDir = filesDir/collector/out`
  - `configDir = filesDir/collector/config`
  - `cacheDir = cacheDir/collector`
- 백업 제외 규칙(P15 `dataExtractionRules`)에 이 폴더의 비밀 관련 파일과 DB를 넣는다. 사용자 데이터 JSON은 수동 내보내기(R4)로 백업한다.
- `SecretProvider`는 P15 `KeystoreSecretProvider`를 쓴다. 대상 이름은 `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GITHUB_TOKEN`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `MAIL_TO`이다(core가 찾는 이름 전부).

## 3. 실행 서비스 `CollectorRunService`
- 포그라운드 서비스이고 `foregroundServiceType="dataSync"`이다. 알림 채널은 "수집 실행"이다.
- 알림은 진행 단계를 표시한다(`onStage`: 수집 중·분류 중·작성 중·메일 보내는 중).
- 동작 순서
  1. 서비스가 `CollectorRuntime.runScheduled(mail = true, batchOverride = 설정값(기본 false), deadline = 시작 후 60분)`을 실행한다.
  2. 끝나면 결과를 Room에 반영한다. P15 Room 캐시를 "폰 out 폴더에서 읽기"로 바꾼다(아래 5).
  3. 서비스를 멈추고 완료 알림을 띄운다. 예: "오늘 카드 5개 · 추적 새 소식 2". 누르면 SC-01을 연다. 실패하면 짧은 사유를 보여 주고, 누르면 SC-10a를 연다.
- 단일 실행
  - 이미 실행 중이면 새 요청은 무시하고 "이미 실행 중"을 알린다.
  - core `RunLockedException`도 같은 문구로 처리한다.
- 취소: 서비스가 시스템에 의해 끊기면 코루틴을 취소한다. 다음 실행이 R1·P13 규칙으로 이어 간다.
- Batch를 켠 경우 2단계로 한다: 제출 → 서비스 종료 → WorkManager 지연 작업(30분)으로 결과 회수 → 카드 작성 → 메일. P13 체크포인트·재개 기능을 쓴다.
- **사업성 분석**도 같은 서비스를 쓴다. 입력은 테마 ID와 승인한 상한이다. 추정은 서비스 없이 바로 한다.

## 4. 예약 — 정각 알람 + 예비 실행
- `RunScheduler`
  - `AlarmManager.setExactAndAllowWhileIdle`로 다음 실행 시각을 예약한다. 기본값은 07:00이고 설정 시각은 Room·DataStore에 둔다. 알람을 받으면 서비스를 시작한다.
  - 정확한 알람 권한(`canScheduleExactAlarms()`)이 없으면 `setAndAllowWhileIdle`(부정확)로 대신한다. 이 상태를 앱 상태로 노출해 R3 화면에서 "정각 실행 꺼짐 · 권한 켜기"로 보여 준다.
  - 매니페스트에 `SCHEDULE_EXACT_ALARM`을 선언한다. `USE_EXACT_ALARM`은 쓰지 않는다(알람 앱 전용 정책).
- **예비 실행:** WorkManager 주기 작업(하루 1회, 고유 작업)이 설정 시각 + 2시간 이후 "오늘 실행 기록 없음"을 확인하면 서비스를 시작한다. 폰이 꺼져 있었거나 알람을 놓친 경우를 위한 것이다.
  - 날짜가 바뀌면 지난날 실행은 하지 않는다. 기록만 남긴다.
- **조건 옵션:** Wi-Fi만, 충전 중만. 둘 다 기본은 꺼짐이다.
  - 조건이 맞지 않으면 WorkManager 제약(UNMETERED·CHARGING)을 건 작업으로 미룬다. 오늘 23:59까지만 기다린다.
- **다시 등록:** 다음 경우에 알람을 다시 등록한다.
  - `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`는 쓰지 않는다(Keystore 접근 불가).
  - `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED`
  - 정확한 알람 권한 변경 브로드캐스트(`ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`)
  - 설정 시각 변경
- **다음 실행 시각:** 계산 함수를 따로 두고, 앱 상태로 노출한다(R3 화면용).
- **배터리 최적화:** `PowerManager.isIgnoringBatteryOptimizations` 상태를 노출한다. 예외 요청 인텐트(`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) 함수와 삼성 절전 예외 설정 화면 이동 함수를 둔다. 실패하면 일반 배터리 설정으로 보낸다. 화면은 R3에서 만든다.
- **실행 누락 감지:** 오늘 설정 시각 + 3시간이 지나도 실행 기록이 없으면 앱 상태를 "오늘 실행 안 됨"으로 둔다(R3에서 경고 띠로 보여 준다).

## 5. 데이터 출처 전환
- P15·P16의 "data 브랜치 동기화"를 **폰 `outDir` 읽기**로 바꾼다.
  - Room 캐시 갱신 함수가 `outDir`의 일일·트렌드·분석·실행 기록·추적·비용 JSON을 읽어 반영한다. 파싱·검증 규칙은 기존 것을 재사용한다.
  - 실행이 끝날 때와 앱을 열 때 갱신한다.
- 피드백·추적·키워드·프롬프트·설정은 **inbox를 거치지 않고** core 저장소에 바로 반영한다.
  - 대상 파일: `feedback.json`, `tracked.json`, `config/prompts`, `config/*.local.json`, `budget` 설정
  - 반영할 때 실행 잠금과 충돌하지 않게 core 쪽 쓰기 함수를 쓴다(R1 런타임에 필요한 함수를 추가해도 된다).
- 더 이상 필요 없는 코드는 지운다.
  - inbox 보내기 대기열
  - `GitHubClient`의 contents·dispatch
  - `SyncWorker`·`OutboxWorker`
  - 관련 Room 테이블: 마이그레이션으로 삭제하거나 미사용으로 두되 스키마 버전을 올린다.
  - 관련 테스트
- 단, P16 화면이 쓰던 상태 타입은 R3에서 바뀌므로 화면이 컴파일되는 최소 연결만 해 둔다.

## 6. 테스트
- 단위 테스트
  - 다음 실행 시각 계산: 자정 넘김, 시각 변경, 시간대 변경, 이미 지난 시각
  - 예비 실행 판단: 오늘 기록 있음·없음, 날짜 변경
  - 조건 미충족 시 미루기
  - 실행 누락 감지
  - outDir → Room 반영
- 계측 테스트(에뮬레이터)
  - SDK·메일 호출(1번)
  - 서비스로 가짜 파이프라인 1회 실행: 알림 단계, 완료 알림, Room 반영, 단일 실행
  - 알람 등록·재등록(`AlarmManager` 조회 또는 테스트 훅)
  - WorkManager 예비 작업(테스트 드라이버)
  - 피드백·추적 직접 반영
- 빌드: `:app:assembleDebug`, `:app:assembleRelease`(lint 우회 없이), `-PwithoutApp :core:test`

## 완료 기준
- 위 빌드·테스트가 통과한다. 계측 테스트를 실행했는지 명시한다.
- 보고: 1~5 결과(각 한 줄), APK 크기, 지운 코드 목록, 테스트 개수.

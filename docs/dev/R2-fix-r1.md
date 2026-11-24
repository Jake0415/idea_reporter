# R2 수정 지시 — 1회차 (OpenAI 높음 4·중간 3·낮음 1, Claude 높음 1·중간 5·낮음 6)

규칙은 R2와 같다.
- 커밋하지 않는다. `docs/`를 고치지 않는다(이 파일 제외).
- 실제 LLM·메일·수집 호출을 하지 않는다.
- 계측 테스트는 emulator-5554에서만 한다.
- 원칙: **이중 과금·중복 메일이 없어야 하고, 그날 실행이 조용히 빠지지 않아야 한다.**

## 높음
1. **워커 안 직접 실행이 10분 제한에 걸려 반복 과금된다.** (Claude H1, OpenAI 2)
   - 이 경로를 타는 경우: 정확 알람 권한이 없을 때, Wi-Fi·충전 조건으로 미뤄진 실행, Batch 이어받기.
   - 워커는 `setForeground(ForegroundInfo(id, 알림, FOREGROUND_SERVICE_TYPE_DATA_SYNC))`로 장시간 작업으로 바꾼다.
     - 매니페스트에 `SystemForegroundService`의 `foregroundServiceType="dataSync"`를 병합 선언한다.
     - Android 12 이상에서 막히면(`ForegroundServiceStartNotAllowedException` 등) 장시간 실행을 하지 않는다. 대신 "배터리 최적화 예외가 필요" 상태를 기록하고 알림을 띄운다. 이 상태는 R3 화면에서 쓴다.
   - **같은 날 자동 시도 상한은 2회**로 한다. 시도 횟수는 날짜별로 파일 또는 DataStore에 기록한다.
     - 상한을 넘으면 `Result.failure`로 끝내고 실패 알림을 띄운다.
     - 취소로 끝난 시도도 횟수에 넣는다.
   - Batch 이어받기는 오래 폴링하지 않는다. 확인해서 아직 진행 중이면 30분 뒤로 다시 미룬다. 미루기는 최대 6회, 이후에는 core 기존 마감·격리 규칙을 따른다.
2. **실행 중에 도착한 예약 요청이 버려지는데 성공으로 처리된다.** (OpenAI 1)
   - 서비스가 이미 실행 중일 때 예약·예비 요청이 오면 버리지 않는다. "대기 중인 예약 실행" 표시를 남기고, 지금 실행이 끝난 뒤 오늘 기록이 없으면 이어서 실행한다.
   - 워커 쪽: `startForegroundService` 호출이 접수됐다고 성공으로 보지 않는다. 실행이 진행 중이면 `Result.retry()`(백오프)로 돌아간다. 다음 시도 때 오늘 기록이 있으면 성공으로 끝낸다.
3. **이미 끝난 날짜에 Batch 이어받기가 전체 실행을 또 한다.** (OpenAI 3, Claude M2)
   - `resume=true` 실행은 그 날짜의 미결 Batch가 실제로 있을 때만 진행한다(`isPendingBatch` 같은 core 조회, 분류·작성 둘 다).
   - 미결 Batch가 없으면 아무것도 하지 않고 성공으로 끝낸다. 기록 확인을 건너뛰지 않는다.
4. **Actions cron과 폰이 같은 날 둘 다 실행된다.** (OpenAI 4)
   - R4 1번을 지금 한다.
     - `.github/workflows/daily.yml`에서 `schedule`을 없애고 `workflow_dispatch`만 남긴다.
     - 머리 주석: "매일 실행은 폰이 한다. 이 workflow는 폰이 고장 났을 때 임시로 쓰는 수동 실행용."
   - `WorkflowTest`의 기대값을 "cron 없음"으로 바꾼다.
   - `scripts/actions-setup.md` 첫머리에도 같은 안내를 적는다.

## 중간
5. **잠금을 잡은 뒤 오늘 기록을 다시 확인한다.** (Claude M1) 워커 직접 실행 경로도 서비스처럼 확인 순서를 CAS 다음에 기록 확인으로 맞춘다.
6. **실행 중 ★·피드백·설정 저장이 실패한다.** (Claude M3)
   - 실행 중이면(`RunLockedException`) 사용자 변경을 Room 대기 테이블에 넣는다.
   - 대기 중인 변경은 실행이 끝난 직후와 앱 시작 때 순서대로 반영한다.
   - 화면 상태에는 "실행 끝나면 반영"을 보여 줄 수 있게 상태를 노출한다(문구 표시는 R3에서).
   - 이 변경에 맞춰 마이그레이션을 추가하거나 고친다(아직 커밋 전이므로 7을 고쳐도 된다).
7. **예비 작업 시각을 바로잡는다.** (Claude M4, OpenAI 5)
   - 첫 지연은 "다음 예비 시각"으로 잡는다. 오늘 예비 시각(설정 시각 + 2시간)이 지났으면 내일 예비 시각으로 한다.
   - 설정 저장·`TIME_SET`·`MY_PACKAGE_REPLACED`·`BOOT_COMPLETED` 때 즉시 유료 실행이 나지 않게 한다.
   - 오늘 놓친 실행은 자동으로 실행하지 않는다. 누락 감지(경고)로만 알린다.
   - 설정 시각이 22:00 이후라 예비 시각이 자정을 넘는 경우도 날짜를 올바르게 계산한다. 예비 실행은 "설정 시각이 속한 날짜"의 실행으로 판단한다.
   - `dispatch()`에서 예외가 나도 다음 알람 등록은 `finally`에서 반드시 한다.
8. **정확 알람 권한이 회수돼도 다시 등록한다.** (Claude M5) 예비 워커도 실행할 때 `register()`를 불러 알람을 다시 잡는다(정확 알람이 안 되면 대체 방식으로).
9. **60분 마감과 취소를 실행 전체에 적용한다.** (OpenAI 6)
   - 예약 메일 재시도와 SMTP 발송 직전에 마감과 취소를 확인한다. 넘었으면 보내지 않고 다음 실행으로 넘긴다.
   - 진행 알림에 [취소] 동작을 넣는다. 취소는 기록하지 않는다.
   - `onTimeout`·`onDestroy`에서 취소한다(이미 있으면 확인만).
10. **6→7 마이그레이션을 정리한다.** (OpenAI 7, Claude L6)
    - 앱은 아직 실사용 전이다. 옛 저장소 키의 행(artifacts·cards·requests 등)은 마이그레이션에서 지운다.
    - 보내기 대기(PENDING)였던 피드백은 가능하면 6번의 로컬 대기 테이블로 옮기고, 어려우면 지운다. 어느 쪽인지 보고한다.
    - `MigrationTestHelper`로 6→7 테스트를 추가한다.

## 낮음
11. **실패 기록을 둘로 나눈다.** (Claude L1)
    - 실패 상태를 따로 기록한다.
    - 비용이 0인 단계(수집 전·수집 중 네트워크 실패 등)에서 실패했으면 그날 예비 실행이 1번 항목의 상한 안에서 다시 시도할 수 있게 한다.
    - LLM 비용이 생긴 뒤에 실패했으면 자동 재시도하지 않는다. 실패 알림을 띄우고 누락 감지에 포함한다.
12. `stopSelf()`를 마지막 `startId`로 부른다. (Claude L2)
13. 실행 뒤 후처리(`trackingUpdates`, `sync.refresh`)의 예외는 실행 결과를 실패로 바꾸지 않게 따로 감싼다. (Claude L3)
14. Wi-Fi 조건 판정을 `NET_CAPABILITY_NOT_METERED` 하나로 맞춘다(즉시 판정과 WorkManager 제약 모두). (Claude L4)
15. 자정을 넘긴 Batch 이어받기는 원래 실행 날짜로 처리한다. `validRunDate` 검사에서 이어받기는 원래 날짜를 쓴다. 미룬 실행이 건너뛴 일요일 트렌드·메일 재시도는 이어받기에서 처리되는지 확인한다. (Claude L5)
16. **계측 테스트를 보강한다.** (OpenAI 8, Claude 테스트 공백)
    - 예비 워커가 실제로 실행을 시작하는지: TestDriver 뒤에 가짜 런타임 호출 횟수를 단언한다.
    - 서비스 시작이 막힌 경우의 대체 경로
    - 잠금 뒤 기록 재확인
    - 미결 Batch 없이 resume
    - 실행 중 ★·설정 저장이 대기 후 반영되는지
    - 6→7 마이그레이션
    - BOOT·TIME_SET 때 즉시 실행하지 않고 다시 등록만 하는지
    - 시도 상한
    - release(R8) 계측의 keep 규칙은 테스트 전용 규칙만 남기고 release와 같은 조건으로 SDK·SMTP 초기화를 검증한다.

## 완료 기준
- 아래가 모두 통과한다.
  - `-PwithoutApp :core:test --rerun-tasks`
  - `:app:testDebugUnitTest`
  - `:app:connectedDebugAndroidTest`(emulator-5554)
  - `:app:assembleDebug`
  - `:app:assembleRelease`
- 1~16을 한 줄씩 보고하고, 테스트 개수를 적는다.

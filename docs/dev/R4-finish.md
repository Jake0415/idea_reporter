# R4 — 마무리: Actions 예약 끄기·백업·문서·실기기 확인

근거: [docs/phone-runner-plan.md](../phone-runner-plan.md) §6 사용자 결정.

## 규칙
- 커밋하지 않는다. `docs/`는 Claude가 고친다(이 지시서 범위에서는 `scripts/`·`.github/`·코드만 다룬다).
- 비밀값은 기록하지 않는다.
- 실제 workflow 실행·push는 하지 않는다.

## 0. 상태 (2026-11-24)
- 1번(Actions cron 제거, WorkflowTest)은 R2에서 이미 끝났다. 다시 고치지 않는다. `scripts/actions-setup.md` 첫머리 안내만 확인해서, 없으면 넣는다.
- R3 커밋 b210de7 기준이다. 공통 실행 상태(`operationRunning`, `finishOperation`)와 실행 중 저장 대기열을 그대로 쓴다.
- 계측 테스트와 캡처는 `emulator-5554`에서만 한다. 캡처는 `out/screens/R4/light|dark`에 저장한다.
  - 대상: 백업 화면, 내보내기 완료, 가져오기 미리보기, 잘못된 파일 오류, 실행 중 막힘
- Gradle을 돌릴 때는 `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`을 직접 지정한다.
- 백업 화면 위치: SC-11 맨 아래 "백업" 구역.
  - 버튼: [내보내기], [가져오기]
  - 안내: "키·앱 비밀번호·메일 주소는 들어가지 않습니다. 새 폰에서는 가져온 뒤 키와 메일을 다시 입력하세요."
  - 문구는 strings.xml에 둔다.
- 가져오기 중에는 공통 실행 상태를 잡는다. 끝나면 `finishOperation`으로 해제한다.
- 가져오기 뒤에는 Room을 다시 반영하고 알람을 다시 등록한다.

## 1. GitHub Actions
- `daily.yml`에서 `schedule`(cron)을 없앤다. `workflow_dispatch`는 남긴다.
- 파일 머리 주석: "매일 실행은 폰이 한다. 이 workflow는 폰이 고장 났을 때 임시로 쓰는 수동 실행용."
- `analyze.yml`은 그대로 수동 실행으로 남긴다.
- `WorkflowTest`의 기대값을 바꾼다: cron이 없어야 한다.
- `scripts/actions-setup.md` 첫머리에도 같은 안내를 적는다.

## 2. 백업 (수동 내보내기·가져오기)
- 위치: 설정 > 백업.
- **내보내기:** 다음을 JSON 묶음 파일 하나로 만들어 사용자가 고른 위치에 저장한다(Storage Access Framework `CreateDocument`).
  - 포함: 카드, 추적, 피드백, 실행 기록 요약, 비용 원장, 설정(`config/*.local.json`), 수정한 프롬프트, 내 기준
  - **제외:** 키·토큰·앱 비밀번호, 메일 주소
  - 파일에 `schemaVersion`과 생성 시각을 넣는다.
- **가져오기:** `OpenDocument`로 파일을 고른다.
  1. 검증한다(스키마 버전, 크기 상한 20MB, JSON 형식).
  2. 미리보기를 보여 준다(카드 n개·추적 n개·기간).
  3. 확인을 받는다.
  4. 현재 데이터를 **임시 백업한 뒤** 반영한다.
  5. 반영이 실패하면 임시 백업으로 되돌린다.
  - 실행 중이면 가져오기를 막는다(실행 잠금).
- core에 JSON 묶음을 만들고 푸는 함수(`collector.runtime.Backup`)를 둔다. 앱은 파일 입출력만 한다. 테스트하기 쉽게 하기 위해서다.
- 테스트
  - 내보내기 → 가져오기 왕복이 일치하는지
  - 키·주소가 들어가지 않는지
  - 잘못된 파일을 거부하는지
  - 실패하면 되돌리는지

## 3. PC 쪽
- `scripts/README.md` 맨 위에 적는다: "매일 실행은 폰. PC 명령(--scheduled 등)은 수동 확인용."
- `register-task.ps1`의 경고 문구를 갱신한다.

## 완료 기준
- 아래가 통과한다.
  - `-PwithoutApp :core:test`
  - `:app:testDebugUnitTest`
  - `:app:connectedDebugAndroidTest`(emulator-5554)
  - `:app:assembleDebug`
  - `:app:assembleRelease`
- 보고: 1~3 결과, 테스트 개수.

## (Claude가 할 일 — 이 지시서 범위 밖)
- 문서 갱신: `CLAUDE.md`, `docs/design.md` §6·§9, `docs/menu.md`
- 실제 폰 설치: 사용자 확인 후 USB 디버깅으로 설치
- 첫 실행 마법사 진행 안내
- 다음 날 07:00 예약 실행 결과 확인

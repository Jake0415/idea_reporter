# P15 수정 지시 — 2회차 (OpenAI 높음 1·중간 4·낮음 2, Claude 중간 1·낮음 3)

규칙은 같다. 커밋·docs 수정·실제 GitHub 호출·push 금지, 계측 테스트는 emulator-5554에서만 한다.

## 높음
1. **50MB를 넘는 변경이 있어도 기존 사용자의 동기화가 멈추지 않게 한다.** (둘 다 지적)
   - 정상 스냅샷이 있을 때도 첫 동기화처럼 **나눠서 반영(PARTIAL)**한다.
     - 받은 파일은 버리지 않고 반영한다.
     - 상태를 `PARTIAL`(대상 manifest sha, 남은 파일 목록)로 바꾼다.
     - 다음 실행에서 남은 것부터 이어 받는다.
   - 반영 중에는 같은 파일의 이전 정상본을 화면이 계속 쓸 수 있게 한다.
   - 대상 manifest가 그사이 바뀌면 새 manifest 기준으로 남은 목록을 다시 계산한다.
   - 테스트: 기존 스냅샷 + 변경 합계 50MB 초과 → 여러 번 실행하면 끝까지 반영된다.

## 중간
2. **분석 dispatch 중복을 막는다.**
   - 다시 dispatch하기 전에 GitHub API로 `analyze.yml`의 최근 실행을 조회한다(`GET .../actions/workflows/analyze.yml/runs`, 마지막 dispatch 시각 이후, status `queued`·`in_progress`·`waiting`·`requested`).
   - 대기·진행 중인 실행이 있으면 다시 dispatch하지 않는다.
   - 실행이 `cancelled`·`failure`로 끝났는데 결과가 없으면, 그 경우에만 재전송을 허용한다.
     - 이렇게 하면 수락됐지만 취소된 dispatch도 복구된다. 결과가 계속 404여도 30분 뒤 이 조회로 판단한다.
   - 조회가 실패하면(권한 등) 재전송하지 않고 "분석 요청 상태 확인 필요"로 둔다.
   - 결과가 없는 `SENT` 분석 요청은 OutboxWorker가 끝없이 백오프하지 않게 한다. 주기 동기화 때만 확인한다. (Claude 낮음)
3. **깨진 artifact 격리.**
   - 기존 파일의 새 버전이 크기 상한을 넘거나 디코드에 실패하면 **이전 정상본과 카드 인덱스를 유지**한다. 해당 파일을 건너뛰고 issue로 남긴다.
   - 결과 파일의 파일명과 `requestId`가 다르면(`require` 실패) 그 파일만 건너뛴다. 파일별 처리에서는 `SerializationException`뿐 아니라 모든 `Exception`을 잡는다(`CancellationException` 제외).
4. **DataStore 손상 복구.**
   - 읽기나 쓰기 실패로 손상이 확인되면, 다시 등록할 때 비밀값 DataStore 파일을 지우고 새로 만든 뒤 쓴다.
   - 등록 함수는 성공·실패를 반환한다. 디버그 화면은 실패하면 "저장 실패"를 표시한다.
   - 테스트: 손상된 파일 → 다시 등록 성공.

## 낮음
5. `scripts/run-scheduled.ps1`·`data-pull.ps1`의 Gradle 호출에 `-PwithoutApp`을 붙인다.
6. 테스트를 추가한다.
   - 결과 404인 상태에서 Worker 재예약
   - 실행 조회: 진행 중이면 재전송 0건, 취소되면 재전송 1건
   - 깨진 새 버전 → 이전 정상본 유지
   - 결과 파일 경로 불일치 → 그 파일만 건너뜀

## 완료 기준
- `:core:test`, `:app:testDebugUnitTest`, `:app:connectedDebugAndroidTest`(emulator-5554), `:app:assembleDebug`, `:app:assembleRelease`가 모두 통과해야 한다.
- 1~6 결과를 한 줄씩 보고하고, 테스트 개수를 적는다.

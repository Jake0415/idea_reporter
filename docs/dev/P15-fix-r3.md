# P15 수정 지시 — 3회차 (Claude 통과·낮음 3, OpenAI 높음 1·중간 1)

규칙은 같다. 커밋·docs 수정·실제 GitHub 호출·push 금지, 계측 테스트는 emulator-5554에서만 한다.

1. **[높음] 동기화를 하나의 커밋 스냅샷에서 읽고, 파일 하나가 실패해도 진행분을 남긴다.**
   - 동기화를 시작할 때 `data` 브랜치 head의 **커밋 sha**를 한 번 조회한다. manifest와 모든 artifact를 그 커밋에서 읽는다(Contents API `?ref=<sha>`, raw도 같은 sha). 그 사이에 게시가 갱신돼도 스냅샷이 일관되게 유지된다.
   - 파일 하나의 404나 SHA·크기 불일치는 그 파일만 실패로 기록한다.
     - 이미 정상으로 받은 파일은 버리지 않고 `PARTIAL` 진행분으로 반영한다(같은 트랜잭션에 남은 목록 포함).
     - 실패한 파일은 남은 목록 끝으로 보낸다.
     - 같은 파일이 같은 커밋 sha에서 3번 실패하면 그 커밋에서는 건너뛰고 issue를 남긴다.
   - 깨진 artifact가 매 동기화마다 다시 받아지는 문제(Claude 낮음)도 같은 규칙으로 막는다. 기준은 "커밋 sha + 파일 sha"이며, 이미 실패로 기록된 조합은 다시 받지 않는다.
2. **[중간, 둘 다] 진행 중 판정을 같은 요청으로 좁힌다.** `ACTIVE` 판정은 `display_title == "Analyze <requestId>"`인 실행만 본다. 다른 요청의 실행이 대기 중이어도 내 요청 복구를 막지 않는다.
3. **테스트**
   - 요청 두 개가 섞인 실행 응답에서 A는 취소, B는 진행 중 → A만 재전송된다.
   - artifact 다운로드 404·SHA 불일치 → 다른 파일은 반영되고 PARTIAL로 남는다.
   - 동기화가 커밋 sha 하나로 고정된다.
   - 같은 실패 조합은 다시 받지 않는다.

## 완료 기준
- `:core:test`, `:app:testDebugUnitTest`, `:app:connectedDebugAndroidTest`(emulator-5554), `:app:assembleDebug`, `:app:assembleRelease` 통과.
- 1~3을 한 줄씩 보고하고 테스트 개수를 적는다.

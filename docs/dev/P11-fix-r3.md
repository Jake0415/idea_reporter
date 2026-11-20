# P11 수정 지시 — 3회차 (Claude 통과, OpenAI 높음 1·중간 1)

규칙 동일(커밋·docs 수정·비밀값/주소 기록 금지, 실제 발송·LLM·스케줄러 등록 금지).

## 높음
1. **나중에 생기는 메일 라이브러리 로거로 주소 노출** — 하위 로거를 미리 차단하는 방식은 Angus가 transport 생성 때 새로 만드는 로거와 JUL 설정 파일의 전용 핸들러를 막지 못한다. 메일 발송을 하는 프로세스(`--mail*`, `--scheduled`, `--analyze` 자동 메일)에서는 SMTP 세션을 만들기 **전에** `LogManager.getLogManager().reset()`으로 JUL 설정(설정 파일에서 읽은 레벨·핸들러 포함)을 비우고, 루트 로거에 WARNING 이상만 출력하는 콘솔 핸들러 하나를 다시 단다. 이 핸들러는 로거 이름이 `jakarta.mail`·`org.eclipse.angus` 로 시작하는 기록을 버리는 필터를 가진다. 기존 하위 로거 차단 코드는 유지(이중 방어). 이 앱은 자체 출력에 JUL을 쓰지 않으므로 다른 기능 영향은 없음을 확인하고 보고.
   - 테스트: JUL 설정에서 `org.eclipse.angus.mail.smtp.level=FINEST`와 그 로거 전용 핸들러를 지정한 상태(설정 문자열을 `readConfiguration`으로 주입)에서 첫 발송 → 출력·파일 어디에도 수신 주소·서버 응답 원문 없음. **하위 로거를 테스트가 미리 만들지 않는다.**

## 중간
2. **DATA 이전 확정 거절이 RSET 시간 초과로 `unknown`이 됨** — 분류에 SMTP 마지막 응답 코드를 쓴다: `sendMessage` 중 예외가 났을 때 transport의 마지막 응답 코드(`getLastReturnCode`)가 4xx·5xx이면 서버가 그 메일을 받지 않았다는 확정 응답이므로 `failed`(재시도 대상). 마지막 응답이 없거나 2xx/3xx(응답 유실·연결 끊김·DATA 본문 뒤 무응답)면 `unknown`. 테스트: `MAIL FROM`에 550 응답 후 `RSET` 응답을 주지 않고 시간 초과 → `failed`; 본문 뒤 무응답 → `unknown`(기존 테스트 유지).

## 완료 기준
- `:core:test --rerun-tasks` 통과. 1~2 한 줄씩 + 테스트 개수.

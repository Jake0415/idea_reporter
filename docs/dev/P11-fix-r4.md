# P11 수정 지시 — 4회차(마지막) (Claude 통과·낮음 1, OpenAI 높음 0·중간 1·낮음 1)

규칙 동일(커밋·docs 수정·비밀값/주소 기록 금지, 실제 발송·LLM·스케줄러 등록 금지).

1. **[중간] 여러 수신자 중 앞선 주소 거절이 `unknown`이 됨** — 분류 순서: 예외 체인에 `SendFailedException`(주소 거절 포함)이 있고 **`DATA`를 보내지 않았으며**(마지막 명령 기록으로 판단) 성공 수신자(`validSentAddresses`)가 비어 있으면 `failed`. DATA를 보낸 뒤라면 기존 규칙(마지막 응답 4xx·5xx → failed, 없음 → unknown). 테스트: 수신자 2명, 첫 `RCPT TO` 550·둘째 250 → DATA 미전송·`failed`.
2. **[낮음] `MISSING_REPORT`면 재시도 단계 로그에 "보고서 없음"으로 표시**(완료와 구분).
3. **[낮음, Claude] 주석** — `restrictLibraryLogs()`에 "메일 발송 때마다 JVM 전역 JUL 설정을 초기화함(이 앱은 JUL을 쓰지 않음, 나중에 JUL을 쓰면 재검토)" 주석.

## 완료 기준
- `:core:test --rerun-tasks` 통과. 1~3 한 줄씩 + 테스트 개수.

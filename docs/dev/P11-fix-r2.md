# P11 수정 지시 — 2회차 (Claude 통과, OpenAI 높음 1·중간 2·낮음 2)

규칙 동일(커밋·docs 수정·비밀값/주소 기록 금지, 실제 발송·LLM·스케줄러 등록 금지).

## 높음
1. **수락 여부 불명확한 실패의 자동 재발송** — 보수적 분류로 바꾼다:
   - 연결·STARTTLS·인증 단계 실패(아직 `sendMessage` 전) → `failed`(자동 재시도 대상).
   - `sendMessage` 중 실패 중에서 **서버가 확정적으로 거절**한 경우(수신자 주소 거절 `SendFailedException`/`SMTPAddressFailedException`, DATA 이전 5xx 응답) → `failed`.
   - 그 밖의 `sendMessage` 중 실패(연결 끊김, 응답 유실, 시간 초과, DATA 이후 오류) → **`unknown`**(자동 재시도 안 함, `--resend`로만). 콘솔·로그에 "발송 여부 확인 필요 — 받은편지함 확인 후 필요하면 --resend".
   - 테스트: 로컬 SMTP가 본문을 받은 직후 응답 없이 연결을 끊음 → `unknown`, 다음 예약 실행에서 SMTP 호출 0회. 인증 실패 → `failed`·다음 실행 재시도.

## 중간
2. `run-scheduled.ps1`이 `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`도 비운다(기존 세 변수와 함께). 테스트 또는 스크립트 문법 검사.
3. 진입점 회귀 테스트: 같은 날 기존 카드가 있고 새 실행의 카드가 0개라 기존 리포트를 유지하는 경로(`preserveCards`)에서 `runMain("--scheduled")`가 오늘 메일을 보내지 않음(SMTP 호출 0회).

## 낮음
4. 메일 재시도 단계에서 개별 실패(발송 실패·기록 실패)가 있으면 단계 로그를 "실패"로.
5. `send()` 반환을 결과 종류(`SENT`, `SKIPPED_DUPLICATE`, `FAILED`, `UNKNOWN`, `RECORD_FAILED`, `MISSING_REPORT`)로 바꿔, 중복 방지에 따른 건너뛰기는 실패로 기록하지 않음. 종료 코드는 기존 규칙(일일 실패 또는 `RECORD_FAILED`면 1).

## 완료 기준
- `:core:test --rerun-tasks` 통과. 1~5 한 줄씩 + 테스트 개수.

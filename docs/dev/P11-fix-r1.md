# P11 수정 지시 — 1회차 (Claude 중간 1·낮음 4, OpenAI 높음 1·중간 3·낮음 1)

규칙 동일(커밋·docs 수정·비밀값/주소 기록 금지, 실제 발송·LLM·스케줄러 등록 금지).

## 높음
1. **SMTP가 수락한 메일 재발송** (OpenAI)
   - 발송 직전에 `sent.json`에 `sending`(시도 시각) 기록을 먼저 원자 저장. 이 저장이 실패하면 **보내지 않는다**.
   - `Transport.send()` 대신 연결 → `sendMessage` → `close`를 나눠, `sendMessage`가 성공하면 `close` 실패는 무시(경고만)하고 성공으로 처리.
   - 성공 후 `sent` 기록 저장이 실패하면 콘솔·로그에 "발송은 됐으나 기록 실패"를 남기고 종료 코드에 반영(메일 단계 실패로 기록).
   - 다음 실행에서 결과 없이 `sending`만 남은 건은 **자동 재시도하지 않고** `unknown`(확인 필요)으로 표시, `--resend`로만 다시 보냄. 테스트(수락 후 close 실패, 기록 저장 실패, sending 잔존).

## 중간
2. **실행 정책** (Claude) — `register-task.ps1`의 작업 인수에 `-ExecutionPolicy Bypass`(이 실행에만 적용, 시스템 설정 변경 없음). `scripts/README.md`의 등록·해제 명령도 `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\register-task.ps1` 형태로.
3. **라이브러리 상세 로그에 주소·응답 원문** (OpenAI) — 메일 발송 시 `mail.debug=false` 강제, JUL 로거 `jakarta.mail`, `org.eclipse.angus.mail`(및 하위)의 레벨을 `WARNING`으로 고정하고 핸들러 출력이 주소를 포함하지 않게(필요하면 해당 로거 `useParentHandlers=false`). `run-scheduled.ps1`은 실행 전 `JAVA_OPTS`·`JAVA_TOOL_OPTIONS`·`CORE_OPTS`를 비워 상속 설정을 차단. 테스트(JUL FINE 설정 상태에서도 로그에 주소 없음).
4. **일일 실패 뒤 같은 날 기존 결과를 오늘 메일로 발송** (OpenAI) — "오늘 메일"은 **이번 실행의 일일 단계가 성공했고 그 결과 파일을 이번 실행이 만들었을 때만**. 기존 파일은 실패 기록 기반 재시도 경로로만 보냄. 트렌드 메일도 같은 규칙(이번 실행의 트렌드 단계 성공). 테스트.
5. **테스트 보강** (OpenAI) — 신뢰되지 않은 인증서·호스트명 불일치·읽기 시간 초과 실패, 재시도 경계(정확히 3일 전 포함, 4일 전 제외), `runMain("--scheduled")` 실제 진입점 경로(가짜 공급자·가짜 SMTP)와 잠금 충돌 시 종료 코드 1.

## 낮음
6. 메일 단계가 `false`(발송 실패)를 반환하면 단계 로그를 "실패"로 기록(종료 코드는 명세대로 일일 결과 기준 — 단 1번의 기록 실패는 예외).
7. `--analyze` 결과가 실패(issues 있음·유효 기회 0)면 자동 메일을 보내지 않음(수동 `--mail analysis`는 가능).
8. 유료 단계 생략 등으로 일일 보고서 파일이 없는 날의 재시도는 "보고서 없음"으로 한 번 기록하고 재시도 중단.
9. 예약 실행 로그에 일일 요약 한 줄(카드 수, 비용, 실패 출처 수 — 주소·비밀값 없음).

## 완료 기준
- `:core:test --rerun-tasks` 통과. 1~9 한 줄씩 + 테스트 개수.

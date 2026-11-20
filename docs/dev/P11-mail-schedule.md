# P11 — 메일 발송 + 자동 실행 (이 PC, 매일 07:00)

사용자 결정(2026-11-20, CLAUDE.md): Gmail SMTP + 앱 비밀번호, 이 PC의 Windows 작업 스케줄러로 매일 07:00 실행, 트렌드 리포트는 일요일.
규칙: 커밋 금지, docs 수정 금지(이 지시서 제외), 키·비밀번호·메일 주소를 코드·설정·로그·커밋에 쓰지 않음, **실제 메일 발송·LLM 호출·작업 스케줄러 등록 금지**(테스트는 가짜 SMTP/GreenMail 등 로컬 서버), 작업 폴더 `D:\project\ai-idea-collector`.

## 할 일
1. **메일 발송** `collector.mail`(core, 설계 문서의 `mail/SmtpMailer` 자리)
   - `Mailer` 인터페이스 + `SmtpMailer`(Jakarta Mail/Angus Mail 등 검증된 라이브러리, 정확한 버전 고정). STARTTLS 필수, 인증서 검증 끄지 않음, 연결·읽기 시간 제한(30초).
   - 비밀값·주소는 기존 SecretProvider(환경 변수 → `.env`)로만: `SMTP_HOST`(기본 `smtp.gmail.com`), `SMTP_PORT`(기본 587), `SMTP_USERNAME`, `SMTP_PASSWORD`(Gmail 앱 비밀번호), `MAIL_FROM`(없으면 USERNAME), `MAIL_TO`(쉼표로 여러 명). 없으면 메일 단계만 건너뛰고 "메일 설정 없음" 안내(실행은 성공).
   - **로그·예외·실행 기록에 비밀번호·주소를 남기지 않는다**(주소는 `a***@gmail.com`처럼 가림). 예외 메시지에서 서버 응답에 섞인 주소도 가림.
   - 본문은 HTML + 텍스트 대체(multipart/alternative). 기존 렌더러 결과 재사용: 일일 카드 `out/<날짜>.html/.txt`, 트렌드 `out/trend/<날짜>.html/.txt`, 분석 결과 `out/analysis/<ID>.html/.txt`. 제목은 렌더러 제목 그대로.
   - **중복 방지:** 종류(daily·trend·analysis)와 키(날짜·분석 ID)별로 `out/mail/sent.json`에 발송 기록(시각, 종류, 키, 결과, 가린 수신자 수). 같은 키는 다시 보내지 않음(`--resend`로만). 설계 규칙 "메일은 하루 1회만 자동 발송" 준수 — 같은 날 재실행해도 일일 메일은 한 번.
   - 실패해도 실행은 실패로 만들지 않고, 실행 기록 issues와 `out/mail/sent.json`에 실패 기록 → **다음 자동 실행에서 미발송분 재시도**(최근 3일 이내만).
2. **명령**
   - `Main --mail-test`: 설정 확인 후 짧은 테스트 메일 1통(제목 "[AI 아이디어 수집기] 메일 설정 확인").
   - `Main --mail <daily|trend|analysis> [--date] [--id] [--resend]`: 수동 발송.
   - `--analyze` 성공 시 분석 결과 메일 자동 발송(설정 `mail.analysis` 기본 켬).
3. **자동 실행 모드** `Main --scheduled`
   - 순서: ① 일일 파이프라인(지금 `Main`과 같은 동작, 예산 설정 그대로) ② 일요일이면(로컬 시간) `--trend` ③ 미발송 메일 재시도 + 오늘 일일 메일 + (일요일) 트렌드 메일.
   - 실행 잠금 그대로, 단계별 실패 격리(①이 실패해도 ②③ 시도, 실패 사유 기록). 종료 코드: 일일 파이프라인 실패 시 1, 그 외 0.
   - 실행 로그 `out/logs/<날짜>.log`(비밀값·주소 없음, 30일 보관 후 삭제).
4. **Windows 작업 스케줄러 스크립트**(`scripts/`, PowerShell, 실행하지 말고 작성만)
   - `scripts/run-scheduled.ps1`: 프로젝트 폴더로 이동, `JAVA_HOME`(스크립트 상단 변수, 기본 `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`), `gradlew.bat :core:installDist -q` 후 `core\build\install\core\bin\core.bat --scheduled`, 출력은 로그 파일로. UTF-8 출력 설정.
   - `scripts/register-task.ps1`: 작업 이름 `AI-Idea-Collector-Daily`, 매일 07:00, **놓친 실행은 켜지면 바로**(`StartWhenAvailable`), 배터리 조건 끔·네트워크 있을 때만, 현재 사용자 권한(관리자 불필요), 이미 있으면 갱신. `-Unregister` 스위치로 삭제. 실행 전 무엇을 등록하는지 출력.
   - `scripts/README.md`(한국어): `.env`에 넣을 항목 이름(값 예시는 가짜), Gmail 앱 비밀번호 만드는 위치(Google 계정 → 보안 → 2단계 인증 → 앱 비밀번호), 테스트 메일 → 등록 → 해제 순서.
5. **문서 자리:** `docs/design.md`의 메일·스케줄 설명은 Claude가 갱신하므로 손대지 말 것.

## 테스트 (로컬 가짜 SMTP)
- 설정 없음 → 건너뜀(실행 성공), 설정 있음 → HTML+텍스트 multipart, 제목, 여러 수신자.
- 비밀번호·주소가 로그·예외·sent.json에 없음(가림 확인).
- 중복 방지(같은 날 두 번 → 1통), `--resend`, 실패 기록 후 다음 실행 재시도(3일 이내만).
- `--scheduled`: 평일은 트렌드 안 함, 일요일은 트렌드+메일, ①실패해도 ②③ 시도·종료 코드.
- 분석 성공 시 메일, 설정 끄면 안 보냄.
- 스크립트는 PowerShell 문법 검사만(`[System.Management.Automation.Language.Parser]::ParseFile`)으로 테스트 또는 수동 확인 보고.

## 완료 기준
- `:core:test --rerun-tasks` 통과. 1~5 한 줄씩 + 테스트 개수 + 추가한 라이브러리와 버전.

# Windows 자동 실행

프로젝트 루트의 `.env`에 아래 항목을 설정합니다.

| 항목 | 값 |
| --- | --- |
| `SMTP_HOST` (선택) | 기본값 `smtp.gmail.com` |
| `SMTP_PORT` (선택) | 기본값 `587` |
| `SMTP_USERNAME` | Gmail 로그인 주소 |
| `SMTP_PASSWORD` | Gmail 앱 비밀번호 |
| `MAIL_FROM` (선택) | 기본값 `SMTP_USERNAME` |
| `MAIL_TO` | 수신 주소. 여러 개면 쉼표로 구분 |

Google 계정 → 보안 → 2단계 인증 → 앱 비밀번호에서 앱 비밀번호를 만든 뒤 `.env`에 입력합니다. 환경 변수가 있으면 같은 이름의 `.env` 항목보다 우선합니다.

```dotenv
SMTP_USERNAME=sender@example.invalid
SMTP_PASSWORD=example-app-password
MAIL_TO=first@example.invalid,second@example.invalid
```

1. 프로젝트 폴더에서 `.\gradlew.bat :core:installDist -q`를 실행한 뒤 `.\core\build\install\core\bin\core.bat --mail-test`로 테스트 메일을 확인합니다.
2. `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\register-task.ps1`로 매일 오전 7시 작업을 등록합니다. 기존 작업은 갱신됩니다. 놓친 실행은 PC가 켜지고 현재 사용자가 로그인한 상태에서 네트워크를 사용할 수 있을 때 시작합니다.
3. `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\register-task.ps1 -Unregister`로 등록을 해제합니다.

일요일에는 트렌드 리포트도 발송합니다. 실행 로그는 `out/logs/<날짜>.log`에 기록합니다. 분석 결과 자동 발송을 끄려면 `config/analysis.local.json`에 `"mail": {"analysis": false}` 항목을 추가합니다.

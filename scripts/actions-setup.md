# GitHub Actions 설정

매일 실행은 폰이 한다. 이 workflow는 폰이 고장 났을 때 임시로 쓰는 수동 실행용.

1. 저장소 Settings → Secrets and variables → Actions에서 다음 Repository secrets를 등록합니다.
   - `ANTHROPIC_API_KEY`
   - `OPENAI_API_KEY`
   - `SMTP_USERNAME`
   - `SMTP_PASSWORD`
   - `MAIL_TO`
   - `GITHUB_TOKEN`은 Actions가 자동으로 제공합니다.
2. Settings → Actions → General에서 Actions 실행을 허용합니다.
3. `data` 브랜치에 강제 푸시 및 삭제 방지를 권장합니다. Actions와 앱의 일반 쓰기는 허용합니다.
4. Actions → Daily collector → Run workflow에서 `main`을 선택해 첫 실행을 시작합니다. 완료 후 `data` 브랜치의 `manifest.json`과 보고서를 확인합니다.
5. 앱에서 `analysis.request`를 먼저 `data` 브랜치의 `inbox/`에 기록한 뒤 Analyze themes에 같은 `requestId`를 전달합니다.
6. 앱용 fine-grained 토큰은 이 저장소만 선택하고 Contents read/write와 Actions write를 부여합니다. Workflows 권한은 제외합니다.
7. Rulesets에서 `main` 브랜치 보호를 권장합니다.

`data` 브랜치와 실행 로그는 공개됩니다. 피드백 메모, 추적 키워드, 인박스 요청, 수정 프롬프트와 수정 이력에는 개인정보나 비밀값을 입력하지 마세요.
피드백 메모, 프롬프트, 설정의 인박스 원본은 인박스 파일이 삭제된 뒤에도 공개 Git 이력에 영구히 남습니다.

## 남은 위험

- SMTP 발송 뒤 결과 push가 끝내 실패하면 원격에는 `sending`이 남습니다. 다음 실행은 이를 `unknown`으로 읽고 자동 재발송을 건너뜁니다. 실행의 `mail-journal` 아티팩트가 있으면 발송 결과 기록을 확인할 수 있습니다.
- `sending`을 push한 직후 SMTP 발송 전에 러너가 끊겨도 다음 실행에 `unknown`으로 표시됩니다. `unknown`이면 받은편지함을 확인한 뒤 메일이 도착하지 않았을 때 `--resend`로 다시 보냅니다.
- Batch 제출 후 상태 push 전에 러너가 끊기면 같은 날 수동 재실행에서 재제출될 수 있음(훅으로 대부분 막음, 금액 하루 수 센트).

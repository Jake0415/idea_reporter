# P14 — 6단계(2): runner + GitHub Actions, data 브랜치 계약

## 사용자 결정 (2026-11-23)
- 매일 실행은 **GitHub Actions만** 한다. PC 작업 스케줄러는 등록하지 않는다.
- 기록은 **같은 저장소의 `data` 브랜치**에 둔다.
- 저장소는 **공개**다. 따라서 data 브랜치와 Actions 로그도 공개된다는 전제로 설계한다.
- 안드로이드 앱은 **보기·조작 앱**이다(P15).
  - data 브랜치를 읽어 보여 준다.
  - ★·관심 없음·프롬프트 수정·설정·분석 요청을 data 브랜치의 `inbox/`에 쓰거나 workflow를 실행한다.
  - 폰에서는 LLM 호출을 하지 않는다.

## 규칙
- 커밋하지 않는다. `docs/`는 고치지 않는다.
- 비밀값·메일 주소·이름을 어떤 파일·로그에도 남기지 않는다.
- 실제 LLM 호출, 실제 메일, 실제 workflow 실행, 실제 push는 하지 않는다.
- 공개 저장소에 맞춰 보안을 지킨다.
  - 서드파티 action은 커밋 SHA로 고정하고, 옆에 버전을 주석으로 단다.
  - `permissions`는 최소로 준다(필요한 job만 `contents: write`, `actions: write`).
  - `pull_request_target`은 쓰지 않는다.
  - 비밀값은 `secrets.*`로만 받고 출력하지 않는다.

## 1. data 브랜치 구조 (앱과의 계약)
아래 구조를 `docs`가 아닌 **코드 상수 + 스키마**로 정의한다. `core`에 `collector.data` 패키지를 두고, 앱(P15)이 그대로 import해 쓴다.

```
data 브랜치 (main과 이력 분리, orphan)
├── manifest.json        # schemaVersion, updatedAt, latestDaily, latestTrend, 파일 목록·크기·sha256
├── out/                 # 공개 허용 목록(allowlist)에 있는 core 출력만
│   ├── <date>.json/.html/.txt, trend/, analysis/, runs/, logs/(요약만), mail/sent.json(수신자 수만), batch/, ...
├── config/prompts/      # 사용자 수정 프롬프트(앱에서 수정 → inbox로 들어와 반영)
├── settings/            # budget.json, schedule.json 등 사용자 설정
└── inbox/               # 앱이 쓰는 요청 큐 — 한 요청당 파일 1개: inbox/<ISO시각>-<uuid>.json
```

- **허용 목록(allowlist):** core가 `out/`에 쓰는 파일을 모두 조사해, 공개해도 되는 것만 고른다. 근거와 함께 표로 보고한다.
  - 제외 예: `out/debug/`, `out/codex/`, 원시 응답 덤프, 비밀값이 섞일 수 있는 것.
  - 피드백 메모·프롬프트 수정본은 공개된다. 이 사실을 보고서에 명시한다.
- **inbox 요청 종류**(kotlinx.serialization sealed class, `type` 필드):
  - `feedback`(cardId, kind STAR|NOT_INTERESTED, reason, memo?)
  - `track.start` / `track.stop` / `track.keywords`
  - `prompt.set` / `prompt.reset`(promptId, 본문, baseHash)
  - `settings.set`(budget·schedule 등 허용 키만)
  - `analysis.request`(themeIds, mode estimate|run, maxUsd)
- 요청마다 `schemaVersion`, `requestId`, `createdAt`이 있다.
- **처리 규칙:**
  - 같은 `requestId`는 한 번만 처리한다(멱등).
  - 처리 결과는 `out/inbox-results/<requestId>.json`에 남긴다(ok/거부·사유). 앱이 이 결과를 보여 준다.
  - 처리한 inbox 파일은 지운다.
  - 잘못된 요청은 거부 결과만 남기고 실행은 계속한다.

## 2. Actions 동기화 도구 (`core` `collector.data` + Main 명령)
- `Main --data-pull <dataDir>`:
  1. data 브랜치 작업 트리(`dataDir`)의 `out/`, `config/prompts/`, `settings/`를 작업 위치에 반영한다.
  2. inbox를 처리한다.
     - 피드백은 `out/feedback.json`에 합친다.
     - 추적·프롬프트·설정은 해당 저장소에 반영한다.
     - 분석 요청은 대기 목록에 넣는다.
- `Main --data-push <dataDir>`:
  1. 허용 목록 파일만 `dataDir`에 복사한다.
  2. 보관 기간이 지난 파일을 정리한다(`historyDays` 등 기존 보관 규칙과 맞춘다).
  3. `manifest.json`을 갱신한다.
  - git 명령은 workflow 스크립트가 한다. Kotlin은 파일만 다룬다.
- 반영할 때 **사용자 수정 프롬프트 검증**(`core`의 기존 검증) 실패 → 거부 결과를 남긴다.
- **설정 값 검증:** 예산은 음수·무한대를 거부한다.

## 3. workflow
### `.github/workflows/daily.yml`
- 언제 실행되나:
  - `schedule: cron '0 22 * * *'` (= KST 07:00, GitHub 지연 가능을 주석으로 단다)
  - `workflow_dispatch`
- `concurrency`: `group: collector-data`, `cancel-in-progress: false`. 분석 workflow와 같은 그룹을 써서 data 브랜치 동시 쓰기를 막는다.
- 단계:
  1. main checkout
  2. data 브랜치를 `../data`로 checkout. 브랜치가 없으면 orphan으로 만든다.
  3. JDK 17 + Gradle 캐시
  4. `--data-pull`
  5. `--scheduled`(env: `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GITHUB_TOKEN`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `MAIL_TO`)
  6. `--data-push`
  7. data 브랜치 commit·push
     - 충돌이면 fetch → rebase → 다시 push, 최대 3회
     - 커밋 메시지: `data: <date> daily`
     - 커밋 작성자: `github-actions[bot]`
- `timeout-minutes: 150` (Batch 대기 90분 고려).
- **실행이 실패해도 메일 기록(`out/mail/sent.json`)은 push한다.** `if: always()` 단계로, 같은 날 재실행 때 메일이 중복되는 것을 막기 위해서다.
- 공개 로그 위생:
  - `--scheduled` 출력에 주소·키가 없는지 확인한다(기존 테스트 활용).
  - 모든 비밀값은 `::add-mask::` 처리한다.

### `.github/workflows/analyze.yml`
- `workflow_dispatch` inputs: `themeIds`, `mode`(estimate|run), `maxUsd`.
- 앱이 분석을 요청하면 이 workflow를 실행한다.
  - `estimate`면 `--analyze` 추정만 하고 결과를 `out/inbox-results`에 남긴다.
  - `run`이면 사용자 상한(`maxUsd`, budget 설정) 안에서 `--yes`로 실행한다. 분석 메일은 자동으로 간다.
- 같은 동기화 단계를 쓰고, 같은 concurrency 그룹이다.

### (선택) `backfill.yml`
- `workflow_dispatch`로 `--backfill`을 실행한다(분류는 입력으로 승인할 때만).

## 4. PC 쪽 정리
- `scripts/README.md`: "자동 실행은 GitHub Actions. PC 작업 스케줄러는 쓰지 않음(메일 중복 방지)"을 맨 위에 쓴다. `register-task.ps1`은 남겨 두되 실행 전 경고를 출력한다.
- `scripts/data-pull.ps1`: data 브랜치를 받아 PC `out/`에 반영한다(PC에서 수동 실행·확인용).
  - PC에서 실행한 결과를 data 브랜치에 올리는 스크립트는 만들지 않는다(기록 충돌 방지).
- `scripts/actions-setup.md`: 사용자가 할 일을 정리한다.
  - GitHub Secrets 등록 목록(이름만)
  - Actions 활성화
  - data 브랜치 보호 설정 권장
  - 첫 수동 실행 방법

## 5. 테스트
- `collector.data`:
  - allowlist 필터: 제외 파일이 절대 복사되지 않음
  - manifest 생성
  - inbox 처리: 종류별 반영, 멱등, 잘못된 요청 거부, 결과 파일
  - 보관 정리
  - 기존 out 형식과의 왕복
- workflow 정적 검사:
  - YAML 파싱
  - 모든 `uses:`가 SHA 고정인지 확인하는 테스트(Kotlin 테스트로 파일을 읽어 검사)
  - `permissions` 존재
  - 비밀값이 `run:` 안에 직접 echo되지 않음
  - `actionlint`·`shellcheck`가 있으면 돌리고, 없으면 그렇게 보고한다.
- `:core:test --rerun-tasks` 통과.

## 완료 기준
- 보고 내용:
  - allowlist 표
  - inbox 요청 스키마 요약
  - workflow 단계 요약
  - 사용자가 할 일(Secrets 이름 목록)
  - 테스트 개수

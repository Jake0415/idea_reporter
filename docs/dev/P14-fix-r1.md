# P14 수정 지시 — 1회차 (OpenAI 높음 2·중간 5·낮음 1, Claude 높음 1·중간 4·낮음 4)

규칙은 P14와 같다. 커밋·docs 수정·실제 실행·push 금지.

## 결정 (Claude)
- **Actions에서도 Batch를 쓴다.** `out/batch/` 전체를 공개하지 않는다. 다음 두 가지만 **공개용 재개 메타데이터**로 data 브랜치에 둔다.
  - 공개 대상: `batchId`, `runId`, `custom_id`→`itemKeys`, 요청 지문(fingerprint), 예약 상태, 제출·정산 상태
  - 공개 대상: `out/batch/public/*.json`
- 요청 본문(`system`·`user`)과 결과 JSONL은 공개하지 않는다.
- 다음 실행에서 결과가 필요하면 Batch ID로 API에서 다시 받는다(Anthropic 결과 보관 기간 안).
- 동기 대체는 현재 수집 항목으로 요청을 다시 만든다. P13 재개 로직이 본문 없이 메타데이터만으로 동작하게 맞추고, 지난 날짜 결과 재사용은 Actions에서 하지 않아도 된다.
- 기본 설정은 `batch.classify=true`다. workflow에서 Batch를 끄는 코드는 없앤다.

## 높음
1. **inbox 처리 실패가 매일 실행을 멈추지 않게 한다.** (Claude)
   - 요청 단위로 모든 `Exception`을 잡아 거부 결과(`internal_error`, 짧은 사유)로 기록하고 그 inbox 파일을 지운다.
   - workflow 단계를 이렇게 나눈다.
     - `data-pull`(동기화)이 실패하면 → 이전 상태로 수집을 계속하고 issue를 남긴다.
     - inbox 처리가 실패하면 → 수집은 계속한다.
   - 비 JSON 파일, 하위 디렉터리, 파일명 형식 오류도 거부 결과로 처리하고 지운다. requestId를 알 수 없으면 파일명 해시를 쓴다.
2. **공개 산출물 정제.** (OpenAI) `publicOutput`에서 필드별 공개 범위를 강제한다.
   - 수집 항목: 제목, URL, 출처, 점수, 짧은 요약(분류 요약 또는 300자 이내 snippet), 태그, 분류 결과만 남긴다.
   - **제거:** 댓글 본문, 작성자, 원문 본문 전체, 원시 응답.
   - 모든 자유 텍스트에 정제기를 적용한다. 대상은 카드, 피드백 메모, 추적 키워드, 프롬프트 수정본, 설정, 로그, inbox 결과 사유다.
     - 이메일 주소 → `[이메일]`
     - `sk-ant-`·`sk-proj-`·`sk-`+32자 이상·`ghp_`·`github_pat_`·`xox[bp]-`·Bearer 토큰 → `[비밀값]`
   - 정제는 공개 사본에만 한다. 앱이 읽는 필드(카드 근거 메모 등)의 의미는 유지한다.
   - 테스트: 주소·토큰이 든 항목·메모·프롬프트 → 공개 사본에 없음.
3. **메일 발송 전에 보고서를 먼저 올린다.**
   - daily workflow 순서를 이렇게 바꾼다.
     1. `--data-pull`
     2. `--scheduled --no-mail` 실행
     3. `--data-push` + push(보고서·상태)
     4. `--mail-scheduled` 실행: 같은 메일 규칙으로 이번 실행에서 발행된 보고서만 보낸다.
     5. 메일 기록만 `--data-push` + push. 실패하면 재시도 3회를 하고 아티팩트로도 보관한다.
   - 메일 단계는 job 마감 전 여유가 있을 때만 시작한다. 시작 시 남은 시간이 10분 미만이면 보내지 않고 다음 실행 재시도로 넘긴다.
   - 앱 마감(`deadlineMinutes`)은 workflow의 job 시작 시각을 환경 변수로 받아 계산한다. timeout 150분에서 빌드·push·메일 여유 20분을 뺀 값이다.
   - 같은 날 수동 재실행이 메일 기록 유실로 중복 발송할 수 있는 남은 위험은 `scripts/actions-setup.md`에 적는다.

## 중간
4. **push 충돌·manifest.**
   - push 재시도 루프에서 rebase 뒤 **manifest를 최종 트리 기준으로 다시 만들어** amend한다.
   - rebase 충돌이 나면 `set -e`로 끝내지 않는다. 이렇게 처리한다.
     1. `inbox/`(원격 것 유지)
     2. `out/mail/sent.json`(두 쪽을 병합: 같은 키는 sent > unknown > failed 우선)
     3. 나머지(이번 실행 것)
     4. 위 규칙으로 해결한 뒤 계속한다.
   - 테스트: 스크립트의 병합 함수를 Kotlin으로 옮겨 단위 테스트한다. 예: `Main --data-merge-mail`.
5. **concurrency.** 대기 중인 일일 실행이 다른 실행에 밀려 취소되지 않게 한다.
   - GitHub 공식 문서로 사용 가능한 옵션을 확인한다(`queue` 등은 실제 지원 여부를 확인).
   - 지원되지 않으면 이렇게 바꾼다.
     - daily와 analyze의 concurrency 그룹을 분리한다.
     - 동시 쓰기는 4번의 rebase·병합 규칙으로 처리한다.
   - 고른 방식과 근거를 보고한다.
6. **분석 요청 짝 맞추기를 requestId로.** (둘 다 지적)
   - 앱은 inbox에 `analysis.request`를 쓴 뒤 `analyze.yml`을 **`requestId` 하나만 입력으로** 실행한다.
   - 서버는 requestId로 정확히 그 요청을 찾는다. 매개변수 비교(`Double` 포함)는 없앤다.
   - 처리 결과에 `dispatchRunId`를 함께 기록한다. 같은 requestId 재실행은 기존 결과를 돌려주고 다시 과금하지 않는다.
   - 짝이 없는 큐 항목은 7일 후 만료 거부 처리한다.
   - inbox 쓰기만 하고 dispatch가 실패한 요청은 다음 daily 실행에서 `estimate`만 처리한다. `run`은 실행하지 않고 "분석 실행 대기 — 앱에서 다시 요청" 결과를 남긴다.
7. **PC 가져오기와 보관.**
   - `--data-import`는 로컬 `mail/sent.json`을 덮어쓰지 않고 4번 규칙으로 병합한다.
   - 보관 정리는 **활성 추적이 참조하는 날짜의 일일 JSON과 `state`를 유지**한다(추적 시작일 이후).
8. **계약 안정성.** (Claude)
   - inbox JSON 해석에 `ignoreUnknownKeys = true`를 쓴다. 필수 필드는 그대로 검증한다.
   - 길이 상한:
     - memo 500자
     - 추적 키워드 10개·각 50자
     - 프롬프트 본문 20,000자
   - `inbox-results`는 90일 보관한 뒤 정리한다.
   - `manifest.schemaVersion`을 올리는 기준(호환 안 되는 변경)을 `collector.data`에 주석으로 남긴다.

## 낮음
9. `MAIL_TO`가 쉼표로 여러 주소면 주소마다 `add-mask`한다.
10. workflow 테스트를 추가한다.
    - `run:` 안에 `${{ inputs.* }}`·`${{ github.event.* }}`가 직접 삽입되지 않는지
    - `pull_request`·`pull_request_target` 트리거가 없는지
    - 경로 탈출 inbox 입력(`../`, 절대 경로, 잘못된 UUID)이 거부되는지
11. `scripts/actions-setup.md`에 아래를 추가한다.
    - 앱용 fine-grained 토큰은 이 저장소만, Contents read/write + Actions write로 만들고 **Workflows 권한은 빼라**는 안내
    - main 브랜치 보호(ruleset) 권장
    - 공개되는 것: 피드백 메모·프롬프트·설정은 inbox를 지워도 **git 이력에 영구히 남음**

## 완료 기준
- `:core:test --rerun-tasks`가 통과해야 한다.
- 보고: 1~11을 한 줄씩, Batch 공개 메타데이터 필드 목록, concurrency 결정 근거, 테스트 개수.

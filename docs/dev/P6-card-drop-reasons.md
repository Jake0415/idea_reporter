# P6 — 카드 검증 탈락 사유 기록 (F-실행 기록)

배경: 실제 실행에서 작성 모델이 카드 3장을 냈는데 검증 후 2장만 남았다. 중복 경고는 없었고 어느 검증에서 빠졌는지 기록이 없다. 지금은 `CardValidator`가 중복일 때만 `onDuplicate`로 알리고, 나머지(필수 항목·분류·태그·인용·분류별 상한·ideaCount 상한)는 조용히 `null`로 버린다.

규칙: 커밋 금지, docs·프롬프트 수정 금지(이 지시서 제외), 키 출력 금지, 실제 LLM 실행 금지. 작업 폴더 `D:\project\ai-idea-collector`.

## 할 일

1. **탈락 사유 콜백** (`stage/IdeaGenerator.kt` `CardValidator.validate`)
   - `onDuplicate: (IdeaCard) -> Unit` 를 `onRejected: (IdeaCard, CardRejection) -> Unit` 로 바꾼다. `CardRejection`은 사유 종류(enum)와 사람이 읽는 한국어 상세 문자열을 가진다.
   - 종류와 상세(예시 문구, 표현은 다듬어도 됨):
     - `MISSING_FIELD` — "필수 항목 비어 있음: problem, effort" (비어 있는 필드 이름을 모두)
     - `INVALID_CATEGORY` — "분류 id 오류: xyz"
     - `INVALID_TAGS` — "태그 없음" 또는 "빈 태그 포함"
     - `CITATION` — 인용 검증 실패. **어느 블록이 왜 비었는지**를 쓴다: "기술 신호 근거 없음(인용 2건 무효: URL 불일치 1, 블록-프로필 불일치 1)" / "수요·경쟁 근거 없음(...)" 형식. 무효 사유 종류: 목록에 없는 itemId·URL 조합(URL 불일치), note 빈칸, 블록에 맞지 않는 프로필, 다른 블록과 같은 항목 중복 사용.
     - `DUPLICATE` — 기존 중복 규칙 그대로. 상세에 겹친 앞 카드 제목.
     - `CATEGORY_CAP` — "분류 상한 초과: dev-tools (최대 N)"
     - `IDEA_COUNT_CAP` — "카드 수 상한 초과 (최대 N)"
   - 검사 순서와 판정 결과는 **지금과 동일**해야 한다(통과하는 카드 목록이 바뀌면 안 됨).
2. **인용 상세** (`CitationValidator`)
   - `validate(card)`는 그대로 두거나, 결과(통과 카드 또는 null)와 함께 블록별 무효 인용 집계를 돌려주는 메서드(예: `check(card): CitationCheck`)를 추가해 `CardValidator`가 쓴다. 기존 호출부·테스트가 깨지지 않게.
   - 카드가 **통과했지만 일부 인용이 제거된 경우**도 알린다: `onCitationsTrimmed(card, detail)` 같은 별도 콜백(기본값 빈 함수), 상세 "인용 1건 제거: note 빈칸 1".
3. **실행 기록 연결** (`pipeline/FullPipeline.kt`)
   - `issues += "카드 제외 (제목) · 상세"` 형식으로 모든 탈락을 기록한다(기존 "중복 카드 제외 (제목)"도 이 형식으로 통일). 인용 일부 제거는 `"인용 일부 제거 (제목) · 상세"`.
   - 작성이 재시도되어 `validate`가 두 번 불리면, 첫 시도의 기록은 앞에 "1차 시도: "를 붙여 구분한다(재시도가 없으면 접두어 없음).
   - `RunMetrics`에 탈락 종류별 개수(`cardRejections: Map<String, Int>`, 최종 시도 기준)를 추가한다. 직렬화 기본값은 빈 맵(기존 결과 파일 읽기 호환).
4. **리포트**: HtmlRenderer/텍스트 리포트가 issues를 이미 보여주면 추가 작업 없음. 보여주지 않으면 그대로 둔다(화면은 나중).

## 테스트 (PipelineStagesTest 등)
- 종류별 1개 이상: 필수 항목 빈칸(필드 이름이 상세에 들어감), 분류 오류, 태그, 인용(기술 블록 비어 있음 / 수요·경쟁 블록 비어 있음, 무효 사유 집계 숫자 확인), 중복(앞 카드 제목), 분류 상한, ideaCount 상한.
- 통과 카드 목록이 기존 테스트 기대값과 같음(기존 중복 테스트는 새 콜백으로 옮기되 기대값 유지).
- 인용 일부 제거 콜백.
- FullPipeline: 탈락이 issues와 metrics.cardRejections에 남는지, 재시도 시 "1차 시도: " 접두어.

## 완료 기준
- `:core:test --rerun-tasks` 통과. 1~4 한 줄씩 + 테스트 개수 보고.

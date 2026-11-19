# AI 아이디어 수집기 (ai-idea-collector)

개인용 도구. 매일 AI 관련 트렌드를 주요 사이트에서 수집·정제하고, LLM으로 "1인이 만들 수 있는 AI 서비스 아이디어 카드"를 만들어 메일로 받는다.

## 작업 규칙
- 사용자에게 보이는 모든 설명(진행 상황 한 줄 포함)은 **한국어**로 쓴다.
- 기획·초기 단계: `main` 브랜치에 바로 커밋한다. 브랜치·PR은 만들지 않는다.
- 설계 기준 문서: [docs/design.md](docs/design.md)(데이터 흐름·타입), [docs/menu.md](docs/menu.md)(기능 F-xx·화면 SC-xx·메일). 구조나 결정이 바뀌면 함께 고친다.
- 화면·기능은 SC-xx / F-xx 번호로 부른다(코드·커밋 메시지 포함).

## 확정된 결정
- 방식: **C안** — 고정 출처는 코드로 수집·정제(LLM 0원), 싼 모델로 1차 분류, 좋은 모델로 최종 카드 작성. Claude 웹 검색은 보충용(선택, max_uses 3)만.
- 형태: 별도 웹 서비스 없음. **Kotlin 안드로이드 앱** + 결과 메일 발송.
- 로직은 순수 Kotlin(JVM) `core` 모듈에 두고, 안드로이드 의존성은 `app`에만. 실행 시각 보장이 필요해지면 `runner`(JVM) + GitHub Actions로 같은 `core`를 돌린다.
- 인스타그램 제외(API 제한·약관 위험). X는 필요 시 선별 계정만(유료).
- 출처: Hacker News, GitHub, Hugging Face, Reddit, Product Hunt, (선택) arXiv — 공식 API 우선, HTML 파싱은 피한다.
- 모델: 1차 분류 `claude-haiku-5-5`, 최종 작성 `claude-opus-5-5`. 일일 비용 상한(`dailyBudgetUsd`)을 둔다.
- [Horizon](https://github.com/Thysrael/Horizon)(MIT) **설계만 차용**해 Kotlin으로 새로 구현: 신호 프로필 3종(기술·수요·경쟁), 프로필별 임계값, 주제 합치기, 분류별 상한, 댓글 수집, 과거 기록 검색, 인용 검증, 출처 통계, RSS 입력 어댑터. 근거는 [docs/verification/horizon-rea-check.md](docs/verification/horizon-rea-check.md). 코드·프롬프트를 옮기면 `THIRD_PARTY_NOTICES.md`에 고지.
- MVP 하단 탭은 오늘·보관함·설정. 수집함(SC-05)은 나중이지만 탈락 이유 기록은 1단계부터.

## 미정 사항
- 평가 기준(`criteria.default.md`): 1인 개발 여부, 대상 고객, 제외 분야, 수익화 방식 — 사용자 입력 대기.
- 사용자 폰 OS(안드로이드 전제), 정시 도착 필요 여부(→ runner 도입 시점).

## 개발 순서
0. 메뉴·기능 설계 — 완료([docs/menu.md](docs/menu.md))
1. `core` 골격, `Item`, HN·GitHub 어댑터, 규칙 필터, 스팸 묶음 거르기, 중복 제거, 탈락 이유 기록 — 완료(PC 실행·테스트 확인)
2. Classifier(프로필 배정·주제 합치기) + 선택(임계값·상한) + 댓글·과거 기록 + IdeaGenerator(피드백 반영) + 인용 검증 + 비용 기록 + HtmlRenderer
3. 나머지 어댑터 4개, 설정 파일 연동
4. `app`: WorkManager, Room, Keystore, SMTP 메일
5. 앱 화면: 오늘 카드·보관함·출처/평가 기준 수정·실행 기록
6. (선택) 보충 검색, Batch API, runner + GitHub Actions

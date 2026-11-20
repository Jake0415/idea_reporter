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
- LLM: **Claude(Anthropic)·OpenAI 두 공급자 지원, 사용자가 자기 키를 등록하면 연동**(design.md §5-1). 단계별(분류·작성)로 공급자·모델 선택, 모델 목록은 공급자 API에서 받음, 두 키가 있으면 대체 공급자 1회 재시도. 기본값(Claude): 분류 `claude-haiku-5-5`, 작성 `claude-opus-5-5`. 일일 비용 상한(`dailyBudgetUsd`).
- **키 취급:** API 키는 코드·문서·설정 파일·로그·커밋에 절대 쓰지 않는다. PC는 환경 변수(`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`), 앱은 Keystore.
- [Horizon](https://github.com/Thysrael/Horizon)(MIT) **설계만 차용**해 Kotlin으로 새로 구현: 신호 프로필 3종(기술·수요·경쟁), 프로필별 임계값, 주제 합치기, 분류별 상한, 댓글 수집, 과거 기록 검색, 인용 검증, 출처 통계, RSS 입력 어댑터. 근거는 [docs/verification/horizon-rea-check.md](docs/verification/horizon-rea-check.md). 코드·프롬프트를 옮기면 `THIRD_PARTY_NOTICES.md`에 고지.
- 디자인 톤: A 피드(인스타그램풍, docs/design-tokens.md). MVP 하단 탭은 오늘·추적·보관함·설정(아이콘만).
- **프롬프트는 사용자가 수정 가능**: 기본값(AI 작성, 리소스) + 사용자 수정본 우선, 버전·비교·되돌리기·시험 실행. 출력 형식·인용 검증은 코드가 강제(design.md §7-3). 프롬프트: classify·ideas·track·criteria·프로필 6개.
- **★ = 관심 추적 시작**: 추적 아이디어를 매일 계속 조사(수집분 매칭 + 전용 검색), 업데이트 요약·신호 강도 추이(design.md §7-2). 수집함(SC-05)은 나중이지만 탈락 이유 기록은 1단계부터.

## 2026-11-20 사용자 결정
- 평가 기준(`criteria.default.md`) 확정: 대상 고객 제한 없음(개인 개발자·소규모 팀·일반 사용자·B2B), 수익화 4종 모두 가능, 제외는 모델 학습·GPU 인프라·하드웨어만, 개발 범위 1인 3개월 이내 MVP.
- 다음 개발: **메일 발송(Gmail SMTP + 앱 비밀번호, 비밀번호는 사용자가 .env에 직접) + 자동 실행(이 PC, Windows 작업 스케줄러, 매일 07:00, 트렌드 리포트는 일요일)**.
- 출처 약관: 개인용 도구로 HN 유지(상업화 시 재검토), Reddit 보류(사전 승인 필요).

## 미정 사항
- 사용자 폰 OS(안드로이드 전제).

## 개발 순서
0. 메뉴·기능 설계 — 완료([docs/menu.md](docs/menu.md))
1. `core` 골격, `Item`, HN·GitHub 어댑터, 규칙 필터, 스팸 묶음 거르기, 중복 제거, 탈락 이유 기록 — 완료(PC 실행·테스트 확인)
2. Classifier(프로필 배정·주제 합치기) + 선택(임계값·상한) + 댓글·과거 기록 + IdeaGenerator(피드백 반영) + 인용 검증 + 비용 기록 + HtmlRenderer — 완료(P1 LLM 연결부·프롬프트 저장소, P2 파이프라인)
3. 나머지 어댑터 4개, 설정 파일 연동 — 완료(P3 HF·Reddit·PH·RSS, P4 튜닝: 수요 최소 보장·분류 검증·카드 중복 제거). 개발 기록 [docs/dev/log.md](docs/dev/log.md). Reddit·PH API는 기본 꺼짐 — 설정에서 켜고 자격 증명(키)을 넣어야 함. RSS 기본 피드는 HF Blog·Simon Willison(ai)·Latent Space(출시 피드 PH·BetaList는 약관 허용 범위 미확인으로 제외 — 시장 신호가 더 필요하면 사용자가 약관을 확인한 뒤 피드를 직접 추가하거나 PH API를 쓴다, design.md §9)
4. `app`: WorkManager, Room, Keystore, SMTP 메일
5. 앱 화면: 오늘 카드·보관함·출처/평가 기준 수정·실행 기록
6. (선택) 보충 검색, Batch API, runner + GitHub Actions
7. 기술 트렌드 리포트 + 요청형 사업성 분석([docs/business-scout-plan.md](docs/business-scout-plan.md), 시안 `mockup/trend.html`) — S1 트렌드 리포트 완료(`Main --trend`, 코드만·LLM 0원, 테마 = 정규화한 분류 태그). S2 과거 채우기 완료(`Main --backfill 4w`, 수집 0원·분류는 `--yes`일 때만, 10.23~11.18 채움 $0.081, 리뷰 이관 중간 3건도 처리). S3 테마 지정 분석 완료(`Main --analyze`, 실행 전 추정·`--yes` 승인, 금액 상한은 사용자가 `out/settings/budget.json`·`--set-budget`으로 정하고 상한에 따라 실행·맞춤·중지 — plan §7-1). 다음 S4 요청형 사업성 분석(OpenAI `gpt-6.2-sol` 추론 high, 요청할 때만·실행 전 추정 비용 확인)

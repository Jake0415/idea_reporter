# Astryx 검토 — 인스타그램풍 톤 적용 가능성

- 검토일: 2026-11-19
- 대상: [facebook/astryx](https://github.com/facebook/astryx) (Meta, MIT, 베타) — `@astryxdesign/core`·`cli`·`theme-neutral` **0.7.16**, `@stylexjs/stylex` 0.19.2
- 검토 프로젝트: [mockup/astryx-review](../../mockup/astryx-review) (Vite + React 19 + TypeScript). 실행: `cd mockup/astryx-review` → `npm install` → `npx vite --port 5180`

## 1. 확인한 것

| 항목 | 결과 |
|---|---|
| 공식 에이전트 스킬 | 있음. Claude Code 플러그인 `astryx`(SKILL.md): "`astryx build`로 템플릿부터 시작, 컴포넌트·토큰만 쓰고 임의 hex/px 금지" 규칙. 이번 검토는 플러그인을 설치하지 않고 같은 규칙을 CLI로 따랐다 |
| 설치 | 정상. npm 11이 두 패키지의 postinstall 스크립트를 막았지만(실행 안 됨) 사용에 문제 없었다 |
| 컴포넌트 | 174개. 모바일에 쓸 만한 것: BottomSheet(드래그·스냅·겹침 지원), MobileNav, ClickableCard, Card, Token, Badge, Banner, Toast, Switch, ProgressBar, Avatar, Carousel |
| 페이지 템플릿 | 58개, **대부분 데스크톱 업무 화면**(대시보드·표·IDE·설정·로그인). 우리 "오늘의 카드" 피드를 물으니 가장 가까운 것으로 `library`(카드 그리드)를 제안 → **모바일 피드·소셜형 템플릿은 없음** |
| 제공 테마 | 7개(neutral, butter, chocolate, matcha, stone, gothic, y2k). **인스타그램 테마 없음** |
| 테마 편집기(실험 기능) | 동작함. 색·모서리·글꼴을 "다이얼"로 바꾸면 실제 컴포넌트가 읽는 토큰인지 검사하고 반영. 잘못된 값은 거부(예: vibrancy `low` → 0~100 숫자만) |
| 테마 PNG 미리보기 | **실패**: 이 PC의 Node 26에서 편집기 렌더러가 `.css`를 읽지 못함. 대신 Vite 화면으로 확인 |
| 시안 화면 | SC-01 오늘·SC-02 카드 상세·SC-06 설정 일부를 Astryx 컴포넌트로 작성. 타입 검사 통과, 브라우저 콘솔 오류 없음 |

## 2. 만든 인스타그램풍 테마 `feed`

`mockup/astryx-review/themes/feed/feed.settings.json` (neutral 기반)

| 설정 | 값 | 의도 |
|---|---|---|
| accent | `#0095F6` | 강조색은 파란색 하나 |
| neutral | `#737373` | 회색 글자·구분선 |
| vibrancy | 30 | 채도를 낮춰 차분하게 |
| corners | small (8px) | 각지지도 둥글지도 않게 |
| font | system | 기기 기본 글꼴 |
| 배경(body·surface) | 라이트 `#FFFFFF` / 다크 `#000000` | 흰 배경, 다크 모드는 완전한 검정 |

로고·브랜드 그라데이션 등 인스타그램 상표 요소는 쓰지 않았다(톤만 참고).

## 3. 관찰

- 카드·배지·토큰·배너가 깔끔하게 맞물리고, 토큰만 바꿔도 전체 톤이 바뀐다 — "인스타그램풍"으로 가는 데 필요한 손잡이(색·모서리·글꼴·배경)는 충분하다.
- 하단 탭은 버튼으로 흉내 냈더니 알약 모양이 됐다. 인스타그램처럼 아이콘 탭으로 하려면 아이콘 세트(Icon) 연결이 필요하다.
- BottomSheet는 실제 모바일 화면 기준으로 동작해서 폰 액자 밖까지 덮었다(실기기에서는 정상). 높이는 `height="hug"`로 줄이는 것이 맞다.
- 패키지가 0.7.x 베타이고 테마 편집기는 "패치 버전에서 바뀔 수 있음"이라고 명시돼 있다.

## 4. 우리 제품에 맞는가

| 관점 | 판단 |
|---|---|
| 최종 앱(Kotlin + Jetpack Compose)에 직접 사용 | **불가.** Astryx는 React 웹 전용(네이티브는 Apple만) |
| 고품질 시안(hi-fi) 도구 | **유용.** 손으로 쓴 HTML보다 빠르고 일관된 시안을 만들 수 있다 |
| 디자인 토큰의 기준 | **유용.** `feed` 테마의 색·모서리·글꼴·배경 값을 그대로 Compose `MaterialTheme`(colorScheme·shapes·typography)로 옮길 수 있다 |
| 비용 | 시안만을 위해 React 도구 체인을 유지해야 하고, 베타라 업데이트 때 깨질 수 있다. 시안 코드는 앱에 재사용되지 않는다 |

## 5. 제안

1. **흐름 확인은 지금 와이어프레임(mockup/index.html)으로 계속**한다.
2. **톤 결정은 Astryx 시안으로** 한다: `feed` 테마로 핵심 화면 3~4개를 hi-fi로 만들어 보고 색·모서리·글꼴을 확정한다.
3. 확정한 값은 `docs/design-tokens.md`(신설 예정)에 적고, 4~5단계에서 Compose 테마로 옮긴다. Astryx는 그 이후 필수 의존성이 아니다.

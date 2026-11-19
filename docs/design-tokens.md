# 디자인 토큰 — "feed" 톤 (초안, 확정 전)

> 인스타그램풍의 깔끔한 피드 톤. 출처: Astryx 테마 편집기로 만든 `feed` 테마(neutral 기반) — `mockup/astryx-review/themes/feed/feed.settings.json`, 빌드 결과 `src/themes/feed/feed.css`.
> 시안: `cd mockup/astryx-review` → `npx vite --port 5180` (SC-01 오늘·SC-02 상세·SC-03 보관함·SC-11 AI 공급자, 라이트/다크 전환).
> 사용자 검토 후 확정하면 4~5단계에서 Jetpack Compose `MaterialTheme`로 옮긴다. 인스타그램 로고·브랜드 그라데이션 등 상표 요소는 쓰지 않는다.

## 디자인 샘플 (비교용)

시안(http://localhost:5180)에서 테마 4종 × 카드 레이아웃 3종 × 라이트/다크를 바꿔 보거나, "테마 4종 비교"로 오늘 화면을 나란히 볼 수 있다. 아래 표는 고르기 위한 요약이고, 이 문서의 나머지 값은 A 기준이다.

| 테마 | 바탕(라이트/다크) | 포인트 | 모서리 | 제목 글꼴 | 느낌 |
|---|---|---|---|---|---|
| **A 피드** | `#FFFFFF` / `#000000` | 파랑 `#0095F6` | small 8px | 기본 | 인스타그램풍, 깔끔·가벼움 |
| **B 모노** | `#FFFFFF` / `#0A0A0A` | 검정 `#111111` | square 0px | 세리프 | 신문·에디토리얼, 진지함 |
| **C 소프트** | `#F2F3F5` / `#111214` (카드 흰색 / `#1C1D21`) | 인디고 `#5B5BD6` | round 16px | 기본 | 금융앱풍, 카드가 떠 보임 |
| **D 선셋** | `#FFF8F3` / `#16100D` (카드 흰색 / `#211915`) | 코럴 `#F2552C` | large 12px | 기본 | 따뜻한 소비자앱 |

| 카드 레이아웃(오늘 화면) | 내용 | 맞는 경우 |
|---|---|---|
| **1 피드 카드** | 카드마다 제목·한 줄 설명·분류·배지 | 기본값, 훑어보기와 판단의 균형 |
| **2 촘촘한 목록** | 한 화면에 전부, 설명 생략, 번호·분류·★ | 빨리 훑고 관심 있는 것만 열 때 |
| **3 한 장씩** | 큰 카드 1장 + 이전/다음, 이유·문제까지 미리 보기 | 하루 5개를 하나씩 집중해서 읽을 때 |

테마는 각각 Astryx 테마 편집기 설정으로 남아 있다: `mockup/astryx-review/themes/{feed,mono,soft,sunset}/*.settings.json`.

## 색 (라이트 / 다크) — A 피드 기준

| 토큰 | 라이트 | 다크 | 쓰임 | Compose 대응(예정) |
|---|---|---|---|---|
| accent | `#0095F6` | `#0095F6` | 주요 버튼, 선택된 ★, 반복 주제 고리 | `primary` |
| accent-muted | `#B2D9FF` | `#00497D` | 강조 배경 | `primaryContainer` |
| background-body | `#FFFFFF` | `#000000` | 화면 바탕 | `background` |
| background-surface | `#FFFFFF` | `#000000` | 카드 | `surface` |
| background-muted | `#F1F1F1` | `#1B1B1B` | 깔때기·근거 상자 | `surfaceVariant` |
| text-primary | `#000000` | `#FFFFFF` | 본문·제목 | `onBackground`, `onSurface` |
| text-secondary | `#474747` | `#9D9D9D` | 보조 글(출처·시각·설명) | `onSurfaceVariant` |
| border | `#000000` 8% | `#FFFFFF` 10% | 구분선·카드 테두리 | `outlineVariant` |
| 상태(경고·성공·정보·오류) | Astryx neutral 기본값 | 〃 | 배너·배지 | 확정 시 값 추출 |

기준 다이얼: accent `#0095F6`, neutral `#737373`, vibrancy 30(차분한 채도).

## 모서리

| 토큰 | 값 | 쓰임 |
|---|---|---|
| radius-inner | 5px | 배지·작은 칩 안쪽 |
| radius-element | 8px | 버튼·입력·토큰 |
| radius-container | 10px | 카드·배너 |
| radius-page | 22px | 하단 시트 위쪽 |
| radius-full | 원형 | 반복 주제 아이콘, 아바타 |

## 글꼴

- 본문·제목: 기기 기본 글꼴(`system-ui` → 안드로이드는 Roboto / 한글은 기본 고딕). 별도 웹폰트 없음.
- 크기·굵기는 Astryx neutral 기본 스케일을 따른다. Compose에서는 `Typography`의 `titleLarge`(화면 제목)·`titleMedium`(카드 제목)·`bodyMedium`(본문)·`bodySmall`(보조)로 대응한다.

## 화면 패턴 (시안에서 정한 것)

| 패턴 | 내용 |
|---|---|
| 하단 탭 | 아이콘만(홈·보관함·설정), 선택된 탭은 **채운 아이콘**, 글자 없음 |
| 반복 주제 줄 | 오늘 화면 맨 위, 원형 아이콘 가로 줄. 새 주제는 accent 고리, 본 주제는 회색 고리 → 누르면 SC-04 |
| 카드 | 흰 바탕 + 얇은 테두리, 그림자 없음. 제목(굵게) → 한 줄 설명(보조색) → 분류 토큰·배지 |
| ★ | 빈 별 / 채운 accent 별 |
| 상단 바 | 왼쪽 큰 제목, 오른쪽 아이콘 버튼(지금 실행, ★) |
| 하단 시트 | 내용 높이만큼(`hug`), 위쪽 22px 둥근 모서리 |
| 다크 모드 | 완전한 검정 바탕, 흰 글자, accent는 그대로 |

## 확정이 필요한 것

- [ ] accent `#0095F6`(파랑) 그대로 갈지
- [ ] 카드 테두리만 vs 아주 옅은 그림자
- [ ] 반복 주제 "스토리 줄"을 MVP 오늘 화면에 넣을지(SC-04는 원래 L)
- [ ] 하단 탭에 글자 라벨을 숨길지(접근성: 스크린리더 라벨은 유지)
- [ ] 상태 색(경고·성공·오류) 값

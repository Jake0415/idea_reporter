# Horizon 관련 설계 주장 검증 (REA)

- 검증일: 2026-11-19
- 대상 문서: [docs/menu.md](../menu.md) §1·§5·§6, [docs/design.md](../design.md) §3
- 판정: **확인됨**(증거와 일치) / **다름**(증거와 어긋남, 수정 필요) / **근거 없음**(Horizon에서 찾지 못함 — 우리 고유로 표기해야 함) / **확인 불가**

## 0. 방법과 증거 위치

| 구분 | 방법 | 증거 |
|---|---|---|
| 데모 페이지 | 임시 프로필 Chrome(CDP 9333)을 띄우고 REA `list_browser_targets` → `inspect_web_page`(접근성 텍스트 포함, 수동 관찰·JS 실행 없음) → `capture_web_screenshot` | 페이지 `https://thysrael.github.io/Horizon/2026/11/19/summary-zh.html` · 접근성/DOM Evidence `ev_8bdc3148…8c78`(노드 994개) · 화면 캡처 Evidence `ev_f8a365c4…0923`(1264×805, PNG sha256 `16842b9d…a01d`) |
| 저장소 | GitHub API·raw로 직접 읽음(REA 대상 아님). 커밋 `12d5fbde4d9e0776a48618ea65d16fe6d1e1b606` 고정 | 아래 표의 `파일:줄`은 이 커밋 기준 |
| 보조 | `summary-en.html`·`feed-en.xml`·`assets/js/horizon.js` HTTP 조회 | 아래 해당 행 |

추출본(스크래치, 신뢰하지 않는 데이터로 취급): `C:\Users\ysh\AppData\Local\Temp\claude\4b19e7f48a20abbc\d09852f0-db2e-4489-a846-98210778cc7a\scratchpad\out\ax.txt`(접근성 텍스트), `…\scratchpad\out\shot-top.png`(상단 캡처), `…\scratchpad\horizon-src\`(저장소 파일 23개). 스크래치는 재부팅 시 지워질 수 있다.

데모 페이지 기본 사실(REA 접근성 트리): 제목 `Horizon Summary: 2026-11-19 (ZH)`, 항목 11개(科技新闻 8 · 科技博客 1 · 财经新闻 2), 우측 상단에 `EN`/`中文` 전환 버튼.

## 1. menu.md §1 "Horizon 데모 분석" 표

| # | 주장 (menu.md §1) | 판정 | 증거 | 수정 제안 |
|---|---|---|---|---|
| 1-0 | 서문: 데모는 중국어판, 영어판 없음(작업 지시문 기준) | **다름(부분)** | 페이지에 `EN` 버튼이 있음(접근성 트리). 11-19 `summary-en.html`은 HTTP 404. `feed-en.xml` 최신 항목은 `2026/07/30/summary-en.html` → **영어판은 7월 말까지 있었고 최근 날짜는 중국어만** | 서문을 "최근 브리핑은 중국어만 공개(영어판은 2026-07-30까지)"로 고치고, "REA 미사용" 문장은 이 보고서 링크로 대체 |
| 1-1 | 상단 깔때기: "43건 중 11건을 골랐다" 한 줄 | **확인됨** | 접근성 텍스트 `从 43 条内容中筛选出 11 条重要资讯。` · 캡처 상단 인용 블록 · 코드 `src/ai/summarizer.py:78`, `:237`(`> {selected_items}` 형식) | 그대로. 단 Horizon은 **2단계(전체→선택)**뿐이다. 우리 3단계(수집→후보→카드)는 확장임을 명시 |
| 1-2 | 목차: 프로필별 묶음, 제목 + 점수 배지 | **확인됨** | 캡처에서 `科技新闻` 아래 번호 목록 각 줄 끝에 `8.0`/`7.0` 주황 배지. 묶음 = 프로필 `display_names`(`profiles/tech-news/profile.json:4-6`), "Items are grouped by Profile … H2 sections"(`docs/profiles.md:399-405`). 배지는 Markdown `⭐️ N/10`을 `assets/js/horizon.js`가 9/7/5 구간 색 배지로 바꿈 | 그대로. 배지 색 구간(9↑·7↑·5↑)도 참고 가능 |
| 1-3 | 제목: 원문 제목이 아니라 사실을 요약한 문장 | **확인됨** | 예: `NGINX 五个漏洞可链式取得主机 root`. 프롬프트 "short, accurate title of no more than 15 words … Preserve the product or project name and the distinguishing change"(`profiles/tech-news/enrichment.md:20`) | 그대로. "15단어 이하, 제품명 유지" 규칙을 우리 작성 프롬프트에도 넣을 것 |
| 1-4 | 첫 블록: 제목 바로 아래 요약 문단(블록 제목 없음) | **확인됨** | 각 항목 제목 다음 문단에 머리글 없음(접근성 텍스트). `primary: true` 블록은 "directly below the item title without a block heading"(`docs/profiles.md:189`, `:394-396`) | 그대로 |
| 1-5 | 출처 줄: 출처 · 작성자 · 시각 · "커뮤니티 토론" 링크 | **확인됨(조건부)** | `hackernews · speedping · 11月18日 15:19 · 社区讨论`. 링크는 `discussion_url`이 원문 URL과 다를 때만 붙음(`src/ai/summarizer.py:413-417`) → telegram·rss 항목에는 없음(`telegram · zaihuapd · 11月18日 03:55`). 또한 출처 줄은 요약 블록 **다음**에 옴 | "토론 링크는 토론 페이지가 따로 있는 출처(HN·Reddit)만"이라고 명시 |
| 1-6 | 배경 블록: 과거 브리핑을 날짜로 인용 | **확인됨 — 단 별도 블록 아님** | `Horizon 10 月 29 日日报曾报道…`, `10 月 6 日…10 月 23 日的日报…`(인용 2개). 과거 인용은 `background` 블록 **안에서** `history_search` 도구로 함(`profile.json:19-21`). 규칙: 최대 2건, 직접 연결만, 날짜는 "브리핑 날짜"로 표기(`enrichment.md:14-16`). 일부 항목은 블록 제목이 `此前进展`으로 바뀜 | 우리 "과거 언급" 별도 블록은 **변형**으로 표기. "직접 연결(이전 버전·같은 사건의 앞 단계)만, 회사·분야만 같으면 버림" 규칙을 차용 |
| 1-7 | 영향 블록: "누구에게 무슨 영향" 실무 관점 | **확인됨** | `「影响与处置」`, `「对使用者的影响」` 등. 프롬프트 "one concrete, evidence-supported consequence … Omit the block if it only predicts a broad industry trend"(`enrichment.md:9`), `optional: true`(`profile.json:24-28`) | 우리 "실행 판단"은 변형이 맞음. "막연한 업계 전망이면 생략" 규칙은 그대로 가져올 만함 |
| 1-8 | 커뮤니티 블록: 댓글 작성자를 이름으로 인용, 찬반 함께 | **확인됨** | `black3r 质疑…fduran 则指出…aftbit 提到…`, `trentnix 认为…bemtevi 则主张…`. 프롬프트 "Attribute opinions … Engagement counts are not evidence of consensus. Omit the block when comments provide no substantive discussion"(`enrichment.md:10`). 이름은 수집 단계에서 `[commenter]: text`로 본문에 붙임(`src/scrapers/hackernews.py:100-108`) | 그대로. "추천 수는 합의의 근거가 아님" 문구 차용 |
| 1-9 | 불확실성 표시: "커뮤니티 주장", "업체 주장" 명시 | **확인됨 — 단 별도 표시가 아니라 본문 문장** | `这些均为社区说法，未经公告材料确认`, `属于厂商声明，并非独立测评结果`, `尚无独立验证`. 별도 필드·배지 없이 프롬프트 규칙("Distinguish an announced plan or vendor claim from a shipped capability", `enrichment.md:7`)으로 본문에 녹임 | 우리 `caveats` 별도 블록은 **변형**으로 표기. 본문 안 표기 규칙도 함께 작성 프롬프트에 넣을 것 |
| 1-10 | 태그: 항목 끝에 3~5개 | **확인됨** | 11개 항목 모두 `标签: #…` 5개. 분석 프롬프트 "Use three to five specific topic tags"(`profiles/tech-news/analysis.md:17`), 렌더 `src/ai/summarizer.py:458-461` | 그대로. 태그는 **1차 분석 단계**에서 만든다는 점을 design.md 분류 출력에 반영 |
| 1-11 | 묶음: 기술 뉴스·기술 블로그·금융 뉴스가 한 페이지에 섞임 | **확인됨** | H2 `科技新闻`·`科技博客`·`财经新闻`. 저장소 내장 프로필 4종(tech-news, tech-blog, finance-news, ai-creator — `docs/profiles.md:19-24`) | 그대로 |
| 1-12 | 한계: 기사 1건 = 항목 1개, 피드백·보관함 필터 없음 | **대체로 확인됨(보완 필요)** | 주제 중복 제거 시 중복 항목의 본문·댓글을 대표 항목에 `--- From <출처> ---`로 **합쳐 넣음**(`src/orchestrator.py:753-768`) → 여러 출처가 한 항목에 들어갈 수 있음. README에 피드백 기능 언급 없음. "그래서 무엇을 만들까" 단계 없음 | "1건=1항목"을 "같은 사건만 합침(여러 신호를 엮는 종합은 없음)"으로 정확히 |
| 1-13 | (표에 없음) 참고 링크 | **누락** | `参考链接`는 접힌 `<details>` 목록(`src/ai/summarizer.py:446-455`), 도구 검색 결과를 인용했을 때만 나옴(curl·Evals·연준 항목엔 없음) | §1 표에 행 추가, §5 근거 링크 블록 근거로 사용 |
| 1-14 | (표에 없음) 블록 제목이 항목마다 다름 | **누락** | `「影响与处置」`·`「选型与部署影响」`·`「此前进展」` 등. 블록 id는 고정이지만 제목은 LLM이 쓰는 "short localized heading"(`src/ai/prompting/enrichment.py:99`), 렌더 `**「{block_title}」**`(`summarizer.py:440`) | 우리는 앱 UI라 **고정 제목**이 낫다. "Horizon은 가변, 우리는 고정"으로 명시 |

## 2. design.md §3 "Horizon 설계 차용" 표

| # | 행 | Horizon 원형 주장 | 판정 | 증거 | 수정 제안 |
|---|---|---|---|---|---|
| 2-1 | 프로필 | 프로필마다 `match.md`·`analysis.md`·`enrichment.md` + `profile.json` | **확인됨** | `docs/profiles.md:30-43`, 저장소 `profiles/<id>/` 4파일 × 4종. 분석은 0~10 점수·이유·한 줄 요약·태그(`docs/profiles.md:240-244`) | 그대로. "출처마다 프로필 지정(고정/후보 배열/auto)" 라우팅 규칙(`docs/profiles.md:194-236`)도 우리 "명시/후보" 배정과 일치 — 근거로 링크 |
| 2-2 | 프로필별 임계값 | `profile_settings.threshold` | **확인됨** | `docs/profiles.md:248-266`(0~10, 이상이면 통과, null이면 생략), 예시 tech-news 7.0 / tech-blog 4.0 | 그대로 |
| 2-3 | 주제 중복 제거 | 프로필 안에서 LLM으로 같은 주제 합침 | **확인됨** | `topic_dedup`(기본 true, `docs/profiles.md:357-380`), 임계값 통과 후 프로필별로 **별도 LLM 호출**(`src/orchestrator.py:661-704`, `:807-836`), 기준 "exact same real-world event … Err on the side of keeping items separate"(`src/ai/prompting/deduplication.py:3-8`). 점수 높은 쪽이 대표, 중복의 본문·댓글은 대표에 합침 | 우리 "분류 호출 안에서 `topicKey`"는 변형이 맞음. ① "같은 제품의 다른 사건은 다른 주제" 기준 차용 ② 합칠 때 **중복 항목의 근거(URL·댓글)를 버리지 말고 대표에 붙인다** 추가 |
| 2-4 | 분류별 상한 | `digest.category_groups`, `max_items` | **확인됨** | `docs/configuration.md:644-738`: 그룹별 `limit`, 그룹 안은 점수순, `max_items`는 최종 상한, `group_by: "category"|"profile"`, `default_group_limit`. 실행 시점: 임계값·주제 합치기 **뒤, 작성(enrichment) 전** → 비싼 호출 절약 | 그대로. "작성 전에 상한 적용해 비용 절약" 순서를 design.md 흐름에 명시 |
| 2-5 | 댓글 수집 | HN·Reddit 상위 댓글 N개 | **확인됨 — 수집 시점은 다름** | HN `TOP_COMMENTS_LIMIT = 5`, `kids` 앞 5개(`src/scrapers/hackernews.py:14`, `:64`), 댓글 497자 자름(`:105-107`). Reddit `fetch_comments`(예시 5, `docs/configuration.md:461`). **모든 수집 항목**에 대해 수집 단계에서 가져옴. Reddit은 API 키 없이 `old.reddit.com` HTML 우선(`docs/configuration.md:454`) | 우리 "최종 후보만"은 변형(비용 절약)으로 표기. Reddit HTML 스크레이핑 방식은 **따라 하지 않음**(우리 원칙: 공식 API 우선) 한 줄 추가 |
| 2-6 | 과거 기록 검색 | 지난 브리핑 BM25 검색(`history_search`) | **확인됨** | `docs/profiles.md:293-336`: `summaries/horizon-YYYY-MM-DD*.md`의 제목·요약·태그를 BM25(제목 가중), 항목당 1회, 후보 최대 3건(요약 500자), 기본 90일(1~365), 동의어·다른 언어 매칭 불가. 구현 `src/processing/history.py` | 그대로. design.md:220 "Horizon과 같은 한계" 문장도 확인됨. **검색 대상은 과거 결과물(브리핑)이지 수집 원본 전체가 아님** — 우리는 항목+카드 둘 다 검색하므로 확장임을 명시 |
| 2-7 | 인용 검증 | 도구가 돌려준 출처만 인용 허용 | **확인됨** | 블록의 `source_refs`가 그 블록의 도구 결과 ID에 없으면 예외(`src/ai/enricher.py:489-500`), "Horizon rejects references that were not returned by a tool call"(`docs/profiles.md:290-291`) | 우리 "이번 실행 수집 URL만"은 같은 원리의 **변형**. Horizon처럼 **블록 단위**로 검증(근거가 그 블록에서 쓴 신호에 속하는지)하면 더 엄격 — 선택 사항으로 제안 |
| 2-8 | 본문 길이 예산 | `analysis_max_chars`, `sampling` | **확인됨** | `content.analysis_max_chars`·`enrichment_max_chars`(500~100000), `sampling: "prefix" | "head-middle-tail"`(`docs/profiles.md:338-355`), 구현 `src/processing/content.py` | 그대로. 작성 단계 한도 이름(`enrichment_max_chars`)도 함께 적기 |
| 2-9 | 출처 품질 지표 | HorizonHub 구상: 평균 점수, 통과율 | **확인됨(구상 문서)** | `docs/horizon-hub-design.md:84-95`: AI Avg Score, SNR(=통과 비율), Avg Daily Output, 30일 추세. **서버 텔레메트리 구상이며 구현 코드 없음** | 그대로. "구상(미구현)"임을 표에 명시. "하루 평균 수집량" 지표가 우리 SC-07과도 일치 |
| 2-10 | 범용 RSS | RSS/Atom 스크레이퍼 | **확인됨** | `src/scrapers/rss.py`, 출처별 `profile`·`content_extractor`(trafilatura로 본문 추출, 실패 시 피드 요약, `docs/profiles.md:47-61`). 별개로 결과물 RSS 피드도 냄(`docs/feed-zh.xml`, `feed-en.xml`) | 그대로. CLAUDE.md "RSS"가 입력(어댑터)인지 출력(피드)인지 모호 → "RSS 입력 어댑터"로 명확히 |
| 2-11 | "우리만의 것" 목록 | LLM 전 규칙 필터, 아이디어 종합, 피드백, 비용 상한, 안드로이드, 한국어 | **대체로 확인됨** | 저장소에서 피드백·일일 비용 상한·아이디어 종합 단계를 찾지 못함. 단 Horizon도 **LLM 전에 코드로 거름**: URL 정규화 중복 제거(`src/orchestrator.py:59-60`, `:608-622`), 수집 기간 `time_window_hours`, GitHub `max_items` 등 | "LLM 전 규칙 필터"는 "출처별 점수·키워드 규칙 필터"처럼 범위를 좁혀 쓰기(URL 중복 제거는 Horizon에도 있음) |

## 3. menu.md §5 카드 상세 블록, §6 메일 리포트

| # | 주장 | 판정 | 증거 | 수정 제안 |
|---|---|---|---|---|
| 3-1 | §5 "Horizon의 '첫 블록 + 선택 블록' 방식" | **확인됨** | 블록 계약: `primary`(제목 없이 첫 줄, 최대 1개) + 필수(기본) + `optional`(내용 없으면 생략)(`docs/profiles.md:179-189`, `:273-277`). 필수 블록 누락·모르는 블록·중복 블록은 거부(`src/ai/enricher.py:480-505`) | 그대로. "필수 블록 누락 시 카드 거부" 검증도 차용 대상으로 명시 |
| 3-2 | §5 0번 머리: 한 줄 설명(블록 제목 없이) | **확인됨** | 1-4와 같음 | — |
| 3-3 | §5 4번 수요 신호: 댓글 인용(작성자·찬반) | **확인됨(원형)** | 1-8과 같음 | — |
| 3-4 | §5 6번 과거 언급: 날짜 인용 최대 2개 | **확인됨(변형)** | Horizon도 최대 2건(`enrichment.md:14`). 단 별도 블록이 아니라 배경 블록 안 | 1-6 제안과 같음 |
| 3-5 | §5 8번 확인 필요(`caveats`) | **변형(근거 일부)** | Horizon은 본문 문장으로만 표시(1-9) | "Horizon은 본문 표기, 우리는 별도 블록"으로 명시 |
| 3-6 | §5 9번 근거 링크: 인용 검증 통과분만 | **확인됨(변형)** | Horizon `参考链接`도 검증된 도구 결과만, 접힌 목록(1-13, 2-7) | 앱에서도 접기 기본값 고려 |
| 3-7 | §5 3번 기술 신호의 "라이선스", 7번 실행 판단, 1번 오늘 고른 이유, 규칙 "기술 1 + 수요/경쟁 1 없으면 카드 안 만듦" | **근거 없음(우리 고유)** | Horizon에 해당 블록·규칙 없음 | 우리 고유로 표기(문제 없음) |
| 3-8 | §6 머리의 깔때기 한 줄 | **확인됨(확장)** | Horizon 메일 본문 = 브리핑 Markdown 전체이므로 깔때기 한 줄 포함(`src/services/email.py:231-242`) | 3단계는 우리 확장 |
| 3-9 | §6 목차(번호·제목·분류·배지) | **확인됨(부분)** | 브리핑 목차가 메일에 그대로 들어감. 단 메일에서는 `⭐️ 8.0/10` **글자** 그대로(배지 변환 JS는 웹페이지 전용) | 메일 배지는 HTML 인라인 스타일로 직접 그려야 함 |
| 3-10 | §6 "메일은 요약본, 판단은 앱" | **다름(Horizon과 반대 — 문제 아님)** | Horizon 메일은 **전체 브리핑**(블록 전부)을 Markdown→HTML로 보냄. 제목 `Horizon Summary (ZH) - {날짜}`(`src/orchestrator.py:387`), 구독/해지는 답장 키워드(`email.py:98-170`) | Horizon 차용이 아니라 **우리 결정**임을 명시 |
| 3-11 | §6 "HTML + 일반 텍스트 둘 다" | **확인됨** | `MIMEMultipart("alternative")`에 plain + html(`src/services/email.py:275-286`) | 그대로 |
| 3-12 | §6 제목의 카드 수·깔때기, 꼬리의 비용·실행 시간 | **근거 없음(우리 고유)** | Horizon 메일 제목·꼬리에 없음(꼬리는 발신자·해지 안내) | 우리 고유로 표기 |

## 4. 설계 문서 수정 목록 (우선순위순)

1. **[menu.md §1 서문]** "REA 미사용" 문장을 이 보고서 링크로 바꾸고, "최근 브리핑은 중국어만(영어판은 2026-07-30까지)"으로 고친다. (1-0)
2. **[menu.md §1 표 · §5]** "차용"이라 적었지만 실제로는 형태가 다른 3곳을 **변형**으로 고친다: 과거 언급(Horizon은 배경 블록 안), 확인 필요(Horizon은 본문 문장), 블록 제목(Horizon은 LLM 가변, 우리는 고정). (1-6, 1-9, 1-14, 3-4, 3-5)
3. **[menu.md §6]** "요약본 메일"과 제목·꼬리 구성은 Horizon 차용이 아니라 우리 결정으로 명시하고, 메일 배지는 JS가 없으므로 인라인 HTML로 그린다고 적는다. (3-9, 3-10, 3-12)
4. **[design.md §3 주제 중복 제거]** Horizon 기준("같은 제품의 다른 사건은 별개, 애매하면 분리")과 "중복 항목의 근거 URL·댓글을 대표에 합친다"를 우리 적용 칸에 추가한다. 카드 근거가 사라지지 않게 하려는 것. (2-3, 1-12)
5. **[design.md §3 댓글 수집]** Horizon은 모든 수집 항목의 댓글을 수집 단계에서 가져오고 Reddit은 HTML 스크레이핑임을 적고, 우리는 최종 후보만 + 공식 API(변형)임을 분명히 한다. (2-5)
6. **[design.md 흐름]** 분류별 상한을 "작성(Opus) 호출 전"에 적용해 비용을 줄이는 순서를 명시한다. (2-4)
7. **[design.md 분류 출력 타입]** 태그 3~5개를 1차 분류(Haiku) 출력에 넣는다(Horizon은 분석 단계에서 태그 생성). (1-10)
8. **[menu.md §1 표]** 누락 행 추가: 참고 링크(접힌 목록, 검증된 출처만), 출처 줄 토론 링크는 토론 페이지가 있는 출처만. (1-5, 1-13)
9. **[design.md §3]** 작은 표기 정리: HorizonHub는 "구상(미구현)", RSS는 "입력 어댑터", 과거 기록 검색은 "Horizon은 과거 브리핑만 검색, 우리는 항목+카드", "LLM 전 규칙 필터"는 Horizon에도 있는 URL 중복 제거와 구분. (2-6, 2-9, 2-10, 2-11)
10. **[작성 프롬프트 — 3단계 이후]** 차용할 문구: 제목 15단어 이하·제품명 유지, 영향은 근거 있는 구체적 결과 1개(막연한 전망이면 생략), 추천 수는 합의 근거가 아님, 과거 인용은 직접 연결만. 프롬프트 문장을 그대로 옮기면 `THIRD_PARTY_NOTICES.md`에 MIT 고지. (1-3, 1-7, 1-8, 1-6)

## 5. 이번 검증의 한계

- 데모는 2026-11-19 중국어판 한 페이지만 REA로 확인했다. 다른 날짜·영어판은 HTTP 조회로 존재 여부만 확인.
- 화면 캡처는 첫 화면(목차 상단)만. 항목 본문은 접근성 트리 텍스트로 확인했다(REA 캡처는 스크롤하지 않음).
- 저장소 코드는 실행하지 않고 읽기만 했다. 수치(댓글 5개, 후보 3건 등)는 위 커밋 기준이며 바뀔 수 있다.
- `reverse-engineer-anything` 스킬은 이 세션 스킬 목록에 없어 불러오지 못했다. `rea` MCP 도구와 설치된 스킬 참고 문서(`references/runtime-observation.md`)만 사용했다.

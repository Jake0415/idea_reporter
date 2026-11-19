# P2 수정 지시 — 1회차 (Claude·OpenAI 합의)

두 리뷰어 모두 "수정 필요". 합의된 항목만이다. 규칙 동일: 커밋 금지, docs 수정 금지, 키 출력 금지, 프롬프트 파일 내용 변경 금지.

## A. 공통 지적
1. **카드 근거 유형 검증과 사전 차단 정리** (IdeaGenerator.kt:26-30·90·95-102, FullPipeline.kt:124) — 결정된 규칙:
   - `techSignals`는 **tech-signal** 항목만, `demandSignals`는 **demand-signal** 항목만, `competitors`는 **market-signal 또는 tech-signal** 항목(같은 일을 하는 기존 오픈소스도 경쟁 제품으로 인정).
   - 카드 조건: 유효 인용 기준 기술 1개 이상 + (수요 1개 이상 또는 경쟁 1개 이상). 같은 항목을 기술과 수요/경쟁에 **동시에** 쓰면 두 번째 이후는 무효.
   - 인용 허용 범위: **작성 모델에 보낸 후보와 그 mergedUrls만**(탈락 항목·추적 전용 항목 제외). 키는 `(itemId, url)` 쌍.
   - 사전 차단은 "후보 중 tech-signal 1개 이상 + 후보 2개 이상"으로 완화(그 외에는 모델이 판단, 검증이 거름).
2. **배지 계산 범위** (BadgeCalculator.kt:12·32-35, Selector.kt:25): 검증된 인용의 `(itemId, url)`을 실제 원본 항목에 연결해 그 항목들의 출처·지표만으로 계산(병합 그룹 전체의 alsoSeenIn 금지). GitHub 급상승은 **20시간 이상 이전** 스냅숏만 비교, 없으면 생성 후 하루 평균 규칙.

## B. 한쪽 지적, 확인 결과 채택
3. **일일 비용 원장** (Main.kt:74·116): `out/cost-ledger/<날짜>.json`에 호출 전 예약액(최대 출력 기준 추정) 기록 → 응답 후 실제 비용으로 정산. 정산되지 않은 예약액도 당일 예산에 포함. 실행 시작 시 원장으로 당일 사용액 복원.
4. **이전 날짜에 본 글 제외 순서** (Pipeline.kt:88): 일반 수집분은 **중복 제거 전에** 이전 날짜 열람 항목을 제외. 추적 근거 경로는 이전 글 허용 유지.
5. **추적 업데이트의 재인용** (Tracking.kt:81): 이전 업데이트에 쓴 항목은 기본 제외하되, 원점수 50% 이상 증가 또는 댓글 수 증가 시 다시 포함. 이전 업데이트 요약은 항상 함께 보냄.
6. **ProfileLoader의 프롬프트 직접 읽기 제거** (ProfileLoader.kt:30, Main.kt:70): 프로필 로더는 JSON만, match/analysis는 `PromptResolver`로 로딩·검증.
7. **분류 출력 상한·잘림 처리** (Classifier.kt:53-54, StructuredCall.kt:45): 항목당 약 400 토큰 + 여유. `truncated`면 같은 요청을 반복하지 말고 **묶음을 반으로 나눠** 재요청(최대 2단계). 
8. **비용 사전 추정** (LlmRouter.kt:370-380): 바이트 수 대신 토큰 근사(ASCII 문자/4 + 그 외 문자/1.5, 올림).
9. **추적 전용 검색 제한** (FullPipeline.kt:48-68, Tracking.kt:41-49): 아이디어×출처당 최대 10건, `SpamClusterFilter`·정규화 URL 중복 제거 적용, 추적 근거로 이미 쓴 항목 키 기억(재분류 방지), GitHub 추적 검색은 순차 실행(동시 1).
10. **카드 작성 재시도** (FullPipeline.kt:131-134): `truncated` 재시도는 출력 상한을 1.5배로 늘리거나 compact 요청으로, 단계 기록은 하나로 합침.
11. **실패 메시지** (Main.kt:122-130·209): 비밀값이 없는 예외 클래스 이름과 사유 코드 출력, 출처 설정 검증 목록 출력 복원.
12. **같은 날 재실행 결과 교체 조건** (Main.kt:241-256): 새 실행이 카드 0개(또는 실패)이고 기존 결과에 카드가 있으면 **교체하지 않고** runs/에만 보관, 메시지로 알림.

## C. 품질 개선 (실제 실행에서 드러남)
13. **분류 전 HN 정보 보강**: HN 항목은 분류 전에 Algolia `items/{id}`로 `story_text`(있으면)와 상위 댓글 3개(각 300자)를 snippet에 덧붙인다(HN 항목 최대 40건, 동시 4). 분류가 "제목만으로 판단 불가"에 그치지 않게.

## D. 테스트 추가
- FullPipeline 통합(가짜 공급자): 같은 날 재실행 교체·보관, 0카드 재실행 시 교체 안 함, 추적 전용 키 미기록, 분류 실패·예산 초과 제외, 실행 잠금 충돌·해제.
- 근거 유형 검증(기술/수요/경쟁 규칙, 중복 사용 무효, 범위 밖 인용 무효), 배지 범위.
- TrackingUpdater(관련 없으면 호출 안 함, itemKey 검증, 재인용 규칙), TrackingSearch(0.3 계수, trackHint, 상한·중복 제거).
- Selector topicKey 합치기(mergedUrls, SAME_TOPIC), Classifier(후보 밖 프로필·잘못된 topicKey → CLASSIFY_FAILED, 잘림 시 분할), FileHistorySearch, Enricher.sample(head-middle-tail), 비용 원장, 토큰 근사.

## 완료 기준
- `:core:test --rerun-tasks` 통과. 실제 전체 실행 1회($0.50 상한) — 카드 수·비용·잘림 여부를 표로. 1~13 처리 결과 한 줄씩.

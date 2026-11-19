# P1 수정 지시 — 2회차 (Claude·OpenAI 합의)

1회차 13건은 두 리뷰어 모두 "반영됨"으로 확인. 아래만 고친다. 규칙은 그대로(커밋 금지, 키 출력 금지, docs 수정 금지).

1. **[공통·높음] 날짜 붙은 스냅샷 모델 이름 가격 매칭** (ModelCatalog.kt:31-32·44-46)
   - 정확히 일치 → 없으면 날짜 접미사(`-\d{4}-\d{2}-\d{2}$`, `-\d{8}$`)를 뗀 이름으로 같은 공급자에서 다시 찾기. **단순 앞부분(prefix) 매칭 금지**(`gpt-5.4-mini-…`가 `gpt-5.4`에 붙지 않게).
   - 실제 응답 모델 이름은 그대로 기록.
   - 테스트: 응답 `gpt-5.4-mini-2026-03-17` → `gpt-5.4-mini` 요율(예: 입력 52·출력 25 → 정확한 값), 미등록 모델은 최고가 추정.
2. **[OpenAI·중간] 거절 + 예산 초과 시 거절 결과 유실** (LlmRouter.kt:54): 첫 호출이 거절이면서 예산을 넘기면 대체 호출의 사전 검사 예외로 결과가 사라짐 → 이 경우 첫 결과를 반환, 대체 0회, 다음 독립 호출은 차단. 테스트.
3. **[OpenAI·중간] 이력 JSON 손상 + 잘못된 본문** (PromptResolver.kt:16, FilePromptStore.kt:63): 이력 디코딩 실패는 "메타데이터 없음"으로 처리하고 본문 검증 → 기본값+warning. 회귀 테스트.
4. **[Claude·낮음] `priceEstimated` 의미 분리**: `priceFallback`(미등록이라 최고가 추정)과 `priceUnverified`(가격표 값 미확인)로 나눈다. 비용 표시·기록에 둘 다.
5. **[Claude·낮음] 실패 사유 코드**: `LlmServiceException`에 `reason`(truncated / invalid_json / empty / http_4xx / http_5xx / timeout)을 넣고 `LlmCall.error`에 사유 코드를 남긴다(키·본문 금지).
6. **[Claude·낮음] 긴 입력 구간 캐시 읽기 단가**: `LongContextPrice`에 선택 필드 `cachedInput`(없으면 기본 캐시 단가).
7. **[Claude·빠진 테스트]** OpenAI `complete()`의 401 → `LlmAuthException`, 5xx → 사유 http_5xx.

참고(수정 안 함): 동시 호출 N건이 모두 사전 검사를 통과하면 예산을 조금 넘을 수 있음 — 설계상 허용, 문서에 기록.

## 완료 기준
- `:core:test` 통과, `--llm-smoke` 두 공급자 성공 — OpenAI 비용이 `gpt-5.4-mini` 요율로 나오는지 확인해 표로 보고(키 제외). 각 항목(1~7) 처리 결과 한 줄씩.

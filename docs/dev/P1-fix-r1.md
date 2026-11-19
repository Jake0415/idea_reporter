# P1 수정 지시 — 1회차 (Claude·OpenAI 합의)

리뷰 결과 두 리뷰어 판정 모두 "수정 필요". 아래는 합의된 지적만이다. 하나씩 고치고 테스트를 추가하라. 브리프(`docs/dev/P1-llm-prompt.md`)의 "하지 말 것"은 그대로 유지(커밋 금지, 키 출력 금지, docs 수정 금지).

## A. 두 리뷰어 공통 (높음·중간)
1. **가격표에 없는 모델·응답 해석 실패 시 비용이 사라지고 대체 호출이 일어남** (AnthropicProvider:89, OpenAiProvider:55, LlmRouter:59-67)
   - 요청 전에 **요청 모델**의 가격을 확인한다. 가격표에 없으면 HTTP 요청을 보내지 않고 `LlmConfigException`("가격표에 없는 모델: … — models 설정에 가격을 등록하세요")으로 실패, **대체 호출 금지**.
   - 응답의 **실제 모델**이 요청 모델과 다르고(서버 측 대체 등) 가격표에 없으면, 그 공급자 가격표의 **가장 비싼 모델 가격**으로 보수적으로 추정하고 `LlmResult.priceEstimated = true`.
   - 응답을 받은 뒤의 해석 실패(텍스트 없음, 본문 없음, JSON 아님)는 **사용량·비용을 담은** `LlmServiceException(usage, costUsd)`로 던지고, 라우터는 그 비용을 CostGuard에 기록한 뒤 대체 규칙을 적용한다.
2. **출력이 잘린 응답을 성공으로 반환** (AnthropicProvider:62-64,79-81, OpenAiProvider:49)
   - Anthropic `stop_reason == max_tokens`, OpenAI 종료 사유 `length`(또는 incomplete)는 비용을 담은 실패 예외로 던져 대체 대상이 되게 한다.

## B. 한쪽 지적이지만 확인 결과 버그·설계 위반 → 수정
3. **실행별 LLM 호출 기록**: `CostGuard`에 실행 단위 기록을 꺼내는 API(예: `beginRun()`/`drainCalls()`)를 두어 연속 두 실행의 기록이 섞이지 않게 한다. (`Pipeline`에 연결하는 것은 P2에서 함. 여기서는 API와 테스트만.)
4. **예산 초과 처리** (CostGuard:48-52): 예산을 넘긴 그 호출의 결과는 **돌려주고**, **다음 호출부터** 사전 검사로 `BudgetExceededException`. (design §11: "예산 초과 시 이후 LLM 단계 중단, 그때까지 결과만 발송".) 기존 테스트 기대값도 이에 맞게 바꾼다.
5. **잠금 범위** (CostGuard:38-46): Mutex는 날짜·예산 검사와 누적 기록에만 걸고, 공급자 호출은 잠금 밖에서. 동시 호출 테스트 추가.
6. **단계별 시간 제한** (LlmRouter:16): `classify`·`track`·`test` 60초, `generate` 600초(설정 가능).
7. **키 없는 공급자** (LlmRouter:30): `LlmAuthException` 대신 `LlmKeyMissingException`("키 미등록: OPENAI_API_KEY") — 대체 규칙은 인증 실패와 같게(재시도 없음).
8. **토큰 의미 통일**: `inputTokens`는 두 공급자 모두 **캐시 포함 전체 입력**. Anthropic은 `input + cacheRead + cacheCreation`, `cachedInputTokens = cacheRead`. 캐시 쓰기 토큰은 `cacheWriteTokens` 필드로 따로 기록하고 비용에 반영(캐시 쓰기 가격이 없으면 입력 가격의 1.25배).
9. **Haiku 5.5 긴 입력 요금**: 가격표에 선택적 `longContext { thresholdInputTokens, input, output }` 지원. haiku-5-5: 100,000 초과 시 $0.50/$2.50.
10. **FilePromptStore 저장 일관성** (FilePromptStore:17,29): 본문 파일을 먼저 임시 파일→원자적 교체로 쓰고, 그다음 이력을 갱신. `get()`은 본문과 해시가 일치하는 이력 항목의 메타데이터만 쓰고, 없으면 버전을 "unknown"으로 표시. 두 번째 교체 실패 후 재조회 테스트.
11. **설계 §7-3 필드**: `PromptOverride(id, text, version, baseHash, editedAt)`, `PromptVersion(id, version, text, savedAt)`, `versions()`는 `List<PromptVersion>`.
12. **깨진 수정본** (PromptResolver:16): 저장된 수정본이 비었거나 2만 자 초과면 실행을 멈추지 말고 기본값을 쓰고 결과에 `warning` 표시.
13. **baseHash** : 주석을 제거한 본문으로 계산(주석만 바뀐 기본값 변경은 "새로 바뀜"이 아님).

## C. 추가할 테스트
- 잘린 응답(Anthropic max_tokens, OpenAI length) → 실패 + 비용 기록 + 대체
- 가격표 없는 요청 모델 → HTTP 0건, 대체 없음 / 응답 모델이 다르고 미등록 → 보수적 가격 + `priceEstimated`
- 해석 실패 시 비용이 CostGuard에 기록됨
- `fallback=false`·단계별 대체 끔에서 대체 없음, 대체도 실패했을 때 마지막 예외 전파, 대체 공급자가 없을 때 거절 결과 그대로 반환
- Anthropic 401·5xx 변환
- Anthropic 캐시 읽기·쓰기 포함 비용 **정확한 값**
- 프롬프트 2만 자 경계(정확히 20,000자 통과, 20,001자 거부)
- 실행별 기록 분리, 예산 초과 호출 결과 반환·다음 호출 차단, 동시 호출

## 완료 기준
- `:core:test` 전부 통과. `--llm-smoke` 다시 실행해 두 공급자 성공(결과 요약, 키 제외).
- 마지막 답변에 각 항목(1~13)의 처리 결과를 한 줄씩.

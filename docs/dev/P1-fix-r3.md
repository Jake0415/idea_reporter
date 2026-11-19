# P1 수정 지시 — 3회차 (마무리)

판정: Claude "통과"(낮음 2), OpenAI "수정 필요"(중간 2). 같은 영역(프롬프트 이력 손상)을 양쪽이 지적했고 나머지도 작고 명확해서 함께 정리한다. 규칙 동일(커밋·키 출력·docs 수정 금지).

1. **[OpenAI·중간] 날짜 붙은 Opus 요청의 서버 측 대체** (AnthropicProvider.kt:45): 베타 엔드포인트·`fallbacks` 사용 여부를 **카탈로그가 매칭한 기본 모델명**으로 판단하고, API에는 원래 요청 모델명을 보낸다. 테스트: `claude-opus-5-5-20261101` 요청 → 베타 헤더·fallbacks 포함.
2. **[OpenAI·중간 + Claude·낮음] 이력 파일 손상 처리 일원화** (FilePromptStore.kt:37·51·67·71): `history()` 자체에서 JSON 오류·UTF-8 디코딩 오류(`CharacterCodingException`, `MalformedInputException`)를 잡아 손상 파일을 `<id>.versions.json.bak`으로 옮기고 빈 이력으로 계속. `get()`·`save()`·`versions()`·`reset()` 모두 손상 상태에서 막히지 않게. 회귀 테스트(깨진 UTF-8 바이트, 깨진 JSON → 저장 가능).
3. **[Claude·낮음] 인증 실패 사유 코드**: 401/403은 `LlmCall.error = "auth"`, 키 미등록은 `"key_missing"`.

완료 기준: `:core:test` 통과(이번에는 `--rerun-tasks`로 실제 재실행), 1~3 처리 결과 한 줄씩. 스모크는 생략 가능(Anthropic 경로를 바꾼 경우만 1회).

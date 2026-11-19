# 개선 수정 지시 — 1회차 (Claude·OpenAI 합의 + 실제 실행)

리뷰: Claude "통과"(낮음 2), OpenAI "수정 필요"(중간 3·낮음 3). 설정·문서·프롬프트 지적은 Claude가 직접 반영(BetaList 제외, Simon `ai` 태그 피드, Latent Space tech만, PH 10건, 문서 표현 정확화, ideas.md 카드 조합 안내).
실제 실행(RSS 켬·병렬): 수집 125, 분류 70초(이전 87건 90초), 수요 15·시장 20건 분류, 카드 2개, $0.211. **CLASSIFY_FAILED 14건 — 전부 "점수 없음/범위 밖(null)"**(HF·HN·GitHub·PH가 섞여 있음 → 특정 묶음의 응답에서 점수가 null).

규칙 동일(커밋·docs·프롬프트 수정·키 출력 금지, 실제 LLM 실행 금지).

1. **점수 null 회복** (Classifier.kt): 유효 프로필인데 점수가 null인 항목도 후보 밖 프로필과 같이 기존 1회 재요청 묶음에 넣는다(재요청에도 null이면 `CLASSIFY_FAILED` "점수 없음(null)"). `profile = "none"`이면 점수 null 허용(실패 아님). 테스트.
2. **[OpenAI 중간] 병렬 예산 경계 테스트** (ClassificationTest.kt:33): 사전 예약을 켠 실제 CostGuard로, 예산이 묶음 2개분만 남은 상황에서 병렬 실행 시 (a) 상한을 넘지 않고 (b) 차단된 묶음 항목이 `BUDGET_EXCEEDED`로 기록되고 (c) 결과 목록이 입력 순서인지 검증(어느 묶음이 차단될지는 고정하지 않아도 됨).
3. **[OpenAI 중간] 취소 정리 테스트**: 부모 취소 시 실행 중 요청이 취소되고, 대기 중인 묶음이 시작되지 않는지 검증.

## 완료 기준
- `:core:test --rerun-tasks` 통과(ideas.md 변경으로 프롬프트 해시·내용을 검사하는 테스트가 깨지면 기대값을 갱신). 1~3 한 줄씩 + 테스트 개수.

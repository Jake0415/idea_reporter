# P4 품질·비용 튜닝 + 남은 지적 (Codex 개발 지시)

규칙: git 커밋 금지, docs·프롬프트(.md) 파일 수정 금지, .env·API 키 읽기·출력 금지, HTML 스크레이핑 금지. 설정 기본값(`pipeline.default.json`, `profiles/*/profile.json`)의 수요 신호 임계값 6.0→5.0은 Claude가 이미 바꿨다(테스트가 6.0을 전제하면 맞춰 고칠 것).

1. **[P2 이월, OpenAI 중간] 분류 재요청이 비용 상한에 막히면 BUDGET_EXCEEDED로 기록**: Classifier의 누락 항목 재요청·잘림 분할 재호출이 `CostGuard` 예산 초과로 막힐 때, 해당 항목·실행 상태를 `CLASSIFY_FAILED`가 아니라 `BUDGET_EXCEEDED` 사유로 남긴다(이미 받은 분류 결과는 유지). 테스트.
2. **[P3 참고] 동시 요청 한도**: `Http.client()`의 Dispatcher `maxRequestsPerHost`를 10으로(전체 64 유지). 테스트 불필요, 컴파일·기존 테스트 통과.
3. **수요 근거 확보(품질)**: 후보 선택(Selector)에서 demand-signal 후보가 임계값을 넘는 것이 1건 이상 있으면 최소 2건(있는 만큼)은 프로필 상한·태그 상한과 별개로 보장한다(`pipeline.default.json`에 `"minDemandCandidates": 2` 추가, 0이면 끔). 테스트.
4. **실행 요약에 프로필별 분포**: 실행 끝 콘솔 요약과 결과 JSON `metrics`에 프로필별 분류 건수·임계값 통과 건수·최종 후보 건수를 넣는다(이미 있으면 확인만). 튜닝 판단용.

## 완료 기준
- `:core:test --rerun-tasks` 통과. 1~4 처리 결과 한 줄씩과 테스트 개수. 실제 LLM 실행은 하지 않는다(Claude가 직접 한다).

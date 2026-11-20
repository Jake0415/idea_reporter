# P8 S2 수정 지시 — 4회차(마지막 수정) (Claude 통과 + 노이즈 분석, OpenAI 중간 1·낮음 1 + 노이즈 제안)

규칙 동일(커밋·docs·프롬프트 수정·키 출력 금지, 실제 수집·LLM 실행 금지). 프롬프트(classify.md)는 바꾸지 않는다.

배경(실제 데이터, 백필 27일 + 실제 2일, HN·GH, none 제외): 항목 290, 서로 다른 태그 728개 중 84%가 4주 동안 1건. 이번 주 앞 주에 없던 태그 201개 중 2건 이상 8개, **3건 이상 0개** → 리포트 "신규" 9행이 거의 전부 2건짜리 노이즈.

## 1. [OpenAI 중간] 중단된 강제 갱신 + `--force --dry-run` 뒤 재개 안 됨
수집 캐시를 교체할 때 체크포인트는 무효화하되, **미완료 갱신 상태(`replacing`·`replacementId`)는 유지**한다. 이후 `--yes`가 기존 결과 파일이 있어도 그 날을 다시 분류해 확정. 테스트(결과 파일 있는 날: `--force --yes` 예산 중단 → `--force --dry-run` → `--yes` → 확정). 낮음: 같은 수집 내용으로 다시 강제 갱신 시 새 `replacementId`가 이전 결과 해시 일치를 막는 테스트.

## 2. 노이즈 줄이기 (설정 + 작은 코드)
- **신규 판정:** 앞 주 비교 건수 0 **그리고** 이번 주 비교 고유 항목 **≥ `newMinCount`(3)** **그리고** 서로 다른 날 **≥ `newMinDays`(2)**일 때만 "신규". 아니면 증가율 칸 "—"(데이터 부족 아님, 표본 적음).
- **증가율 안정화:** 앞 주 일평균 × 7 < 1(주 1건 미만)이면 숫자 증가율 대신 신규 규칙 또는 "—". 숫자 증가율은 스무딩 `(이번 주 + 1) ÷ (앞 주 일평균 × 7 + 1)`.
- **뜨는 테마·◆:** `risingCount` 2→**3**, ◆에 "이번 주 서로 다른 날 ≥ 2" 추가.
- **희소 태그는 테마에서 제외:** 4주 동안 **3건 이상 또는 3일 이상** 나온 태그만 테마(`minThemeItems: 3`, `minThemeDays: 3`, 둘 중 하나). 나머지는 집계에서 빼되 주제 예시에는 쓸 수 있음. 데이터 상태에 "희소 태그 n개 제외" 표시.
- **제품·모델 이름 태그 제외:** 태그가 그 항목의 `topicKey`와 같거나, 모델 접두사(`gpt-`, `claude-`, `gemini-`, `qwen`, `llama-`, `deepseek`, `mistral-`, `grok-`)로 시작하면서 숫자를 포함하면 테마에서 제외(설정 `modelNamePrefixes`).
- **동의어 확장**(`trend.default.json`): `llm-benchmark→benchmark`, `claude-model→claude`, `mcp-server→mcp`, `mcp-tool→mcp`, `llm-inference→inference-optimization`, `ai-coding→coding-agent`, `coding-assistant→coding-agent`, `model-compression→quantization`, `quantized-model→quantization`, `ai-agents→ai-agent`.
- **표시(Claude 낮음):** 출처 맞춤 모드에서 건수 칸에 비교 건수를 함께: `7 (비교 1)`.

## 테스트
- 신규: 2건 → "—", 3건·1일 → "—", 3건·2일 → 신규.
- 스무딩 값, 주 1건 미만 앞 주.
- risingCount 3, ◆ 2일 조건.
- 희소 태그 제외(3건 또는 3일), 모델 이름 태그 제외, 새 동의어.
- 비교 건수 표시.

## 완료 기준
- `:core:test --rerun-tasks` 통과. 1~2 항목별 한 줄 + 테스트 개수.

# P3 수정 지시 — 4회차 (마무리, Claude·OpenAI 합의)

판정: Claude "통과"(낮음 2), OpenAI "수정 필요"(높음 1·중간 2). 3·4 양쪽 통과. 다음 리뷰가 5회차(한도)이므로 범위를 좁혀 마무리한다. 규칙 동일(커밋·docs·프롬프트 수정·키 출력·HTML 스크레이핑 금지).

1. **피드 이름은 비밀이 아닌 설정 라벨로 취급** (RssAdapter.kt:72·79·132, SourceCap.kt:25):
   - 설정 검증에서 피드 이름에 그 피드 URL의 비밀 후보(쿼리 값 6자 이상·userinfo)가 들어 있으면 거부(메시지에는 "피드 N번 이름에 URL 비밀값 포함"처럼 이름·값 없이).
   - 그 대신 `extra.feed`는 치환하지 않고 원래 이름을 그대로 쓴다 → SourceCap 피드별 `maxItems` 조회가 정확해진다. 실패·시간 초과 메시지도 이름만 사용.
   - 테스트: 비밀값을 포함한 이름 거부, 피드별 상한이 이름으로 정확히 적용.
2. **전체 기한 만료 시 HTTP 호출 취소 + 테스트 구분** (Http.kt:40, PipelineTest.kt:76):
   - `readResponse`가 코루틴 취소 시 OkHttp `Call.cancel()`을 호출하도록(예: `suspendCancellableCoroutine` + `enqueue`, 또는 `invokeOnCancellation`).
   - 테스트: 개별 HTTP 타임아웃을 넉넉히(예: 10초) 두고 출처 전체 기한만 짧게 해 **전체 기한 만료 경로**로 완료 피드·경고가 반환되고, 반환 시점이 전체 기한 + 여유(예: 1초) 안인지 확인.
3. **[Claude 낮음]** Reddit 댓글 1회 실패 → 다음 호출에서 재요청하는 테스트 추가.

## 완료 기준
- `:core:test --rerun-tasks` 통과. 1~3 처리 결과 한 줄씩.

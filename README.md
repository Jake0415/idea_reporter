# AI 아이디어 수집기

매일 AI 트렌드를 수집·정제해 서비스 아이디어 카드 5개를 메일로 보내는 개인용 Kotlin 안드로이드 앱.

- 설계: [docs/design.md](docs/design.md) (데이터 흐름·타입), [docs/menu.md](docs/menu.md) (기능·화면·메일)
- 작업 규칙과 확정된 결정: [CLAUDE.md](CLAUDE.md)

상태: 1단계 완료 — `core`에서 HN·GitHub 수집 → 규칙 필터·스팸 묶음 거르기 → 중복 제거·이미 본 글 제외 → 점수 통일까지 PC에서 동작. LLM 단계(2단계)부터 진행 예정.

## PC에서 실행 (1단계 확인용)

JDK 17이 필요하다. `JAVA_HOME`이 올바른 JDK를 가리키지 않으면 실행 전에 지정한다.

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
```

```powershell
.\gradlew.bat :core:test
```

```powershell
.\gradlew.bat :core:run
```

- 결과: `out/<날짜>.json` (남은 항목, 탈락 항목과 이유, 실행 기록), 이미 본 글: `out/seen.json`
- 같은 결과를 반복해서 보려면 `.\gradlew.bat :core:run --args="--keep-unseen"` (이미 본 글로 기록하지 않음)
- 출처 설정을 바꾸려면 `core/src/main/resources/sources.default.json`을 `config/sources.local.json`으로 복사해 고친다(커밋되지 않음).
- 비밀값은 프로젝트 폴더의 `.env`에 넣는다(커밋되지 않음). 형식은 `.env.example` 참고: `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`(둘 중 하나 이상, 2단계부터 필요), `GITHUB_TOKEN`(선택, 검색 호출 한도 증가). Windows 환경 변수에 같은 이름이 있으면 그 값이 먼저다. 실행하면 각 키의 "있음/없음"만 출력하고 값은 출력하지 않는다.

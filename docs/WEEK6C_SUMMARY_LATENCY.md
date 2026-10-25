# 6c · 오늘의 한마디 지연 대응 · 0.6.1 (9)

2026-10-23. [사용자/Claude 실측 명세](../SPEC_W6c_요약지연대응.md)에 따른 수정이다. 원래 규칙·게임·탐침·Jev 채택 임계·AI 전송 필드·2일 상세/7일 집계는 유지했다. 정차 시 요약 준비를 시작하도록 기록 세션의 연결 지점만 추가했다.

## 변경 파일

| 파일 | 내용 |
|---|---|
| `ai-proxy/config.mjs` | 기본1200토큰, 생성형 전체15초, RECAP_LLM_FALLBACK_MODEL 기본 MiniMax |
| `ai-proxy/providers.mjs` | Go+DeepSeek만 low, 빈 content일 때 대체 모델1회, 공유 시간 예산/세션 |
| `ai-proxy/contract.mjs`, `server.test.mjs` | 정차 후 한 번 살펴보기 문구 허용, 지시/허위 건수 차단, offset/대체 모델/전체 제한 시험 |
| `logic/.../recap/SummaryCache.kt`(신규) | 카드/맥락 SHA-256, 요청 세대 관리, 캐시 코덱 |
| `logic/src/test/.../recap/SummaryCacheTest.kt`(신규), `AiReviewTest.kt` | 캐시 적중·변경/삭제/재시도 경합·잘못된 캐시·offset 등 신규10개 |
| `app/.../RecapSummaryCoordinator.kt`(신규) | 서비스 프로세스 비동기 요청·메모리/AtomicFile 캐시·늦은 응답 배제 |
| `app/.../AiProxyClient.kt`, `AiJson.kt` | 분류3초/요약20초 분리, 캐시 문장 IPC 직렬화 |
| `app/.../RecapRecordingSession.kt`, `RecapRepository.kt` | D→P 선요청, 이후 카드 변경 시 갱신, 주행/삭제/AI 끄기 취소, 캐시 정리 |
| `app/.../RecapProvider.kt`, `MainActivity.kt` | 캐시/준비 중 조회, 템플릿→도착 문장 교체, 명시적 재시도 |
| `app/build.gradle.kts` | versionCode9 / versionName0.6.1 |
| `docs/AI_CONTRACT.md`, `ai-proxy/README.md`, `README.md`, `docs/WORK-CHECKLIST.md`, 이 문서 | 최신 계약·실측과 오프라인 증거·Claude 순서 |

## 계약·시간 제한

- 분류 입력은 동일하다. **조향 시작=0, 이후 양수**이며 `late.offsetMs`만0..durationMs다. before/none은 null이다. `before,-1500`은400이 맞다. 사전 사용은 state=before로 전달하고, 보관하지 않은 정확한 사전 시각을 만들어 보내지 않는다.
- Go+deepseek 계열만 `reasoning_effort:"low"`. max_tokens 기본1200(환경변수800..4096). GLM/MiniMax/OpenAI에 이 옵션을 넣지 않는다. GLM의 미확인 thinking 파라미터도 넣지 않는다.
- 첫 응답 content가 null/빈 문자열/공백이면 Go에서만 대체 모델로1회 호출한다. 기본 `minimax-m3.1-flash`, 환경변수 RECAP_LLM_FALLBACK_MODEL로 변경하며 빈 문자열은 비활성화다. 첫 모델과 같으면 다시 부르지 않는다.
- 첫 모델과 대체 모델의 **합계15초** 제한이다. 첫 요청이 대부분 소모하면 대체 모델은 남은 예산만 쓴다. 401/5xx/시간 초과/비어 있지 않은 잘못된 문장에는 재시도하지 않는다. 추론 내용은 표시하지 않는다.
- 앱 요약 총20초, Jev 총3초(서버2.8초) 유지. 실패는 기존 규칙/기본 문구다. 폴링이 실패를 자동 반복하지 않는다.

## 정차 선요청·캐시

1. RecapRecordingSession이 검증된 주행 상태에서 P로 전환되면 요약 저장/flush 뒤 서비스 프로세스가 비동기로 요청한다. 복기 화면을 열 필요가 없다. Activity가 onPause여도 이 요청은 계속된다.
2. 복기 화면은 provider에서 캐시를 읽는다. 있으면 즉시 AI 문장, 없으면 템플릿 + **AI 문장 준비 중**을 표시하고 완료 후 다음1초 폴링에서 교체한다. 업그레이드 후 기존 카드가 있는 경우에는 복기 진입도 준비를 시작할 수 있다.
3. 메모리 + `filesDir/recap-ai-summary-v1/today.bin` AtomicFile에 **오늘 한 건의** 문장/patterns/model·날짜·두 SHA-256만 저장한다. 카드 원문·특징값·HTTP 본문·키·세션 ID는 저장하지 않는다. 오늘/해시가 다르면 캐시를 쓰지 않는다.
4. 카드 해시는 전체 표시 카드의 근거·종류·시각·ID를 포함한다. 피드백·주행 시간 카운터·표시 순서·100개 페이지는 제외한다. 시간대/과거 집계는 별도 맥락 해시로 무효화한다. 해시는 외부로 보내지 않는다.
5. 다시 주행/주행 미확인/AI OFF/삭제 시 pending을 취소한다. 취소 직후 같은 카드로 새 요청하더라도 옛 응답은 요청 세대가 달라 메모리/디스크에 반영되지 않는다. 날짜가 달라져도 같은 처리를 한다.
6. **AI 다시 확인**은 현재 캐시를 지우고 새 요청한다. **기록 삭제**는 문장 캐시도 삭제한다. AI OFF는 저장된 문장을 표시하지 않는다. 오래된 캐시는 접근 및 기존6시간 Job에서 정리한다. 프로세스가 응답 완료 전에 종료되면 완료를 보장하지 않는다.

## 검증 결과와 남은 확인

- JVM **170/170**: 기존160 + 신규10. 기존 규칙137개를 유지했다. `.local/w6c-jvm-tests.txt`.
- Node **45/45**: 서버27 + 리플레이18. 기존37 + 신규8. `.local/w6c-node-tests.txt`.
- 전체 앱+logic 직접 Kotlin 타입 검사 통과. `.local/w6c-kotlin-types.txt`. 기존 탐침 deprecated 경고만 있다. Gradle 리소스/manifest 병합/APK 검증과 구분한다.
- Node는 가짜 키·모의 업스트림만 사용했다. 실제 키 파일을 읽거나 외부 AI를 재호출하지 않았다. 실제 기기 캐시 파일·Activity 없는 선요청·재실행·20초·정차 전환은 Claude 검증이 남았다.
- **실측 응답 문장3개의 본문은 명세에 없고 프로젝트 문서에서도 찾지 못했다.** 본문을 요청했다. 현재 safeText 회귀의 정상3개는 직접 작성한 합성 예시이며 실측 응답 통과라고 보고하지 않는다. `한 번 살펴보세요`는 허용하고 `운전 중/주행 중` 지시·허위 건수·안전/법률 단정·키 패턴 차단은 유지했다.
- Claude의0.6.0 측정(DeepSeek low3.9초/MiniMax4.4초/GLM6.5초)은 전달받은 실측 근거다. 0.6.1의1200토큰/15초/캐시 통합이 실제로 성공한 증거는 아직 아니다.

## Claude 실행 순서

서버를 먼저 **재시작**한다. 기존800토큰 환경변수가 있으면 지우거나1200으로 바꾼다.

```powershell
Set-Location D:\Projects\pleos-observe-lab
$env:RECAP_LLM_PROVIDER='opencode-go'
$env:RECAP_LLM_MODEL='deepseek-v4.1-flash'
$env:RECAP_LLM_FALLBACK_MODEL='minimax-m3.1-flash'
$env:RECAP_LLM_MAX_TOKENS='1200'
node .\ai-proxy\server.mjs   # 키는 환경변수로 따로 넣는다
```

별도 터미널에서 Claude가 빌드한다. Codex는 Gradle/AVD를 실행하지 않았다.

```powershell
Set-Location D:\Projects\pleos-observe-lab
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME=Join-Path $env:LOCALAPPDATA 'Android\Sdk'
.\gradlew.bat :logic:test :app:assembleDebug --offline --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Build failed. Do not install an older APK.' }
```

1. [이전 설치 명령](WEEK6_AI.md#2-빌드설치)을 쓰되 aapt2에서 **9/0.6.1**인지 확인한다. AI ON, 속도 미수신 시연 OFF. P에서 기록 삭제/시작, 준비됨 뒤 A를 실행한다.
2. A가 D일 때 앱이 가려져 있는 상태를 유지한다. 마지막 P 복귀 후 **복기 화면을 열기 전에** 서버 `proxy.summarize.ok`가 나오는지 본다. 네 규칙 카드/애매함0/Jev 호출 없음은 이전 기대 그대로다. 실패 시 규칙 결과를 바꾸어 개수를 맞추지 않는다.
3. 정차 직후 복기를 빨리 열면 템플릿 + **AI 문장 준비 중** → 응답 후 문장 교체를 확인한다. 요청이 이미 완료됐으면 즉시 AI 문장이 정상이다. 앱을 일시 숨겨도 준비가 계속돼야 한다. 실제 지연을 측정한다.
4. 문장 성공 뒤 서버를 끈다. 앱 재실행/복기 열기에서 동일 카드면 **디스크 캐시의 문장**이 즉시 보여야 한다. 캐시가 있을 때 서버 중지로 폴백을 기대하지 않는다.
5. 서버가 꺼진 상태에서 **AI 다시 확인**을 누르면 캐시를 비우므로 템플릿→준비 중→AI 미사용이 돼야 한다. 연결 거부는 즉시 끝날 수 있고, 응답을 지연시키는 시험에서는20초 한도를 확인한다. 분류는 여전히3초다.
6. 서버 재시작 후 다시 확인. P 삭제 후 C를 실행해 카드 목록 해시가 바뀌면 이전 A 문장을 사용하지 않는지 확인한다. C는 규칙 애매함2, Jev 판단 보류/낮은 확률이면 그대로 애매함이다.
7. 요약 대기 중 D 재진입, AI OFF, 기록 삭제를 각각 시험한다. 늦은 응답으로 문장/캐시가 되살아나면 실패다. 잠금 중 문장/상태 값이 화면에 남으면 실패다. 피드백 변경이나 페이지 이동만으로 새 요약 요청이 생기지 않아야 한다.
8. 빈 content 대체 호출/합계15초는 모의 시험으로 재현 가능하다. 실호출에서 대체가 발생했다면 응답 model이 MiniMax인지 기록하되 키·추론·개인정보는 보고에 넣지 않는다. 실제 문장3개가 확보되면 text만 회귀 시험에 추가한다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-logic-offline.ps1
node --test ai-proxy/server.test.mjs scripts/test-recap-replay.mjs
git diff --check
```

## Claude AVD 실측 (2026-10-23, 0.6.1)
- ai-proxy(opencode-go deepseek-v4.1-flash, reasoning_effort low, 1200토큰): `/summarize` 200, 7.2~8.6초, 패턴 문구 포함 정상.
- 앱: 시나리오 A → P 정차 시 선요청(`proxy.summarize.ok`) → 복기 화면 상단 "오늘의 한마디"(AI 문장) 표시, 카드 4종(좋아요·미사용·늦은 깜빡이·급제동) 유지, 규칙 애매함 0. 캡처 `docs/evidence/w3/recap_ai_01.png`.
- 개선 후보: 확정 규칙 카드에 붙는 "AI 미사용" 문구는 오해 소지 → "규칙 판정"으로 바꾸기. 급제동 감속 126m/s²는 Console 즉시 정지 아티팩트(시나리오 단계 감속 필요). 첫 '기록 시작' 탭이 "복기 서비스 응답 지연"(콜드 스타트) 후 재시도로 성공.

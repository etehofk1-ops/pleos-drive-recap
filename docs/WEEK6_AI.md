# 6주차 · AI 어댑터 인계 · 0.6.0 (8)

> 아래는0.6.0 당시 기록이다. Claude의 이후 실측과0.6.1 시간 제한·정차 선요청·문장 캐시는 [6c 문서](WEEK6C_SUMMARY_LATENCY.md)를 따른다.

2026-10-23. 6b 사용자 결정에 따라 생성형 기본 공급자는 OpenCode Go다. AI 없이도 기존 규칙·기록·복기·피드백이 동작한다. [계약](AI_CONTRACT.md)과 [서버 실행 문서](../ai-proxy/README.md)를 함께 본다.

## 구현과 검증 상태

- JVM **160/160**: 기존137 유지 + 신규23. Jev 채택 임계/보류·잘못된 분포·호출 대상/오류 폴백·근거 보존·단위/요약 개인정보 축소·템플릿·7일 집계 코덱/유지·C 시나리오를 검증했다. `.local/w6-jvm-tests.txt`.
- Node **37/37**: 중계19 + 리플레이18. 모의 업스트림만 사용했다. 키 누출·정확한 헤더/형식·401·5xx·타임아웃·클라이언트 종료·빈 content·잘못된 문구 폴백을 포함한다. `.local/w6-node-tests.txt`.
- 앱+logic Kotlin 직접 타입 검사 통과. `.local/w6-kotlin-types.txt`. 설치된 API36/Vehicle2.3.2와 임시 BuildConfig를 사용한 검사이며 Gradle, 리소스 병합, manifest, APK, AVD 실행 증거가 아니다.
- 실제 키 파일 열람·실제 Jev/Go/OpenAI 호출·Gradle 실행·AVD 조작은 하지 않았다. **Claude가 빌드**하고 아래 순서로 실측한다. 과거0.5.2의 AVD 통과는 [5c 실측 부록](WEEK5C_RULE_REFINEMENT.md#claude-avd-실측-2026-10-23-052)에 있으며 이번 AI 성공과 구분한다.
- 기존 게임/탐침, Vehicle SDK 구독, FGS, 안전 잠금, 조향 부호, NaviHelper 입력은 변경하지 않았다. 조향 좌우는 여전히 표시하지 않고 실제 TBT 미수신은 unknown이다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `ai-proxy/config.mjs`, `providers.mjs`, `server.mjs`, `contract.mjs` | 환경/승인된 키 읽기, Jev/Go/선택형 OpenAI, 로컬 HTTP, 닫힌 스키마/문구 검증 |
| `ai-proxy/server.test.mjs`, `ai-proxy/README.md` | 모의 공급자 시험, 실행·모델·키 안내 |
| `logic/.../recap/AiReview.kt`, `ReviewCounts.kt` | 순수 특징 추출·채택·표시·템플릿·집계 코덱 |
| `logic/.../recap/RecapModels.kt` | 사용하지 않던 W5용 Jev 인터페이스를 실제 특징값 계약으로 교체(저장 코덱은 유지) |
| `logic/src/test/.../recap/AiReviewTest.kt`, `ReviewCountsTest.kt`, `RecapScenarioTest.kt` | 신규23개. 기존137 assertion 유지 |
| `app/.../AiJson.kt`, `AiProxyClient.kt`, `WeeklyCountStore.kt` | 허용 필드 직렬화,3초/취소/세션, 집계 전용7일 저장 |
| `app/.../MainActivity.kt`, `RecapProvider.kt`, `RecapRepository.kt` | 토글/AI 오버레이/오늘의 한마디, 전체 일자 특징 IPC, 집계/삭제/설정 |
| `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml` | 8/0.6.0, 서버 BuildConfig, INTERNET, 기본 cleartext 차단 |
| `app/src/main/res/xml/network_security_config.xml`, `app/src/debug/res/xml/network_security_config.xml` | 기본 HTTPS, Debug에서만10.0.2.2 HTTP 허용 |
| `scripts/recap-scenario-c.json`, `scripts/test-recap-replay.mjs` | 경계 속도 조향2건, 기존 A/B 유지 |
| `docs/AI_CONTRACT.md`, 이 문서, `docs/WORK-CHECKLIST.md`, `README.md` | 계약·증거·인계 |

## 동작과 한계

규칙 UNSURE 조향 중 깜빡이 관측을 신뢰할 수 있는 구간만 Jev로 보낸다. 최고 선택 확률0.6 이상/판단 보류 아님이면 `AI 추정 · 종류`를 표시하고 기존 규칙 결과·수치도 그대로 보여 준다. 규칙 분류나 깜빡이 미사용 판정을 AI로 바꾸지 않는다. Jev 확률은 정답 확률이 아니다. 확정 카드에는 Jev 요청이 없다.

복기 화면에 진입하면 규칙 카드와 기본 문구가 즉시 보이고 AI 요청은 비동기로 진행한다. 실패·미채택·비대상 카드는 `AI 미사용`이다. 성공한 생성형 문장은 `오늘의 한마디` 아래 `AI 문장`으로 구분한다. 실패는 `AI 미사용 · 기본 문구`다. 자동 재시도는 없고 버튼으로 다시 요청한다. AI 결과는 저장하지 않는다.

상세 요약/피드백은 오늘·어제만, 네 건수 집계는7일만 보관한다. 집계는 규칙 확정 카드만 세며 AI 채택으로 과거 숫자를 바꾸지 않는다. 기간 정리는 기존 접근/6시간 Job을 사용하므로 자정 정각 삭제 보장은 없다. 손상 파일은 오류를 표시하고 원본을 보존한다. 기록 삭제는 집계까지 지우되 AI 설정은 남긴다.

Go는 개발·시연 전용이다. 서버 생성형8초보다 앱3초 제한이 짧아 실제 문장 대신 폴백이 자주 보일 수 있다. 실제 지연·모델 채택률은 미측정이다. 공식 추론 끄기 파라미터를 확인하지 못했으며 content가 빈 reasoning-only 응답은 폴백한다. GLM 대안의 실제 속도 시험도 미수행이다. 모델을 바꿔도 빈 content나3초 초과를 성공으로 보고하지 않는다.

## Claude 실행 순서

### 1. 서버

```powershell
Set-Location D:\Projects\pleos-observe-lab
$env:RECAP_LLM_PROVIDER='opencode-go'
$env:RECAP_LLM_MODEL='deepseek-v4.1-flash'
node .\ai-proxy\server.mjs   # TYPESAFE_API_KEY 등 키는 환경변수로 따로 넣는다
```

Go 키는 사용자 환경변수 또는 승인된 Aside 항목에서 읽는다. 키를 출력하지 않는다. 시작 로그의 `jevKeyPresent`/`recapKeyPresent`가 true인지와 provider/model만 확인한다. 다른 터미널에서 `Invoke-RestMethod http://127.0.0.1:8787/health`를 확인한다. 서버를 브라우저로 호출하지 않는다.

### 2. 빌드·설치

```powershell
Set-Location D:\Projects\pleos-observe-lab
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME=Join-Path $env:LOCALAPPDATA 'Android\Sdk'
.\gradlew.bat :logic:test :app:assembleDebug --offline --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Build failed. Do not install an older APK.' }
$adb=Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$aapt2=Join-Path $env:ANDROID_HOME 'build-tools\35.0.0\aapt2.exe'
$apk='.\app\build\outputs\apk\debug\app-debug.apk'
$info=(& $aapt2 dump badging $apk | Select-Object -First 1)
if ($info -notmatch "versionCode='8'" -or $info -notmatch "versionName='0.6.0'") { throw 'Not 0.6.0 APK.' }
& $adb devices -l
$serial='emulator-5554' # 실제 devices 결과로 정한다.
& $adb -s $serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'Install failed.' }
& $adb -s $serial forward tcp:8088 tcp:8088
& $adb -s $serial shell am start -n lab.pleos.observe/.MainActivity
```

wrapper는 Gradle/Java 임시 파일을 이 프로젝트 아래로 제한한다. 설치된 build-tools 버전이 다르면 aapt2 경로만 실제 버전으로 바꾼다. 오프라인 의존성이 없으면 Claude 환경에서 해결하고 실패한 APK를 설치하지 않는다.

### 3. A 회귀

1. P/속도0/UX 해제. 기록 중지 후 필요하면 기록 삭제. **AI 보조 사용 ON**, **속도 미수신 시연 OFF**, Debug 주소10.0.2.2:8787 확인.
2. 기록 시작 → 초기 GET3개와 `초기 신호 준비됨` →3초 대기. Console8088 새로고침 후 `scripts/recap-replay.js`를 DevTools에 붙여 넣는다. `await driveRecapReplay.runFile()`로 A JSON 선택. 페이지 physicsTick 쓰기 차단은 기존 스크립트가 처리한다. 실행 중 수동 제어하지 않는다.
3. P 복귀 →2초 대기 → 기록 중지 → 오늘 복기 보기. 기존 기대는 **좋아요1/미사용1/늦음1/급제동1, 규칙 애매함0**, 정상 저속 회전 숨김. 이4건 때문에 `proxy.classify.ok`가 찍히면 잘못된 호출이다. 생성형은 별도 `proxy.summarize.ok`와 UI `AI 문장`으로 확인한다.
4. A의 급제동은 Console 즉시 속도 반영 때문에 비현실적인 감속 수치가 나올 수 있다(기존5c 부록). 이번에는 검증된 A fixture를 유지했다. 이 값으로 실제 차량 탐지 정확도나 안전 효과를 주장하지 않는다.

### 4. C 애매함/AI

1. A와 분리해 개수를 볼 경우 P 기록 중지 후 삭제한다(이때 최근 집계도 삭제됨). 기록 시작/준비됨/3초 대기 뒤 같은 리플레이로 `scripts/recap-scenario-c.json`을 고른다.
2. C는25km/h 안정 대기 후25→35km/h 경계에서 `18,25,32,6,-15,-25,-5,0`을350ms 간격으로 준다. 두 번째는 좌우 부호를 반대로 하고 깜빡이를2초 앞서 켠다. 마지막 정지→P다.
3. P에서 기록 중지/복기 보기. **규칙 애매함2건**이 JVM 기대다. 실제 AVD 속도가 경계를 지나지 않았다면 로그를 확인하고 C 성공으로 보고하지 않는다. 두 건의 Jev 응답은 별도로 확인한다.
4. Jev 최댓값≥0.6/판단 보류 아님이면 **AI 추정**이 붙는다. 낮은 확률·보류·3초 초과·오류이면 애매함/AI 미사용을 유지하는 것이 정상이다. 실제 모델이 둘 다 채택할 것을 보장하지 않는다. 원래 규칙 근거·깜빡이 시각·수치가 유지되는지 본다.
5. 오늘의 한마디의 성공 또는 폴백을 구분해 기록한다. 최근 과거 집계2일 미만이면 patterns가 비어도 정상이다. A+C를 삭제 없이 연속 실행했다면 규칙 카드 총6건(확정4+애매함2)이 기대다.

### 5. 장애·설정·보관

1. 서버 Ctrl+C → 정차 복기 화면 **AI 다시 확인**. 각 요청3초 이내 기준으로 `AI 미사용`/기존 애매함/기본 문구를 확인한다. 이미 성공한 화면 캐시를 그대로 보고 폴백 성공이라 하지 않는다.
2. 서버 다시 시작 → AI 다시 확인. 명시적 재요청만 수행하고 자동 반복 요청이 없는지 본다.
3. AI 토글 OFF → 복기 보기/앱 재실행. 새 HTTP 요청 없이 규칙/템플릿이 유지되는지 확인한다. 앱 설정 저장 실패면 오류를 확인한다.
4. AI 응답 대기 중 D로 전환하면 화면 잠금, 늦게 온 문장·카드가 나타나지 않아야 한다. P 복귀는 시작 화면. FGS 기록 지속은 기존 동작이다.
5. 7일 집계 파일은 날짜+4개 정수뿐인지, 2일 상세 유지와 삭제가 함께 동작하는지 Claude가 내부 저장소에서 검증한다. 실제7일 경과/Job 실행은 이번 오프라인 테스트의 증거가 아니다.
6. 모델 비교가 필요하면 서버 중지 후 `RECAP_LLM_MODEL=glm-5.3-flash`로 재실행하고 빈 content/3초 내 응답을 측정한다. 기본 공급자를 OpenAI로 바꾸지 않는다.

키 없이 재검증:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-logic-offline.ps1
node --test ai-proxy/server.test.mjs scripts/test-recap-replay.mjs
git diff --check
```

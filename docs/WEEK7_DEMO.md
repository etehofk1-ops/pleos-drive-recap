# 7주차 · 다듬기와 영상 시연 · 0.6.2(10)

이 문서는0.6.2 구현 당시 기록이다. 이후 D 실측에서 소수 속도 HTTP400·급제동2건·준비 상태 잔류가 확인됐다. 현재0.6.3의 D 타임라인·시험·빌드 명령은 [7b 수정 인계](WEEK7B_DEMO_FIX.md)를 따른다.

2026-10-23. 기준은 [0.6.1 Claude AVD 실측 부록](WEEK6C_SUMMARY_LATENCY.md#claude-avd-실측-2026-10-23-061)이다. 이번 변경은 표시·기동 대기·리플레이에 한정한다. 차량 구독, 주행/잠금 판정, 규칙 임계값, AI 서버/HTTP 계약, 저장 형식과 보관 기간, 기존 게임·탐침은 유지한다. 이번 Codex 작업에서 Gradle/APK/AVD/실제 AI 호출은 실행하지 않았다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `app/build.gradle.kts` | versionCode10 / 0.6.2 |
| `app/src/main/java/lab/pleos/observe/MainActivity.kt` | 시작 준비 중/중복 시작 방지, 오늘 전체 요약 한 줄, 큰 카드/강조 문장, Debug 감속 수치 |
| `app/src/main/java/lab/pleos/observe/RecapProvider.kt` | 첫 IPC 대기3→8초, 100개 페이지와 별개로 오늘 전체 집계 전달 |
| `logic/src/main/kotlin/lab/pleos/observe/logic/recap/AiReview.kt` | 정상 AI 응답 보류와 실패 구별, 상태별 카드 라벨 |
| `logic/src/main/kotlin/lab/pleos/observe/logic/recap/RecapText.kt` | 감속15m/s² 초과 표시 보호 |
| `logic/src/main/kotlin/lab/pleos/observe/logic/recap/ReviewCounts.kt` | 규칙 기반 전체 요약 문구 |
| `logic/src/test/kotlin/lab/pleos/observe/logic/recap/RecapPresentationTest.kt` | 라벨/감속 경계/집계11개 신규 |
| `logic/src/test/kotlin/lab/pleos/observe/logic/recap/RecapScenarioTest.kt` | 실제 D JSON의 카드/숨김/애매함 회귀, 모든 스텝 파싱·순서 확인 |
| `scripts/recap-replay.js` | 한 줄 caption 검증, 실행 시 콘솔 출력만 |
| `scripts/recap-scenario-a.json` | 급제동 단계 감속/브레이크90 유지 |
| `scripts/recap-scenario-d-demo.json` | 약70초 출근길 시연, 매 스텝 한국어 caption |
| `scripts/test-recap-replay.mjs` | 단계 감속/브레이크 유지, D 자막·완만 감속/복원 등 회귀 |
| `README.md`, `docs/AI_CONTRACT.md`, `docs/WORK-CHECKLIST.md`, 이 문서 | 현재 표시 정책·실행·검증/인계 |

## 표시와 기동

| 카드 상태 | 표시 |
|---|---|
| 규칙 확정 | 기존 제목 + **규칙 판정**. AI OFF/서버 중지여도 동일 |
| AI 대상이 아닌 애매한 카드 | 기존 **애매함** + **규칙 판정** |
| Jev 요청 대기 | 기존 **애매함** + **AI 확인 중** |
| 유효한 Jev 응답이 보류 또는 최대 선택 확률0.6 미만 | 기존 **애매함** + **AI 판단 보류** |
| AI OFF / 서버·응답 오류로 분류 불가 | AI 대상 애매한 카드에만 **AI 미사용** |
| Jev 채택 | **AI 추정 · 종류**, 원래 규칙 결과/수치 그대로 |

분류 채택 기준이나 요청 대상은 바꾸지 않았다. 오늘의 한마디 실패는 **기본 문구**, 대기는 **AI 문장 준비 중**이다. 규칙 확정 카드와 문장 공급 상태를 혼동하지 않도록 했다.

감속값 **>15m/s²**는 **감속 큼(측정값 이상)**으로 표시한다. 정확히15 이하는 기존 수치 표시다. Debug에서만 별도 `Debug 원시 감속 …m/s²`가 붙는다. 저장 근거·판정·카드 개수는 수정하거나 숫자를 잘라 맞추지 않는다.

Provider 처리 대기를3→8초로 늘렸다. UI는 IO 스레드로 읽으며 **복기 서비스 준비 중 / 준비 중…**을 표시하고 시작 버튼 중복 입력을 막는다. 실제 `recording=true` 수신 뒤 중지 버튼으로 바뀐다. 시작 확인은 최대12초 기다리고, 끝내 확인 못 하면 오류를 보여 준다. 시작/삭제/피드백 명령을 자동 재전송하지 않는다. 실제 콜드 스타트 해결 여부는 AVD 확인이 남았다.

복기 상단은 페이지에 표시한100개가 아닌 **오늘 전체 규칙 집계**를 한 줄로 표시한다. 차선 변경 합계=좋아요+놓침+늦음이며 급제동/회전/애매함은 더하지 않는다. 애매함은 있을 때 별도 횟수를 붙인다. 카드 제목34sp/근거28sp, 오늘의 한마디 제목34sp/본문30sp, 강조 테두리·배경을 적용했다. 상단 집계는 한 줄에 맞춰20~30sp로 조절한다. 실제2560×1440 잘림/배치/스크롤은 Claude 확인 항목이다.

## 시나리오 D 스텝 표

아래는 JSON의 스텝을 장면 단위로 묶은 표다. 모든 실제 스텝에 caption이 있고 리플레이는 **브라우저 콘솔에만** 출력한다. 화면 자막 삽입/영상 저장은 하지 않는다. 기준 타임라인70초에 속도 도달 대기와 시작 보호 대기0.5초가 추가되므로 실제 길이는 더 길 수 있다.

| 기준 시각 | 장면/제어 | 자막 요지 | 기대 |
|---|---|---|---|
| 0초 | P·0km/h·중립·Off | 출근길 기록, 출발 전 정차 | 초기값 확인 |
| 4~6.5초 | D→60km/h, 도달 대기 | 운전 중 화면 없이 기록·순항 | 플랫폼/앱 잠금 |
| 8~13.5초 | 깜빡이 LEFT, 2초 후350ms 조향8,15,12,4,-6,-10,-4,0 →Off | 미리 켠 깜빡이, 좋아요 | 좋아요1 |
| 17~21초 | Off 유지, -8,-14,-10,-3,6,11,5,0 | 이번에는 깜빡이를 놓쳤어요 | 놓침1 |
| 24~27.5초 | 7,13부터 조향, 24.6초 LEFT, 9,2,-7,-10,-3,0 →Off | 핸들을 움직인 뒤 깜빡이 | 늦음1 |
| 30~39초 | 60→0km/h, **100ms마다0.75km/h**, 브레이크10, 정지 확인 후0 | 신호 앞 완만 감속 | 명령상 약2.08m/s², 급제동 추가 없음 |
| 41~48초 | 12km/h·RIGHT, 20,50,80,90×4,70,40,10,0 →Off | 저속 교차로 우회전 | 회전1, 목록 숨김 |
| 50초 | 다시60km/h 도달 대기 | 순항 후 급제동 장면 | 조향 중립 |
| 54~57초 | 60→45→30→15→0, **450ms 간격·브레이크90**, 정지/400ms 유지 확인 후0 | 단계 급제동 한 구간 | 급제동1 |
| 62~70초 | 0km/h 유지→P | 정차 후 오늘 운전 복기 | 문장 선요청/잠금 해제 |

기대 복기: **좋아요1 + 깜빡이 없이 차선 변경1 + 늦은 깜빡이1 + 급제동1 = 카드4, 애매함0**. 요약은 **오늘 차선 변경3번 · 깜빡이 놓침1 · 늦게 켬1 · 급제동1**이다. 예시의 차선 변경4번을 고정 문구로 쓰지 않는다. A도 같은 네 카드가 기대된다.

A 급제동은30.0/30.45/30.9/31.35/31.8초의60/45/30/15/0km/h, 브레이크90으로 교체했다. 마지막 정지 확인 중에도 브레이크를 재전송하며33초에 푼다. D도 같은 방식이다. `(15 / 3.6) / 0.45 ≈ 9.26m/s²`는 **명령 간 평균**이며 실제 SDK 측정값의 보장이 아니다. 서버가 각 단계를 즉시 반영하거나 콜백 간격이 달라지면15 초과가 여전히 나올 수 있어 표시 보호를 함께 적용했다.

초기 D의 신호 대기를15km/h 단위·2초 간격으로 만들었을 때 JVM에서 급제동이 추가 검출됐다. 각 단계의 순간 속도 하강이 원인이어서 작은 단계로 고쳤고, 기대 카드 수/판정 임계값을 완화하지 않았다. JVM 시나리오는 고정 속도 콜백을50ms 간격으로 합성하는 회귀이며 실제 Console 물리/SDK 수신 증거와 구분한다.

## 오프라인 검증

- JVM **182/182**: 기존170 유지 + 표시11 + D 시나리오1. 기존137 규칙/게임 테스트 포함. `.local/w7-jvm-tests.txt`.
- Node **49/49**: 서버27 유지 + 리플레이22(기존18 갱신 + 신규4). 모의 HTTP/가상 시계만 사용. `.local/w7-node-tests.txt`.
- 전체 앱+logic 직접 Kotlin 타입 검사 통과. `.local/w7-kotlin-types.txt`. 기존 deprecated 경고는 남아 있다. BuildConfig stub 기반이며 Gradle 리소스/manifest/Release APK 시험과 구분한다.
- 실제 화면·콜드 스타트 지연·측정 감속·D 길이·AI/캐시는 이번0.6.2에서 미검증. 아래 순서로 Claude가 확인한다.

```powershell
Set-Location D:\Projects\pleos-observe-lab
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-logic-offline.ps1
node --test ai-proxy/server.test.mjs scripts/test-recap-replay.mjs
git diff --check
```

## Claude 실행 순서

1. 기존 방식으로 ai-proxy를 실행한다. 변경된 공급자/키/모델 옵션은 없다. 서버 상태와 키 존재 여부만 확인한다.

```powershell
Set-Location D:\Projects\pleos-observe-lab
$env:RECAP_LLM_PROVIDER='opencode-go'
$env:RECAP_LLM_MODEL='deepseek-v4.1-flash'
$env:RECAP_LLM_FALLBACK_MODEL='minimax-m3.1-flash'
$env:RECAP_LLM_MAX_TOKENS='1200'
node .\ai-proxy\server.mjs   # TYPESAFE_API_KEY 등 키는 환경변수로 따로 넣는다
```

2. 별도 터미널에서 **Claude가 빌드·설치**한다. 설치한 build-tools 버전이 다르면 aapt2 경로만 바꾼다. serial은 실제 devices 결과를 사용한다.

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
if ($info -notmatch "versionCode='10'" -or $info -notmatch "versionName='0.6.2'") { throw 'Not 0.6.2 APK.' }
& $adb devices -l
$serial='emulator-5554' # 실제 devices 결과로 정한다.
& $adb -s $serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'Install failed.' }
& $adb -s $serial forward tcp:8088 tcp:8088
& $adb -s $serial shell am force-stop lab.pleos.observe
& $adb -s $serial shell am start -n lab.pleos.observe/.MainActivity
```

3. **콜드 스타트**: P·속도0·UX 해제에서 앱 첫 기록 시작을 한 번만 누른다. 권한 요청이 뜨면 승인 후 다시 한 번 누르는 기존 흐름이다. 준비 중→기록 중/초기 신호 준비됨, 첫3초 지연 오류 미노출과 중복 시작 방지를 확인한다. 8초/12초를 넘는다면 실제 지연과 로그를 보고하고 성공으로 처리하지 않는다.
4. P에서 기록 중지/기록 삭제 후 새 기록 시작. AI ON, Debug 속도 미수신 시연 OFF. 초기 신호 준비됨+3초 대기. Console8088을 새로고침하고 `scripts/recap-replay.js`를 DevTools에 붙여 넣는다. `await driveRecapReplay.runFile()`로 **A**를 선택한다. 네 카드/애매함0/전부 규칙 판정, 저속 회전 숨김을 확인한다. 감속>15일 때 경고 문구와 Debug 원시값을 구분한다.
5. P에서 다시 기록 중지/삭제/시작/준비됨+3초 대기. 같은 리플레이로 **D JSON**을 선택한다. 재생 중 Console 수동 조작 없이 `recap caption`을 확인한다. 약70초+속도 대기 뒤 P·0·Off·브레이크0으로 복원돼야 한다. 영상은 앱을 P에서 보여 주고 D 중에는 플랫폼 가림을 유지한다.
6. D 복기에서 위 기대 네 카드와 **차선 변경3번**을 확인한다. 2560×1440에서 요약 한 줄이 잘리지 않는지, 큰 제목/근거/피드백 버튼이 읽히는지, 강조한 오늘의 한마디가 준비 중→문장 또는 캐시 즉시 표시되는지 확인한다. 실제 SDK 감속과 실행 시간을 기록한다.
7. **라벨 경계**: A/D 같은 확정 카드에서 AI OFF·서버 중지여도 규칙 판정이다. C를 별도 기록해 애매함2를 만들면 요청 중 AI 확인 중, 정상 보류/낮은 확률이면 AI 판단 보류, 채택이면 AI 추정이다. 서버를 끄고 **AI 다시 확인**, 또는 AI OFF 후 복기로 들어가면 이 애매한 AI 대상 카드에만 AI 미사용이다. 요약 실패는 기본 문구다.
8. 준비/요약 대기 중 D 전환 시 값/카드/문장이 가려지고 P 복귀는 시작 화면인지 확인한다. 피드백/삭제와 제목 길게 누르기의 기존 게임·탐침도 간단히 회귀 확인한다.

실제 키·외부 AI 응답·과거 사용자 기록은 이 문서에 복사하지 않는다. 영상 촬영·편집과 실차 정확도 검증은 이번 구현에 포함하지 않는다.

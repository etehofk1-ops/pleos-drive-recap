# 7b · D 실측 수정 · 0.6.3(11)

2026-10-23. Claude의0.6.2 D 실측을 반영했다. [화면](evidence/w3/recap_demo_d.png)에서 급제동2건(5.3/40.5m/s²), 깜빡이 준비 상태 잔류, 카드 앞의 큰 버튼2개를 확인했다. [녹화](evidence/w3/demo_d_raw.mp4)는 파일 존재를 확인했고124초 길이는 Claude 전달값이다. 원본 증거는 수정하지 않았다.

작업 시작 시 Git은 깨끗했으며 Claude의 speed Math.round/정수 D 변경은 이미 현재 소스에 들어 있었다. 이 변경을 보존하고 preflight·held controls·HTTP 송신값까지 일치시켰다. 차량 구독/API/권한, 규칙 임계값, 잠금, AI 계약/서버, 저장 형식·기간, 기존 게임/탐침은 변경하지 않았다.

## 변경 파일

| 파일 | 변경 |
|---|---|
| `app/build.gradle.kts` | versionCode11 / 0.6.3 |
| `app/src/main/java/lab/pleos/observe/RecapRecordingSession.kt` | GET 완료 표식 대신 실제 엔진의 수신 상태로 준비 표시 |
| `app/src/main/java/lab/pleos/observe/MainActivity.kt` | 오늘의 한마디→카드→AI 다시 확인/처음으로 순서 |
| `logic/src/main/kotlin/lab/pleos/observe/logic/recap/RecorderReadiness.kt` | GET/notify 공통 준비 표시 순수 함수 |
| `logic/src/test/kotlin/lab/pleos/observe/logic/recap/RecorderReadinessTest.kt` | 준비/미수신/복구/오류 회귀8개 |
| `logic/src/test/kotlin/lab/pleos/observe/logic/recap/RecapScenarioTest.kt` | D 최대 감속8~10 검사, 주기적 속도 콜백 없는 조건1개 추가 |
| `scripts/recap-replay.js` | preflight 정수 정규화·변환 목록/알림, 지연 후 명령 몰아 보내기 방지 |
| `scripts/recap-scenario-d-demo.json` | 정수 감속 타임라인, 급제동 세분화/마지막 완만 정지/자막 |
| `scripts/test-recap-replay.mjs` | 정수만 받는 서버 모의, 소수/held/범위·지연 회귀4개, D 기대 갱신 |
| `README.md`, `docs/WORK-CHECKLIST.md`, `docs/WEEK7_DEMO.md`, 이 문서 | 현재 실행/기대/근거, 이전7주차 문서의 당시 범위 표시 |

## 정수 속도와 간격

- `preflight()`는 원래 입력이 유한한0~240km/h인지 검증한 뒤 `Math.round`한다. 음수/범위 밖/NaN/Infinity는 반올림으로 구제하지 않는다.
- 반환 계획의 `steps[].controls.speedKph`, 이후 유지값과 실제 URL이 모두 같은 정수다. 입력 JSON 객체는 변경하지 않는다. `speedAdjustments: [{atMs,fromKph,toKph}]`로 변환 내역을 돌려준다.
- 실행/dryRun에서 반올림이 있으면 정수 속도 제약을 알리고 변환 목록을 콘솔에 출력한다. D 자체는 이미 전부 정수라 변환이 없어야 한다. 조향/브레이크까지 임의로 반올림하지 않는다.
- 이전 절대 시각 따라잡기는 HTTP가 늦어지면 다음 명령 간격을 줄였다. 이제 각 쓰기 완료/작은 타이머 지연만큼 뒤의 시각을 이동해 JSON의 다음 간격을 보존한다. 속도 도달 대기/HTTP 비용 때문에 실제 길이가 늘 수 있다. 중단 시0·중립·Off·P 복원과 페이지 fetch 복원은 유지한다.

## D 새 타임라인과 감속 기대

| 기준 시각 | 동작 | 기대 |
|---|---|---|
| 0~27.5초 | 기존 P→D/60 순항/좋아요/깜빡이 놓침/늦음 | 차선 변경3건 그대로 |
| 30~45초 | 60→0, **1km/h씩250ms**, 브레이크10 | 명령 평균 **1.11m/s²**, 급제동 추가 없음 |
| 46초 | 정지 확인 후 브레이크0 | 신호 대기 |
| 48~55초 | 12km/h 도달→RIGHT→기존 큰 조향→Off | 저속 회전1, 목록 숨김 |
| 57초 | 60km/h 도달 대기 | 순항 복귀 |
| 61~62.5초 | 60→57→54→…→15, **3km/h씩100ms**, 브레이크90 | 명령 평균 **8.33m/s²**, 목표 약9 |
| 62.5~66.25초 | 15→0, **1km/h씩250ms**, 브레이크90 계속 | 15/10/5/0은62.5/63.75/65/66.25초, **각1.25초** |
| 68~74초 | 정지 확인 후 브레이크0→P | 복기/문장 선요청 |

기준74초에 속도 대기·HTTP/타이머 지연이 추가된다. 모든 실제 스텝의 한국어 caption은 콘솔 출력만이다. **기대: 좋아요1·깜빡이 없이 차선 변경1·늦은 깜빡이1·급제동1 = 카드4, 애매함0**, 저속 우회전 숨김. 요약은 **차선 변경3번 · 놓침1 · 늦게 켬1 · 급제동1**이다.

감속 수치는 명령 평균과 실수신 측정값을 구별한다. 정수 속도1km/h가 순간 반영되고 앱의100ms 창에 들어오면 약2.78m/s²로 계산될 수 있어, 간격을 늘리는 것만으로 **모든 실수신 샘플≤2**를 보장할 수는 없다. 새 완만 감속은 명령 평균1.11이고, 겹친2km/h 하강과 지연 뒤 몰아 보내기를 제거해 급제동 임계3 미만을 목표로 한다. 급제동은 큰15km/h 하강 대신3km/h씩 나누어 약8.3을 목표로 한다. 실제 AVD 값이 다르면 콜백 간격을 확인하며 임계값이나 원시 숫자를 바꿔 맞추지 않는다. 기존>15 경고/Debug 숫자 표시도 유지한다.

## 깜빡이 준비 상태 원인과 수정

이전 `publishSeedStatus()`는 현재 `state.indicator`가 알려져 있어도 `initialGetReady`에 turnSignal이 없으면 계속 미준비로 표시했다. notify와 늦은 GET 경쟁으로 GET이 채택되지 않는 경우 이 표식은 끝까지 없을 수 있었다.

이제 실제 엔진의 `indicator != null`, `brake != null`, 유한한 `angle`로 준비 상태를 계산한다. OFF/false/0도 실제 수신 상태다. GET 성공 표식이나 capability/register 성공을 수신값 대신 쓰지 않는다. GET 미확인 이력이 있어도 유효 notify가 오면 준비됨, 이후 null/오류는 다시 해당 미수신으로 돌아간다. 복구된 신호의 옛 GET 실패가 다른 신호의 대기를 실패로 표시하지 않게 했다. 새 기록 세션은 여전히 미수신에서 시작한다. 구간의 깜빡이 관측 창/분류 규칙은 바꾸지 않았다.

화면은 **상단 요약/기록 상태 → 오늘의 한마디 → 복기 카드 → 페이지 이동 → AI 다시 확인/처음으로**다. 큰 보조 버튼이 첫 카드 위에 끼지 않는다. 실제 화면의 첫 카드 가시성은 AVD 캡처로 확인한다.

## 검증

- JVM **191/191**: 기존182 유지 + 준비 상태8 + D 속도 알림 방식1. D는50ms 주기 속도 콜백과 스텝 기반 속도 콜백 두 조건 모두 급제동1·약8.33m/s²·애매함0이다. `.local/w7b-jvm-tests.txt`.
- Node **53/53**: 서버27 유지 + 리플레이26. 소수 속도 정규화/정수 HTTP/원본 보존·범위 검증, D 완만 감속·마지막 정지·급제동,80ms HTTP 비용에서도 간격 보존·복원을 확인했다. `.local/w7b-node-tests.txt`.
- 앱+logic 전체 직접 Kotlin 타입 검사 통과(기존 deprecated 경고). `.local/w7b-kotlin-types.txt`. Gradle 리소스/manifest/APK/실화면 검증과 구분한다.
- 실제 AI/키 파일/Console 제어/Gradle/AVD/영상 촬영은 이번 Codex 작업에서 실행하지 않았다.0.6.3 실측 감속·카드 수·notify 준비 복구·첫 카드 배치는 Claude 인계다.

## Claude 실행 순서

1. 기존 ai-proxy를 같은 설정으로 실행한다. [서버 명령](WEEK7_DEMO.md#claude-실행-순서)은 유지한다. 요약 캐시/AI 폴백 정책도 그대로다.
2. 별도 터미널에서 **Claude가 빌드**한다. 설치한 aapt2/에뮬레이터 serial은 실제 환경과 맞춘다.

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
if ($info -notmatch "versionCode='11'" -or $info -notmatch "versionName='0.6.3'") { throw 'Not 0.6.3 APK.' }
& $adb devices -l
$serial='emulator-5554' # 실제 devices 결과를 사용한다.
& $adb -s $serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'Install failed.' }
& $adb -s $serial forward tcp:8088 tcp:8088
& $adb -s $serial shell am start -n lab.pleos.observe/.MainActivity
```

3. P·속도0·UX 해제에서 기존 기록 중지 후 삭제, AI ON/속도 미수신 시연 OFF, 기록 시작. 초기 신호 준비됨 또는 남은 미수신 이유를 확인한다. 깜빡이 GET이 미확인인 경우 Console에서 LEFT→Off를 주고 notify 수신 뒤 준비됨으로 바뀌는지 확인한다. 이어 D 시나리오를 실행할 때 첫 조향 전 관측 창이 확보되도록3초 이상 기다린다.
4. Console 페이지를 새로고침하고 **최신** `scripts/recap-replay.js`를 DevTools에 다시 붙인다. `await driveRecapReplay.runFile()`로 **최신 D JSON** 선택. 구버전 JS를 계속 쓰지 않는다. 정수 D는 반올림 알림/HTTP400이 없어야 한다. 실행 중 Console을 수동 조작하지 않는다.
5. P 복귀→복기. **급제동1/카드4/애매함0**, 급제동 약8~10m/s²가 목표다. 완만 신호 정지에서 추가 카드가 없어야 하며 마지막15→0이 한 번에 떨어지지 않아야 한다. 실제 수신 감속이 목표를 벗어나면 카드 수·값·시간과 `ObserveLab` 로그를 보내고 성공으로 처리하지 않는다.0.6.2의 기존5개 카드가 섞이지 않았는지도 본다.
6. 깜빡이 notify를 받은 뒤 준비 상태가 남지 않는지 확인한다. 첫 화면에 요약·오늘의 한마디 다음 카드가 보이고, AI 다시 확인/처음으로는 카드 아래에서 동작하는지2560×1440 캡처로 확인한다. 주행 잠금·P 복귀·피드백/삭제도 회귀 확인한다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-logic-offline.ps1
node --test ai-proxy/server.test.mjs scripts/test-recap-replay.mjs
git diff --check
```

## Claude AVD 실측 (2026-10-23, 0.6.3)
- 게스트 안 `adb shell screenrecord`를 켜면 VHAL 조향 notify가 1.5초 간격으로 성겨져 차선 변경이 '입력 공백·애매함'으로 떨어짐 → **녹화는 호스트 쪽 `adb emu screenrecord start/stop`** 으로.
- Console 서버는 정수 속도만 받고, 명령을 완만하게 줘도 수신 감속이 계단식(20km/h 부근 3.9m/s²) → 신호 대기 정지는 브레이크 0(회생 감속)으로 시나리오 D 수정(Claude, 60 steps).
- 최종 D 결과: **차선 변경 3번 · 깜빡이 놓침 1 · 늦게 켬 1 · 급제동 1 · 애매함 0**, 오늘의 한마디 표시. 급제동 원시 감속은 21.4m/s²라 "감속 큼(측정값 이상)" 표시. 영상 `docs/evidence/w3/demo_d4_720p.mp4`(136초), 화면 `recap_demo_d4.png`.

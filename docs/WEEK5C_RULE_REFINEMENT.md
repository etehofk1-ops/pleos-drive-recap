# 5c · 판정 다듬기 · 0.5.2

2026-10-23. versionCode **7**, versionName **0.5.2**. 코드·오프라인 시험을 마쳤다. **Gradle 빌드·AVD 시험은 Claude가 한다.**

## 실측과 코드에서 확인한 것

사용자 제공 01:25 Claude 실측: 0.5.1 주행 상태 결합은 정상. 60km/h와 350ms 조향 파형에서 애매한 카드3/급제동0, 저속 회전 숨김은 정상. 브레이크 true→false는 41ms였고 서버가 이후 감속했다. 페이지 fetch를 격리하면 60km/h가 유지됐다. 이번 수정에서 HTTP 쓰기·설치·AVD 시험을 실행한 것은 아니다.

코드에서 확인한 문제 경로:

- 초기 GET은 1회뿐이며 실패/null/응답 없음 재시도가 없었다. GET 성공 자체는 원래도 상태 입력이지만 notify가 오면 이후 GET을 무조건 버렸다.
- SDK 콜백을 main에 전달한 뒤 세션이 한 번 더 post했다. 그 사이 ticker가 앞서면 수 ms 오래된 signal/steering도 버리고 indicatorKnownSince=null/INPUT_GAP으로 만들 수 있었다. **350ms notify 간격 자체가 기존 5초 공백 기준에 걸리는 것은 아니다.**
- 속도 샘플 간격이 100ms보다 짧으면 매번 계산을 건너뛰면서 기준점을 갱신해 전체 감속을 놓칠 수 있었다. 브레이크 false가 먼저 오면 이후 감속도 제외했다.
- 기존 목록은 최신순이다. 첫 카드가 시나리오1이라는 뜻은 아니다. 보고된 `-13→10` 모양은 시나리오3과도 일치한다. 원본 전체 콜백 로그가 없으므로 기존 카드 귀속은 확정하지 않는다. 새 구간 ID/시각 로그와 초 단위 UI로 다시 대응한다.

## 초기값·시각·값 유지

`DriveSignalSubscriptions`가 AAR로 이미 확인한 기존 getter 세 개를 매 기록 세션마다 호출한다:

- `getCurrentTurnSignalState` → OFF/LEFT/RIGHT
- `getCurrentBrakePedalStatus` → Boolean
- `getCurrentSteeringWheelAngle` → Float

null/오류/2초 timeout에는 500ms 뒤 재시도하며 최대12회다. 성공하지 못하면 미확인 상태와 로그를 남긴다. OFF/false/0을 기본값으로 만들어 넣지 않는다. 요청 이후 notify가 오면 그 GET 응답은 버리고 다시 조회한다(`InitialReadTracker`). GET 실패가 이미 받은 유효 notify를 지우지도 않는다. 재시작은 새 tracker·엔진 상태로 시작하고, 중지 시 재시도 예약을 취소한다.

메인에 **초기 신호 준비됨** 또는 아직 확인할 신호 이름을 표시한다. GET 준비 완료 뒤에도 실제 값이 null/오류이면 준비 상태로 표시하지 않는다. 로그의 `source=get initial=true`와 `<신호>.initialGet.ready`가 실제 GET 성공의 근거다. 초기 조회가 끝내 실패하면 UI에 미확인을 표시하고 재시작을 안내한다.

main에 도착한 콜백은 세션에서 다시 post하지 않는다. 서로 다른 신호의 수신 시각이 전역 reducer 시계보다 **250ms 이내** 오래된 경우, 원래 eventAt을 보존하면서 계산 시계만 앞으로 맞춘다. 같은 신호 내 역전 또는 더 큰 역전은 기존처럼 불확실하게 처리한다. 이는 prototype dispatcher 허용값이며 센서 지연 정확도 보장이 아니다.

깜빡이 시작/종료·조향 시작은 원래 수신 시각으로 계산한다. 시작 전3초의 ON은 사전 사용, 구간 중 ON은 실제 ON 시각−조향 시작 시각으로 계산한다. 중립 복귀 이후 ON을 앞 구간의 늦은 깜빡이로 붙이지 않는다. 처리 순서가 몇 ms 바뀐 사전 ON도 올바른 구간에 대응한다. 동일 ms라서 순서를 정할 수 없는 입력은 계속 애매함이다.

조향·깜빡이·브레이크는 ON_CHANGE 상태를 유지한다. 값이 안 바뀌어 notify가 없거나 조향 간격이 350ms~3초라고 INPUT_GAP을 붙이지 않는다. 명시적 null/실패·비유한 조향·진짜 시각 역전은 계속 품질을 떨어뜨린다. 속도 3초 신선도, 5초 초과 주행 시간 공백 제외, 700ms 중립 병합, 30km/h 차선 기준 등은 그대로다. 기어/UX 화면 잠금도 그대로다.

## 급제동 창과 최소 요약

급제동 기준 **3m/s²**는 바꾸지 않았다. 속도는 m/s, 카드 속도만 km/h다.

1. 속도 패킷이 20~50ms여도 기준점 하나를 100ms 이상 유지해 감속을 계산한다. 유효 계산 구간은100~2,000ms이며 NaN/Infinity/overflow는 버린다.
2. 직전1.5초의 감속 **구간 요약**만 최대32개 메모리에 유지한다. 원시 속도 스트림을 파일로 쓰지 않는다.
3. 주행 중 브레이크 true가 오면 직전1.5초 감속과, true 유지 중 및 해제 후1.5초 감속을 같은 에피소드에 연결한다. 41ms 펄스도 이후 감속과 연결된다. false 후1.5초 내 재입력 펄스는 같은 에피소드로 합친다.
4. 첫 임계 초과에서 카드1개를 만들고, 더 큰 감속이 관측되면 **같은 ID를 갱신**한다. `mergeReviewCards()`를 저장소/테스트에서 사용하며 피드백을 보존한다. 6초 중복 억제도 유지한다.
5. 새 브레이크와 연결되지 않은 감속, 창 밖 감속, 기준 미만 감속은 급제동이 아니다. 브레이크 상태가 오류면 창을 비운다. 기록 삭제/종료 시 창·감속 기준점을 버린다.

기존 요약 파일 스키마는 그대로다. 새 창·속도 기준점·처리 시계·GET tracker는 일시 메모리이며 파일에는 최종 카드·최대 감속·요약만 저장한다. 동일 카드의 최대치 갱신 때문에 `recap.card` 로그가 같은 ID로 여러 번 나올 수 있지만 화면은1개다.

## 리플레이

`scripts/recap-replay.js`는 5b HTTP/페이지 fetch 격리를 유지한다. 페이지 자체의 POST뿐 아니라 physicsTick이 사용하는 PUT도 controls/gear/turn-signal 세 경로에서 격리한다. 자체 요청은 캡처한 원본 fetch로 보낸다. GET/heartbeat는 통과한다. 종료/오류/중지 시 서버 P·속도0 확인, 페이지 로컬 모델 동기화, fetch 원복을 수행한다. 예전 수동 fetch 패치가 남지 않게 **새로고침한 Console**에 붙여넣는다.

모든 controls POST에 현재 목표 speed(kph)·steering·brake를 포함한다. A JSON의 각 스텝에도 speedKph를 명시했다. turn-signal endpoint는 `/vehicle/turn-signal?mode=BlinkLeft|BlinkRight|Off`다. 속도는 `/vehicle/controls?speed=<kph>`, 기어는 `/vehicle/gear?gear=Drive|Park`다. 차량 속도용으로 Cruise Target Speed를 쓰지 않는다.

브레이크 단계는 speed=0/brake=100을 약200ms마다 재전송하며, 서버 정지 확인과 **최소1.8초 유지**를 모두 충족할 때까지 해제하지 않는다. 실제 SDK가 계속 true를 유지한다는 보장은 아니므로 엔진의 펄스 창도 함께 적용한다. 속도 도달 대기만큼 이후 타임라인을 이동하여 조향350ms·사전2초·늦음600ms 간격을 보존한다. Console 로그의 wallMs는 앱 카드 wallStartMs와 대응할 수 있다.

| A 기본 시간(가감속 대기 제외) | 입력 |
|---|---|
| 0~6초 | P, Off, 중립, 브레이크0 → D·60kph 도달 대기 |
| 7초/9초 | LEFT → 2초 뒤 `8,15,12,4,-6,-10,-4,0`, 350ms |
| 12.5초/17초 | Off → 충분한 사전 관측 뒤 `-8,-14,-10,-3,6,11,5,0`, 350ms |
| 24초/24.6초 | `7,13` 먼저 → LEFT → `9,2,-7,-10,-3,0` |
| 30~32초 | speed0/brake100, 정지·최소 유지 대기 → brake0 |
| 35~42초 | 12kph, RIGHT, `20,50,80,90×4,70,40,10,0`, 400ms → Off |
| 45~47초 | 정지 확인 → P |

## Claude 실행 순서

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
if ($info -notmatch "versionCode='7'" -or $info -notmatch "versionName='0.5.2'") { throw 'Not 0.5.2 APK.' }
& $adb devices -l
$serial='emulator-5554' # 실제 devices 결과로 정한다.
& $adb -s $serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'Install failed.' }
& $adb -s $serial forward tcp:8088 tcp:8088
& $adb -s $serial shell am start -n lab.pleos.observe/.MainActivity
# 별도 터미널: UI PID만으로 필터링하지 않는다.
& $adb -s $serial logcat -v threadtime ObserveLab:I '*:S'
```

1. Console을 새로고침하고 P·속도0·Off·조향0·브레이크0을 확인한다. 앱 P에서 중지 완료 → 기록 삭제 → **속도 미수신 시연 OFF** → 기록 시작.
2. UI **초기 신호 준비됨**과 `turnSignal/brakePedal/steeringAngle` 각각의 `source=get initial=true`, `initialGet.ready`를 확인한다. 시작 시 Off/false/0 상태를 실제로 받았는지 로그에서 확인한다. 미수신이면 다음 시험을 진행해 성공으로 보고하지 않는다.
3. 준비 뒤3초 대기. 새 JS 붙여넣기 → `await driveRecapReplay.runFile()` → A 선택. 기본47초+가감속 대기. 실행 중 Console 직접 조작 금지.
4. P 복귀 뒤 약2초 더 기다려 오늘 복기 보기. **표시4개 = 좋아요1 + 깜빡이 없이1 + 늦은 깜빡이1 + 급제동1, 애매함0**. 정상 회전은 숨김. 입력/신선도가 실제 실패하면 애매함을 지우거나 임계값을 낮춰 개수를 맞추지 않는다.
5. 목록은 최신순이다. HH:mm:ss·`drive.recap.segment.start`·`drive.recap.card`의 id/wallStartMs/startMs/endMs/signalTiming/lateMs와 Console wallMs를 대응한다. 정상 차선은 BEFORE/lateMs=null, 미사용은 NONE, 늦음은 DURING/약600ms여야 한다.
6. 급제동은 같은 ID의 최종 최대 감속을 확인한다. 페달41ms 뒤 감속도 창 안이면 포함돼야 한다. 서버가 실제3m/s² 미만으로 감속했다면 급제동0은 올바르므로 속도/페달 시각을 함께 보고한다.
7. P 중지 → 다시 기록 시작. GET3개가 새로 찍히고 이전 LEFT/브레이크/창을 물려받지 않는지 확인한다. 필요 시 P 삭제 후 A 재실행. 별도 B 회귀는 정상 회전 숨김/굽은 길1개다.

## 오프라인 검증·변경 파일

- JVM **137/137**. 기존119 + 신규18. 실측 파형3종/GET OFF 유지·미수신/오래된 GET/2ms 순서 역전/3초·장시간 ON_CHANGE 유지/41ms 펄스/전후 창 밖 제외/20ms 속도/최대 갱신·피드백/재입력 펄스를 포함한다. `.local/w5c-jvm-tests.txt`.
- Node **17/17**. 기존14 + 신규3. 모든 controls에 속도 포함, 실제350ms/2초/600ms 간격, brake 재전송·최소 유지도 확인한다. `.local/w5c-replay-tests.txt`. 가상 HTTP/시계이며 실제 서버 성공 증거는 아니다.
- 앱+logic 직접 Kotlin 소스 타입 검사 통과. `.local/w5c-kotlin-types.txt`, `.local/w5c-compile/source-types.jar`. 설치된 API36·Vehicle2.3.2·임시 BuildConfig.DEBUG stub 사용. Gradle/APK 빌드 증거가 아니다.
- `git diff --check` 통과. 게임/탐침/주행 상태 OR/shouldLock/NaviHelper는 변경하지 않았다. 초기 GET 재시도·실제 콜백·UI·파일 upsert·새 리플레이의 AVD 검증은 Claude 인계다.

변경 파일:

- `logic/.../recap/InitialReadTracker.kt`(신규), `RecapEngine.kt`, `RecapModels.kt`: 스냅숏 경합·시각 보정·창/최대 감속·동일 카드 병합.
- `logic/src/test/.../recap/RecapMeasuredTimelineTest.kt`(신규), `RecapEngineTest.kt`, `RecapScenarioTest.kt`: 실측 재현·실제 역전/짧은 처리 역전 구분·JSON fixture 병합.
- `DriveSignalSubscriptions.kt`, `RecapRecordingSession.kt`: GET 재시도/준비 상태·main 직렬 처리·구간 로그·삭제 시 창 초기화.
- `RecapRepository.kt`, `RecapProvider.kt`, `MainActivity.kt`: 최대치 upsert/피드백 보존·초기 상태 표시·초 단위 시각.
- `app/build.gradle.kts`: 7 / 0.5.2.
- `scripts/recap-replay.js`, `recap-scenario-a.json`, `test-recap-replay.mjs`: 실측 파형·속도 유지·브레이크 유지·모의 시험.
- `README.md`, 이 문서, `docs/WEEK5_RECAP.md`, `docs/WEEK5B_DRIVING_STATE.md`, `docs/WORK-CHECKLIST.md`: 최신 절차와 이전 근거 구분.

이 폴더 밖 쓰기·Gradle 실행·AVD 제어·commit은 하지 않았다. NaviHelper 실연결 미완료/조향 부호 미확정 상태는 이전과 같다.

## Claude AVD 실측 (2026-10-23, 0.5.2)
- 기록 시작 → "초기 신호 준비됨" → Console에 recap-replay.js + 시나리오 A(로컬 CORS 서빙 `.local/cors_serve.py` 8799에서 fetch·eval) → completed.
- 복기 결과: **좋아요 1(깜빡이 사용 차선 변경) + 깜빡이 없이 차선 변경 1 + 늦은 깜빡이 1(시작 후 523ms) + 급제동 1, 애매함 0**, 정상 저속 회전 숨김. 전부 60km/h 구간. 캡처 `docs/evidence/w3/recap_04.png`.
- 주의: 급제동 감속 77.9m/s²는 Console 서버가 speed=0을 즉시 반영해 생긴 비현실값. 시연 시나리오는 감속을 단계적으로(예: 60→40→20→0) 주도록 다듬을 것.

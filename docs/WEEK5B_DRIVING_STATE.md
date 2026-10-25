# 5b · 주행 상태 판정 수정 · 0.5.1

> 0.5.1의 주행 판정 정상 전환은 Claude AVD 실측으로 확인됐다. 초기값/깜빡이 시각/급제동 누락과 최신 0.5.2 실행법은 [5c 수정 보고서](WEEK5C_RULE_REFINEMENT.md)를 따른다. 아래는 5b 당시 기록이다.

2026-10-23. versionCode **6**, versionName **0.5.1**. Gradle 빌드·AVD 시험은 Claude가 한다. 아래 구현/오프라인 검증을 0.5.1 AVD 실측 통과로 해석하지 않는다.

## 원인·근거

Claude가 보고한 0.5.0 실측: Console의 Gear D는 vendor `VENDOR_GEAR_SELECTION`을 바꾸지만 AAOS CURRENT_GEAR는 계속 PARK(4). D에서 UX requiresDO=true/active=80, P에서 false/0으로 바뀌며 서비스 PID는 유지된다. 속도/조향/깜빡이/브레이크 수신은 계속됐다. 이는 사용자 제공 실측이며 이번 세션에서 재실행한 결과가 아니다.

[AAOS VehiclePropertyIds](https://developer.android.com/reference/android/car/VehiclePropertyIds#GEAR_SELECTION)는 GEAR_SELECTION을 사용자 선택값으로, CURRENT_GEAR를 실제 변속단으로 구분한다. 두 값은 다를 수 있다. 같은 공식 문서의 [속도 정의](https://developer.android.com/reference/android/car/VehiclePropertyIds#PERF_VEHICLE_SPEED)는 signed **m/s**다. 설치된 `android.car.jar`를 프로젝트 안에 읽기 복사하여 javap으로 상수를 재확인했다: `.local/w5b-aaos-constants.txt`.

| 프로퍼티 | ID | 앱 로그 이름 |
|---|---:|---|
| GEAR_SELECTION | 289408000 | drive.gearSelection |
| CURRENT_GEAR | 289408001 | drive.gear |
| PERF_VEHICLE_SPEED | 291504647 | drive.speed |

기어 값 PARK=4, DRIVE=8, UNKNOWN=0, NEUTRAL=1, REVERSE=2. 기존 `android.car.permission.CAR_POWERTRAIN`으로 기어 두 개를 읽는다. 추가 권한/DO/서비스 타입 변경 없음. Vehicle SDK의 조향/깜빡이/브레이크 시그니처는 변경하지 않았다.

## 결합 판정·안전·갱신

`DrivingSignals` → `drivingDecision()`은 순수 함수다.

1. 수신한 **GEAR_SELECTION**을 우선한다. 미수신/에러이면 CURRENT_GEAR를 fallback으로 사용한다. 선택값이 P인데 CURRENT_GEAR만 D인 경우 선택값을 우선한다.
2. 우선 기어가 비P, UX **requiresDO=true**, 신선한 유효 속도 **절댓값>0** 중 하나라도면 기록 상태 DRIVE. 근거는 이 순서로 모두 남긴다. R/N도 화면/정차 판단에서 비P로 취급한다.
3. 우선 기어 P + requiresDO=false + 신선한 유효 속도0이 **모두** 확인돼야 PARK. 그 외는 UNKNOWN이며 정차로 꾸미지 않는다.
4. 속도 신선도는 기존 3초. ON_CHANGE 기어/UX는 변경·에러·연결 해제까지 유지한다. 연결 해제는 세 근거를 모두 비운다.
5. 서비스의 중지/삭제/알림도 결합 상태를 사용한다. 주행 후 UNKNOWN을 잠시 거쳤어도 다음 PARK 확정 시 복기 준비 알림을 만든다.

로그 예: `event=drive.recap.motion.ux_requires_do.speed value=DRIVE ... sources=UX_REQUIRES_DO,SPEED selected=PARK current=PARK ... speedMps=15.0 speedKph=54.0`. 근거가 바뀔 때 기록하며 같은 값의 반복 로그는 만들지 않는다. Release는 기존 정책대로 수치를 지우되 이벤트 이름의 판단 근거는 유지한다.

기록 상태와 화면 허용은 별개다. `CarSafetyClient`도 GEAR_SELECTION 우선/현재 기어 fallback을 사용하지만 **shouldLock은 그대로**다. P·속도0·UX 해제를 확인하기 전엔 화면을 가린다. 기록 상태 완화가 주행 중 UI를 열지 않는다.

주행 시간에는 결합 DRIVE 상태의 정차 대기도 포함한다. 긴 수신 공백은 기존대로 누적하지 않는다. 실제 속도0은 미수신으로 바꾸지 않으며 정지 상태 조향은 여전히 구간 시작에서 제외한다. 차선 변경 속도 기준은 **30/3.6 m/s**, 회전은 **25/3.6 m/s**, 카드만 **×3.6 km/h**다. 급제동은 **m/s²** 그대로다. 속도0을 받는 마지막 감속도 PARK 전환 전에 평가한다.

메인은 정차 상태에서 기존 1초 provider 조회를 유지한다. 일별 메모리 집계가 조회되고 인코딩된 drivingMs/카드 목록을 포함한 fingerprint가 바뀌면 다시 그린다. D에서는 조회/본문을 없애고, P 복귀 후 즉시 재조회한다. 목록·후보 수·P 알림은 동일한 `visibleReviewCards()`를 사용한다. 확실한 TURN은 저장 요약에는 남지만 표시에서 제외한다. LANE_SIGNALLED는 “좋아요”, UNSURE는 좋아요로 칭찬하지 않는다. 정상 회전도 불확실하면 숨기지 않는다.

## HTTP 리플레이

확인 범위: 이번 세션에서는 localhost 카탈로그 GET만 읽었다(`.local/w5b-console-catalog.json`). **POST/기어/속도 변경은 실행하지 않았다.** speed 경로의 동작은 Claude 실측에 근거한다.

| 입력 | POST URL | 값 단위 |
|---|---|---|
| 속도·조향·브레이크 | `/vehicle/controls?speed=54&steering=12&brake=0` | kph / Console 원시 deg / 0~100 |
| 기어 | `/vehicle/gear?gear=Drive` 또는 `Park` | 문자열 |
| 깜빡이 | `/vehicle/turn-signal?mode=BlinkLeft` / `BlinkRight` / `Off` | 카탈로그 control.field=mode의 enum |

카탈로그 feature id는 `turn-signal-state`, domain_label은 Turn Signal이다. 실행 전에 GET으로 endpoint/field/options를 검사하며 달라지면 쓰기 없이 거부한다. 카드가 안 보이거나 검색 중이어도 동작한다. Cruise Target Speed/다른 신호/AI/위치 API는 사용하지 않는다.

`waitForSpeedKph` 단계는 GET `/vehicle/state`의 `controls.speed_kph`가 범위에 2회 연속 도달할 때까지 기다린다. 대기만큼 이후 시간을 미뤄 왕복 조향/늦은 깜빡이의 상대 간격을 유지한다. 이 서버 상태는 SDK 수신 증거를 대신하지 않는다. 미수신·timeout·HTTP 오류·타임라인 500ms 초과 지연이면 중단하고 복원한다. 브레이크 대기 중 관측 최대 감속도 Console에 출력한다.

읽어 본 Console HTML의 `physicsTick`은 약 140ms마다 자체 PUT을 보낼 수 있다. 리플레이는 실행 중 같은 origin의 controls/gear/turn-signal에 대한 페이지 자체 쓰기를 HTTP 423 응답으로 격리하고, 캡처한 fetch로 시나리오만 전송한다. GET/heartbeat/그 외 경로는 그대로 통과한다. 종료/실패/중지 시 중립·속도0·브레이크0·Off·P를 복원하고 서버 P·속도0을 확인한 뒤 UI의 선택적 `syncControls/renderGear/renderAll`로 로컬 물리 상태를 맞추고 fetch를 원복한다. 리플레이 동안 직접 Console 페달/기어를 조작하지 않는다. 복원 실패도 성공으로 보고하지 않는다.

기본 **속도 미수신 시연은 OFF**다. 기존 Debug 체크박스 자체는 비상 진단용으로 남겼으며 기본 false를 유지했다. 정상 시나리오에서는 켜지 않는다.

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
if ($info -notmatch "versionCode='6'" -or $info -notmatch "versionName='0.5.1'") { throw 'Not 0.5.1 APK.' }
& $adb devices -l
$serial='emulator-5554' # 실제 devices 결과로 선택한다.
& $adb -s $serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'Install failed.' }
& $adb -s $serial forward tcp:8088 tcp:8088
& $adb -s $serial shell am start -n lab.pleos.observe/.MainActivity
```

1. P·속도0·UX 해제. **기록 중지 → 기록 삭제 → 속도 미수신 시연 OFF → 기록 시작**. 기존 0.5.0 요약이 남아 개수가 섞이지 않게 한다.
2. 별도 터미널 `adb -s emulator-5554 logcat -v threadtime ObserveLab:I '*:S'`. UI PID로만 필터링하지 않는다.
3. Console DevTools에 `scripts/recap-replay.js` 전체 붙여넣기 → `await driveRecapReplay.runFile()` → `recap-scenario-a.json` 선택. 기본 타임라인 38초 + 가감속 대기. 중단은 `await driveRecapReplay.stop()`.
4. D에서 UX 차단과 서비스 수신 유지, `drive.gearSelection` 수신 여부, `drive.recap.motion.*` 판단 근거 확인. GEAR_SELECTION도 P에 고정되거나 없더라도 UX/SPEED로 DRIVE여야 한다. 54kph 입력 시 SDK 약15m/s·카드 약54km/h 확인.
5. P 복귀 알림 → **오늘 복기 보기**. **좋아요 1 + 미사용 1 + 늦은 깜빡이 1 + 급제동 1 = 표시 4개**. 저속 큰 회전은 TURN 요약으로 분류되고 목록/후보 수에는 없어야 한다. 늦은 깜빡이는 조향 시작 약400ms 뒤다. 조향 좌/우는 여전히 표시하지 않는다.
6. **급제동은 브레이크 true와 실제 감소율≥3m/s²를 수신했을 때만** 기대한다. 없으면 drive.speed/drive.brakePedal 시각과 값, Console 감속 관측을 비교한다. 서버가 완만하게 가감속했다면 카드가 없는 것이 맞다. 임계값을 낮추거나 속도를 만들어 개수를 맞추지 않는다. 다른 카드가 애매함이면 신선도·깜빡이 사전 관측·수신 공백을 확인한다.
7. 메인에서 주행 구간 N초·후보4개가 갱신되는지 확인. D에서는 화면을 보여 주지 않으므로 P 복귀 시 갱신 확인으로 충분하다. 다시 열어도 요약/피드백이 유지돼야 한다.
8. P 중지·삭제·기록 시작 후 B 실행. 정상 저속 회전은 숨김, 굽은 길 후보 **1개**. 정차 조향은 새 카드를 만들지 않아야 한다.

## 검증과 변경 파일

- JVM **119/119**: 기존 107개 유지/명세 변경에 맞춘 기대값 갱신 + 결합 상태/단위 회귀 12개. `.local/w5b-jvm-tests.txt`. 실제 JSON A/B를 사용하는 순수 엔진 테스트도 포함한다. A 정상1/미사용1/늦음1/감속1/회전1을 분리하고 표시4개를 확인했다.
- 기존 D+속도0 테스트는 조향 카드가 없다는 검증을 유지하고 시간만 D 대기를 포함하도록 변경했다. 새 명세의 주행 상태 OR 조건에 따른 의미 변경이다.
- HTTP 도구 모의 테스트 **14/14**: 카탈로그 계약·kph 전송·가감속 대기·시간 간격·HTTP 오류·중지·P 복원·페이지 쓰기 격리/로컬 모델 동기화·fetch 원복. `.local/w5b-replay-tests.txt`. 실제 서버/AVD 통과 증거가 아니다.
- 전체 앱+logic 직접 Kotlin 소스 타입 검사: `.local/w5b-kotlin-types.txt`, `.local/w5b-compile/source-types.jar`. 설치된 API36/Vehicle2.3.2와 임시 BuildConfig.DEBUG stub 사용. Gradle/리소스 링크/APK 생성 증거가 아니다.
- 실기 미검증: 0.5.1 구독값·서비스 전환·알림·메인 갱신·HTTP 시나리오·실제 감속. Claude가 확인한다. 조향 부호 미확정/NaviHelper 실연결 미완료는 기존과 같다.

변경 파일:

- `logic/.../recap/DrivingState.kt`: 주행 결합·m/s↔km/h 변환·카드 표시 필터.
- `RecapEngine.kt`, `RecapText.kt`: 시간 의미·단위 helper·좋아요 표시.
- `logic/src/test/.../recap/DrivingStateTest.kt`, `RecapEngineTest.kt`, `RecapScenarioTest.kt`: 회귀/시나리오 기대값.
- `DriveSignalSubscriptions.kt`: GEAR_SELECTION·typed UX 구독, 등록 거부 처리.
- `RecapRecordingSession.kt`: 결합 상태·공백/오류 초기화·판단 근거 로그·P 알림.
- `CarSafetyClient.kt`: UI 기어 선택값 우선, 기존 잠금 함수 유지.
- `RecapProvider.kt`, `DriveRecorderService.kt`: 표시 목록·개수·알림 일치.
- `app/build.gradle.kts`: 6 / 0.5.1.
- `scripts/recap-replay.js`, `recap-scenario-a.json`, `recap-scenario-b.json`, `test-recap-replay.mjs`: HTTP·속도 대기·페이지 간섭 격리·시나리오·모의 시험.
- `README.md`, `docs/WEEK5_RECAP.md`, 이 문서, `docs/WORK-CHECKLIST.md`: 최신 절차와 역사적 근거 구분.

원시 기록 포맷·보관/삭제 정책·키·게임/탐침·AI/Navi 호출은 바꾸지 않았다. Git commit·배포 없음. 이 폴더 밖에는 쓰지 않았다.

# 3주차 신호 탐침 · 2026-10-22

## 결과와 범위

versionCode **3**, versionName **0.3.0**. 제목 길게 누르기 → 기존 관찰 진단 → **신호 탐침**으로 들어간다. 게임 문제·출제·잠금 판정은 바꾸지 않았다. 추가 신호는 탐침 화면에서만 읽고 게임의 스냅숏에 넣지 않는다.

탐침은 capability, GET, notify를 별도로 보여 준다. 숫자는 `toString()`, Boolean은 `true/false`, enum은 `.name` 그대로다. 거리·SOC·밝기·문 위치의 물리 단위는 해당 AAR 반환 타입/모델에서 제공하지 않으므로 km, %, 0~100 등의 단위를 가정하지 않는다. 색도 Int 그대로이며 색상 변환하지 않는다. Light 색상 listener의 매개변수 이름은 AAR에서 `argbColor`지만 값을 해독하지 않는다.

**SDK 메서드 존재는 AVD 실수신 증거가 아니다.** 이 세션에서 AVD 설치·Console 변경·콜백 수신은 검증하지 않았다. Claude가 빌드·설치·아래 시험표를 실행한다.

## AAR 근거와 재현

직접 열어 본 파일:

```text
.gradle-user-home/caches/modules-2/files-2.1/ai.pleos.playground/Vehicle/2.3.2/
  1c5535aa87c279e1cf36fa69452d3f29da562139/Vehicle-2.3.2.aar
SHA-256: e11fdfe498cad3ecc0d5a2a6992b0c4a4d135195f1455018127bc069fd5ef937
```

`classes.jar`를 프로젝트 `.local/w3-aar/`에 추출하고 Android Studio JBR의 `javap -public`, `javap -p -v`로 인터페이스·listener·영역 상수·권한 어노테이션·Kotlin metadata를 확인했다. 재현 명령:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\inspect-probe-aar.ps1
Get-Content -Encoding UTF8 .local\w3-aar\api_Odometer.public.txt
Get-Content -Encoding UTF8 .local\w3-aar\api_EvBattery.public.txt
Get-Content -Encoding UTF8 .local\w3-aar\api_Wiper.public.txt
Select-String -Path .local\w3-aar\api_*.verbose.txt -Pattern 'RequiresPermission' -Context 0,3
```

이 스크립트는 캐시를 읽고 `.local/w3-aar/`에만 결과를 쓴다. SDK setter는 호출하지 않는다. 문서의 경로보다 **이 AAR의 클래스와 실제 Kotlin 컴파일 결과**를 시그니처 근거로 삼았다. 기존 공개 문서 위치: [Vehicle 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro), [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission), [Virtual Console](https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide). 이번 탐침에서는 웹 상세 페이지의 내용으로 메서드를 추정하지 않았다.

## 실제 메서드 시그니처

도메인 인터페이스의 공통 패키지: `ai.pleos.playground.vehicle.api`.

- 진입: `ai.pleos.playground.vehicle.Vehicle.getOdometer(): Odometer`, `getEvBattery(): EvBattery`, `getLight(): Light`, `getDoor(): Door`, `getSeat(): Seat`, `getWiper(): Wiper`.
- 영역: `ai.pleos.playground.vehicle.api.model.area.{SeatArea,DoorArea,WindowArea}`.
- capability: `ai.pleos.playground.vehicle.api.model.capability.{CapabilityConfig,AreaBounds}`.
- enum: `ai.pleos.playground.vehicle.constant.control.{LightState,WiperSwitch,WiperState}`.
- listener: `ai.pleos.playground.vehicle.listener`.

다음은 `javap` JVM 제네릭 시그니처의 약식이다. 모든 메서드의 반환은 `void`(Kotlin `Unit`). `F1<T>`는 `kotlin.jvm.functions.Function1<? super T,kotlin.Unit>`, `F2<A,B>`는 `Function2<? super A,? super B,kotlin.Unit>`이다. 숫자·Boolean 콜백 타입은 JVM boxed `java.lang.Float`, `Integer`, `Boolean`이다. `E`는 `F1<java.lang.Exception>`, `C`는 `F1<CapabilityConfig>`. 아래 `Int`는 JVM `Integer`를 뜻한다. 실제 구현에서는 SDK의 Kotlin 타입을 그대로 사용하고 null도 수신 값으로 보존한다.

```text
Odometer
  checkCurrentDistanceDrivenCapabilities(C, E)
  getCurrentDistanceDriven(F1<Float>, E)
  registerDistanceDriven(DistanceDrivenListener)
  unregisterDistanceDriven(DistanceDrivenListener)
  getRangeRemainingDistance(F1<Float>, E)
  registerRangeRemainingListener(RangeRemainingListener)
  unregisterRangeRemainingListener(RangeRemainingListener)
  Remaining Range capability: SDK에 없음

EvBattery
  checkEvBatteryStateOfChargeCapabilities(C, E)
  getEvBatteryStateOfCharge(F1<Int>, E)
  registerEvBatteryStateOfCharge(EvBatteryStateOfChargeListener)
  unregisterEvBatteryStateOfCharge(EvBatteryStateOfChargeListener)
  checkEvChargeSwitchCapabilities(C, E)
  getEvChargeSwitchState(F1<Boolean>, E)
  Charge Switch register/unregister: SDK에 없음

Light
  checkAmbientLightBrightnessColorCapabilities(F2<CapabilityConfig,List<SeatArea>>, E)
  getAmbientLightBrightnessColor(SeatArea, F1<Int>, E)
  registerAmbientLightBrightnessColorListener(SeatArea, AmbientLightBrightnessColorListener)
  unregisterAmbientLightBrightnessColorListener(SeatArea, AmbientLightBrightnessColorListener)
  checkAmbientLightBrightnessCapabilities(F2<CapabilityConfig,Map<SeatArea,AreaBounds<Int>>>, E)
  getAmbientLightBrightness(SeatArea, F1<Int>, E)
  registerAmbientLightBrightnessListener(SeatArea, AmbientLightBrightnessListener)
  unregisterAmbientLightBrightnessListener(SeatArea, AmbientLightBrightnessListener)
  checkAmbientLightStateCapabilities(F2<CapabilityConfig,Map<SeatArea,List<LightState>>>, E)
  getAmbientLightState(SeatArea, F1<LightState>, E)
  registerAmbientLightStateListener(SeatArea, LightStateListener)
  unregisterAmbientLightStateListener(SeatArea, LightStateListener)

Door
  checkDoorPositionCapabilities(F2<CapabilityConfig,Map<DoorArea,AreaBounds<Int>>>, E)
  getCurrentDoorPosition(DoorArea, F1<Int>, E)
  registerDoorPosition(DoorArea, DoorPositionListener)
  unregisterDoorPosition(DoorArea, DoorPositionListener)

Seat
  checkSeatOccupancyCapabilities(F2<CapabilityConfig,List<SeatArea>>, E)
  getCurrentSeatOccupancy(SeatArea, F1<Boolean>, E)
  registerSeatOccupancy(SeatArea, SeatOccupancyListener)
  unregisterSeatOccupancy(SeatArea, SeatOccupancyListener)

Wiper
  checkWiperSwitchCapabilities(F2<CapabilityConfig,Map<WindowArea,List<WiperSwitch>>>, E)
  getCurrentWiperSwitch(WindowArea, F1<WiperSwitch>, E)
  Wiper Switch register/unregister: SDK에 없음
  checkWiperStateCapabilities(F2<CapabilityConfig,Map<WindowArea,List<WiperState>>>, E)
  getCurrentWiperState(WindowArea, F1<WiperState>, E)
  registerWiperState(WindowArea, WiperStateListener)
  unregisterWiperState(WindowArea, WiperStateListener)
```

Door/Seat/Wiper의 구형 F1 capability 오버로드 대신 위의 F2 오버로드를 사용한다. 제네릭 enum List에 있는 JVM `? extends` 공변 표기는 위 약식에서 생략했다. `Odometer.getRangeRemainingDistance` 및 register/unregister는 **존재하지만 deprecated**다. AAR의 메시지는 `This API is deprecated, use the Google RANGE_REMAINING property.`이다. 요청한 Pleos SDK 경로의 수신 여부를 확인하기 위해 그대로 사용했으며 다른 AAOS 신호로 대체하지 않았다.

listener는 모두 `VehicleListener.onFailed(e: Exception)`을 상속한다. 아래 값 매개변수의 nullable 어노테이션과 Kotlin 타입을 확인했다.

| Listener | 실제 콜백 |
|---|---|
| DistanceDrivenListener | `onDistanceDrivenUpdated(value: Float?)` |
| RangeRemainingListener | `onRangeRemainingChanged(rangeRemaining: Float?)` |
| EvBatteryStateOfChargeListener | `onEvBatteryStateOfChargeUpdated(value: Int?)` |
| AmbientLightBrightnessColorListener | `onBrightnessColorUpdated(argbColor: Int?)` |
| AmbientLightBrightnessListener | `onBrightnessUpdated(brightness: Int?)` |
| LightStateListener | `onLightStateUpdated(state: LightState?)` |
| DoorPositionListener | **`onPositionUpdated(pos: Int?)`** |
| SeatOccupancyListener | `onSeatOccupancyUpdated(occupancy: Boolean?)` |
| WiperStateListener | `onWiperStateUpdated(state: WiperState?)` |

Charge Switch와 Wiper Switch의 변경을 다른 listener로 대신해서 구독한 것처럼 표시하지 않는다.

## 영역·capability 처리

`SeatArea(vararg SeatValue)`, `DoorArea(vararg DoorValue)`, `WindowArea(vararg WindowValue)`의 `values`를 원래 enum 이름으로 표시한다. 상수 패키지는 `ai.pleos.playground.vehicle.constant.area`다.

| 영역 타입 | AAR 상수 / 탐침 대상 |
|---|---|
| SeatValue | `ROW_1_LEFT/CENTER/RIGHT`, `ROW_2_LEFT/CENTER/RIGHT`, `ROW_3_LEFT/CENTER/RIGHT` 총 9개. Ambient·Occupancy가 사용하는 SeatArea. |
| DoorValue | `ROW_1_LEFT/RIGHT`, `ROW_2_LEFT/RIGHT`, `ROW_3_LEFT/RIGHT` 총 6개를 조회 대상으로 삼는다. AAR의 `HOOD`, `REAR`도 cap 원시 결과에는 남지만 좌석 영역 요청에 따라 조회하지 않는다. |
| WindowValue | 기본 행은 `FRONT_WINDSHIELD`, `REAR_WINDSHIELD`. AAR에는 `ROW_1_LEFT/RIGHT`, `ROW_2_LEFT/RIGHT`, `ROOF_TOP_1/2`도 있다. Wiper cap에 다른 영역이 반환되면 그 정확한 영역도 표시·조회한다. |

cap의 `accessType`은 `NONE`, `READ`, `WRITE`, `READ_WRITE` 그대로 표시한다. `AreaBounds<Int>.minValue/maxValue`, 영역 목록, 가능한 enum 목록도 그대로 표시한다. READ/READ_WRITE에서만 해당 지원 영역을 조회·구독한다. cap에 없는 기본 영역은 “미지원(cap 영역에 없음 또는 READ 아님)”이다. 복합 영역을 반환하면 나누거나 좌석에 복제하지 않고 정확한 복합 키를 추가해 호출한다.

cap 자체가 실패하면 실패를 표시한 상태에서 위 기본 영역의 GET/구독을 시도한다. cap 실패만으로 GET도 불가능하다고 단정하지 않는다. cap 콜백이 오지 않으면 대기/미수신을 유지한다. 타임아웃을 미지원으로 꾸미지 않는다. **탐침 다시 시작**으로 capability도 재시도할 수 있다.

각 행에는 GET 값·그 수신 시각과 notify 값·그 수신 시각을 따로 남긴다. 등록 호출이 반환한 것과 실제 notify 수신을 구분한다. `null` 콜백, 오류 콜백, 아직 미수신, SDK 메서드 없음도 구분한다. 늦게 도착한 이전 GET은 `status=stale`로 로그만 남기고 최신 GET을 덮지 않는다.

## 추가 권한

각 인터페이스의 해당 메서드에 있는 `androidx.annotation.RequiresPermission(value=...)`를 `javap -v`로 직접 확인했다.

| 도메인 | 읽기 권한 | 변경 |
|---|---|---|
| Odometer (Remaining Range 포함) | `pleos.car.permission.CAR_DRIVE` | 추가 |
| EvBattery | `pleos.car.permission.CAR_ENERGY` | 추가 |
| Door | `pleos.car.permission.CAR_DOORS` | 추가 |
| Seat | `pleos.car.permission.CAR_SEATS` | 추가 |
| Light | `pleos.car.permission.CAR_LIGHTS` | 기존 유지 |
| Wiper | `pleos.car.permission.CAR_WINDOWS` | 기존 유지 |

제어 권한은 추가하지 않았다. 기존 AAOS 기어·속도 권한과 `android.car required="false"`를 유지했다. 인증 키·계정·토큰은 추가하지 않았다. 추가 권한의 이 AVD 부여 여부는 Claude가 설치 후 확인한다.

## 잠금·수명·로그

`CarSafetyClient`, `Observation.kt`, `LabSession.kt`, `Quiz.kt` 및 기존 게임 테스트는 수정하지 않았다. 기존 진단 상태 안에서만 로컬 화면을 전환한다. 같은 Vehicle 세션을 빌려 쓰며 탐침에서 SDK initialize/release를 호출하지 않는다.

탐침 진입 때만 capability/GET/구독을 시작한다. 진단 복귀·처음으로·잠금·백그라운드 이동 시 동일 영역/listener로 unregister하고 화면 데이터와 pending 응답을 버린다. 원래 게임의 창문·독서등 구독은 해제하지 않는다. 잠금 해제 후 시작 화면으로 돌아가는 기존 규칙을 유지한다. DO 선언도 추가하지 않았다.

태그 `ObserveLab`:

```text
event=probe.distanceDriven.cap value=accessType=READ area=GLOBAL receivedAt=... status=received
event=probe.distanceDriven.get value=1234.125 area=GLOBAL receivedAt=... status=received
event=probe.doorPosition.notify value=1 area=ROW_1_LEFT receivedAt=... status=received
event=probe.chargeSwitch.notify value=SDK에 없음 area=GLOBAL receivedAt=... status=sdk_absent
```

이는 **형식 예시**이며 실기 수신 로그가 아니다. `sdk_absent`의 시각은 기능 부재를 기록한 시각으로, 실제 notify 수신 시각이 아니다. 화면에는 SDK 부재에 대한 가짜 콜백 시각을 만들지 않는다.

`probe.*` 원시 진단 로그는 **Debug에서** 기록한다. 앞선 “상태 값은 Debug에서만” 규칙을 유지해 Release는 이벤트 이름만 기록한다. 위의 원시 로그 계약과 아래 실기 시험은 Debug APK 기준이다. 탐침 활성 중에만 작동한다. 기존 게임의 Release 값 필터링도 그대로다. 게임/탐침 모두 파일·DB·SharedPreferences에 이력을 쓰지 않으며 logcat 기록은 Android 로그 버퍼에 남는다. 개발자가 필요할 때 프로젝트 안에 수동 수집한다.

## 검증 결과

- 기존 41개 + 탐침 7개 = JVM **48/48 통과**, 실패·오류 0. `logic/build/test-results/test/`.
- `:app:compileDebugKotlin`, `:app:compileReleaseKotlin` 통과. 실제 Vehicle 2.3.2를 대상으로 새 메서드·영역·listener 타입을 컴파일했다.
- 새 테스트: 숫자 무변환, Boolean/enum 원문, null와 미수신 구별, GET/notify 증거 분리, 로그 형식, Release 값 필터링, 없는 SDK 구독을 수신으로 꾸미지 않음.
- APK 전체 빌드·설치·실제 화면/구독 수신은 **Claude 인계**. 앞 주차의 APK나 시험 통과를 이번 탐침의 증거로 간주하지 않는다.
- 로컬 재현 출력: `.local/w3-compile-and-tests.txt`, `.local/w3-aar/*.public.txt`, `*.verbose.txt`.

## Claude 빌드·설치

```powershell
Set-Location D:\Projects\pleos-observe-lab
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
.\gradlew.bat :logic:test :app:assembleDebug :app:assembleRelease --offline --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Build failed. Do not install an older APK.' }
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$aapt2 = Join-Path $env:ANDROID_HOME 'build-tools\35.0.0\aapt2.exe'
$apk = '.\app\build\outputs\apk\debug\app-debug.apk'
$packageInfo = (& $aapt2 dump badging $apk | Select-Object -First 1)
if ($packageInfo -notmatch "versionCode='3'" -or $packageInfo -notmatch "versionName='0.3.0'") {
    throw 'This is not the week-3 APK.'
}
& $adb devices -l
$serial = 'emulator-5554' # 실제 devices 결과로 정한다.
& $adb -s $serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'Install failed.' }
& $adb -s $serial shell am start -n lab.pleos.observe/.MainActivity
& $adb -s $serial forward tcp:8088 tcp:8088
New-Item -ItemType Directory -Force .local\evidence | Out-Null
& $adb -s $serial logcat -v threadtime -s ObserveLab:I '*:S' |
    Tee-Object -FilePath .local\evidence\week3-probe-logcat.txt
```

기존 로그를 지우지 않는다. Release는 기존처럼 별도 서명이 필요한 unsigned APK다. 앱 첫 속도 권한은 허용한다. 추가 Pleos 권한 거부 오류가 있으면 실제 오류·cap·GET·notify를 각각 기록한다. 토큰이 필요한지 여부도 실제 오류 없이 추정하지 않는다.

## Claude Virtual Console 시험 순서

`http://127.0.0.1:8088/`에서 P·속도 0·UX 제한 비활성으로 시작한다. 제목 길게 누르기 → **신호 탐침**. 먼저 cap와 최초 GET의 원문을 기록한다. 아래 입력 예시는 Console이 허용하는 범위 안에서 선택하며, 단위 환산이나 값 매핑을 미리 단정하지 않는다. 하나씩 변경하고 다른 카드는 그대로 둔다.

| 순서 / Console 카드 | 조작 | 화면·로그에서 확인할 것 |
|---|---|---|
| 1. **Odometer** | 예: 1000 → 1001 | `distanceDriven` cap, GET, 변경 notify의 원시 Float와 시각. GET은 초기 값, notify는 새 값으로 독립 표시된다. |
| 2. **Remaining Range** | 예: 200 → 201 | `remainingRange` cap는 “SDK에 없음”. deprecated GET과 notify가 실제 수신하는지 각각 확인. 없으면 미수신/오류를 기록한다. |
| 3. **Battery SOC** | 예: 50 → 51 | `batterySoc`의 원시 Int·cap·notify. 화면에서 %로 바꾸지 않아야 한다. |
| 4. **Charge Switch** | OFF ↔ ON | notify는 “SDK에 없음”. 변경 후 **현재값 다시 읽기**로 `chargeSwitch.get`의 원시 Boolean을 확인한다. |
| 5. **Ambient Brightness** | 지원 영역 하나의 값을 변경 | `ambientBrightness` 해당 SeatArea의 GET/notify·bounds. cap 없는 영역을 지원한다고 판정하지 않는다. |
| 6. **Ambient Color** | 지원 영역 하나에서 색을 두 번 선택 | `ambientBrightnessColor` 원시 Int 변화. 앱은 색 이름/RGB/16진수로 변환하지 않는다. `ambientState`도 별도 GET/notify를 확인하되 색 변경이 State 변경을 보장한다고 가정하지 않는다. Console에 State 조작이 없으면 그 변경 수신은 미검증으로 남긴다. |
| 7. **Door Position** | 1·2열 좌우를 차례로 변경 | `doorPosition`의 정확한 DoorArea·원시 Int·시각. 3열도 cap가 READ 지원할 때 확인. |
| 8. **Seat Occupancy** | 각 지원 좌석의 상태 변경 | `seatOccupancy` 해당 SeatArea의 원시 `true/false`와 GET/notify. CENTER/3열도 cap 지원 여부를 따로 기록한다. |
| 9. **Windshield Wiper Switch** | OFF → 지원하는 INTERMITTENT/AUTO 등 | `wiperSwitch` notify는 “SDK에 없음”. **현재값 다시 읽기** 후 `FRONT_WINDSHIELD`의 실제 enum 이름을 확인. `wiperState` GET/notify도 독립 기록하며 스위치 변경으로 실제 와이퍼 State가 바뀐다고 단정하지 않는다. |
| 10. 잠금 회귀 | 탐침 중 기어 D → P. 가능하면 속도/UX 제한도 시험 | D에서 플랫폼 차단 또는 앱 “정차 후 사용”, 원시 본문 제거. P 복귀 후 시작 화면. 다시 탐침에 들어가면 새 수신 시각이며 이전 데이터가 복원되지 않는다. |
| 11. 구독 해제·게임 회귀 | 진단 복귀 후 Console 변경, 다시 탐침 재진입. 앱 백그라운드/P 복귀도 시험 | 이탈 후 `probe.*` 콜백 로그가 이어지지 않고 재진입 때 다시 시작. 기존 창문·독서등 진단과 문제 3종은 그대로 동작. |

기록은 **cap 결과 / GET 값·시각 / notify 값·시각 / Console 입력 / 판정**을 분리한다. `SDK에 없음`, `차량 cap 미지원`, `API 오류`, `null 수신`, `미수신`을 합치지 않는다. 변경한 Console 입력은 원래 상태로 돌린다.

## 변경 파일

- `app/build.gradle.kts`: 버전 3 / 0.3.0.
- `app/src/main/AndroidManifest.xml`: 읽기 권한 4개 추가.
- `app/src/main/java/lab/pleos/observe/MainActivity.kt`: 진단 메뉴 버튼·탐침 화면·이탈/잠금 시 정리.
- `app/src/main/java/lab/pleos/observe/PleosObservationClient.kt`: 기존 Vehicle 세션을 빌리는 탐침 factory만 추가.
- `app/src/main/java/lab/pleos/observe/EvidenceLog.kt`: 전용 raw probe 로그.
- `app/src/main/java/lab/pleos/observe/SignalProbeClient.kt`: 11개 신호의 capability/GET/구독·원시 증거.
- `logic/src/main/kotlin/lab/pleos/observe/logic/SignalProbe.kt`: 탐침 표시 모델·무변환 포맷·로그 형식.
- `logic/src/test/kotlin/lab/pleos/observe/logic/SignalProbeTest.kt`: 탐침 JVM 테스트 7개.
- `scripts/inspect-probe-aar.ps1`: AAR 재현 조사.
- `README.md`, `docs/WEEK3_PROBE.md`: 실행·시험·시그니처 인계.

## Claude 에뮬레이터 실측 (2026-10-22, 0.3.0)
| 신호 | Console 조작 | 앱 수신 | 판정 |
|---|---|---|---|
| Odometer DistanceDriven | 0 → 49000 | get/notify 49000.0 (Float, 약 3~4초 주기 notify) | 통과 |
| Remaining Range | 0 → 320 | 계속 0.0 (deprecated) | 반영 안 됨 |
| EV SOC | 100 → 58 | 58 (**Int, 1% 단위**) | 통과, 해상도 한계 확인 |
| Charge Switch | Off → On | get true (구독 메서드 SDK에 없음) | get만 통과 |
| Ambient Brightness/Color | 70 / Purple | 밝기 70, 색 결합값 11141375 | 통과 |
| Door Position | 1열 왼쪽 Open | ROW_1_LEFT 1 | 통과 |
| Seat Occupancy | Driver Occupied | ROW_1_LEFT false, 다른 좌석 기본 true | 의미 불명(뒤집힘 또는 매핑) — 미확정 |
| Wiper Switch | Front Auto | wiperState FRONT ON | 수신됨(상태로 반영) |

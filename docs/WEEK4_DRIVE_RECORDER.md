# 4주차 · 주행 중 백그라운드 기록 가능성 시험

## 현재 결론

**시험 구현 완료, 실제 지속 수신 여부는 미검증.** versionCode 4 / versionName 0.4.0. Gradle 빌드·APK 설치·AVD Console 시험은 Claude가 한다. 서비스가 생성됨, foreground 승격 호출이 반환함, 실제 신호 콜백이 수신됨, D 전환 후에도 같은 실행에서 수신됨을 구분한다.

제목 길게 누르기 → 기존 관찰 진단 → **주행 기록 시험 시작 / 중지**. 시작은 화면이 foreground이고 기존 안전 판정이 해제된 P·속도 0·UX 제한 비활성 상태에서만 요청한다. 첫 알림 권한 창 처리 후에는 다시 시작 버튼을 눌러야 한다. 권한 창 때문에 시작 화면으로 돌아오면 제목을 길게 눌러 진단으로 다시 들어간다. 요청 토스트를 서비스 실행 성공으로 간주하지 않는다.

게임 reducer·문제·안전 판정·탐침 클라이언트는 변경하지 않았다. 기존 onPause의 관찰 중지·잠금·스냅숏 폐기는 그대로 수행한다. 기록 서비스는 Activity onPause/onStop에 연결해 중지하지 않는다. DO는 선언하지 않는다.

## 서비스 타입·권한 근거

AAOS의 [DO 규칙](https://source.android.com/docs/automotive/driver_distraction/guidelines)은 제한 상태에서 foreground Activity의 표시·실행을 다룬다. [Pleos 주행 규제 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide)의 CarUxRestrictions 구독 방식을 따르고 기존 화면 잠금을 유지했다. 이 문서들만으로 이 AVD의 FGS·SDK 콜백 지속 수신까지 보장할 수 없으므로 실제 결과는 아래 실험으로 구분한다.

Android 공식 [서비스 타입 규칙](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)과 [FGS 시작 규칙](https://developer.android.com/develop/background-work/services/fgs/launch)을 확인했다. API 34 이상은 타입과 해당 권한을 선언해야 한다. 이 시험은 다른 전용 타입에 해당하지 않는 사용자 시작 실시간 차량 콜백 관찰이므로 **specialUse**를 사용하고 용도를 `<property>`로 명시했다. 이는 로컬 AVD 시험용 선택이며 배포 심사 통과를 뜻하지 않는다.

새 선언:

```text
android.permission.FOREGROUND_SERVICE
android.permission.FOREGROUND_SERVICE_SPECIAL_USE
android.permission.POST_NOTIFICATIONS
service android:foregroundServiceType="specialUse"
property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
```

서비스는 `exported=false`, `process=:drive_recorder`, `stopWithTask=false`다. 별도 프로세스에서 자체 `Vehicle(applicationContext)`와 `Car` 연결을 소유하여 Activity/탐침의 해제와 분리했다. UID·앱 권한은 동일하며 `isolatedProcess`나 별도 권한을 사용하지 않는다. 이 구성에서의 결과만 이번 시험의 근거다.

시작 직후 SDK 초기화보다 먼저 알림 채널(IMPORTANCE_LOW)과 상시 알림을 만들고, API 34+에서 `startForeground(..., FOREGROUND_SERVICE_TYPE_SPECIAL_USE)`를 호출한다. 이전 API에서는 2인자 호출을 사용한다. 알림의 중지 액션은 Service PendingIntent만 실행한다.

[알림 권한 공식 설명](https://developer.android.com/develop/ui/compose/notifications/notification-permission)에 따라 `POST_NOTIFICATIONS` 거부 자체가 FGS 시작 금지는 아니다. 거부한 경우에도 다음 시작 버튼으로 시도하되, 알림 목록에서 보이지 않을 수 있다. `service.foreground` 로그의 `notificationsEnabled`도 함께 기록한다. 시험은 권한을 허용하고 실행하는 것이 권장된다.

차량 조회 권한은 모두 기존 선언을 사용한다. 추가 차량 제어 권한은 없다.

| 신호 | 권한 / 근거 |
|---|---|
| TurnSignal | `pleos.car.permission.CAR_LIGHTS` · AAR의 해당 check/get/register/unregister `@RequiresPermission` |
| SteeringWheel angle | `pleos.car.permission.CAR_DRIVE` · 같은 AAR 어노테이션. 핸들 열선용 CAR_CLIMATE는 사용하지 않음 |
| Brake pedal | `pleos.car.permission.CAR_DRIVE` · 같은 AAR 어노테이션 |
| CURRENT_GEAR | 기존 `android.car.permission.CAR_POWERTRAIN` |
| PERF_VEHICLE_SPEED | 기존 `android.car.permission.CAR_SPEED` · Activity에서 이미 런타임 요청 |
| UX restrictions | 기존 `Car.CAR_UX_RESTRICTION_SERVICE` API 사용. 새 권한 없음 |

기존 `uses-library android.car required="false"`와 AAOS 하드웨어 요구를 유지했다. GPS·VIN·계정·네트워크·부팅 자동 실행·wake lock 권한은 추가하지 않았다.

## AAR 시그니처 근거

직접 조사한 `ai.pleos.playground:Vehicle:2.3.2`:

```text
.gradle-user-home/caches/modules-2/files-2.1/ai.pleos.playground/Vehicle/2.3.2/
  1c5535aa87c279e1cf36fa69452d3f29da562139/Vehicle-2.3.2.aar
SHA256=e11fdfe498cad3ecc0d5a2a6992b0c4a4d135195f1455018127bc069fd5ef937
```

`classes.jar`를 `.local/w4-aar/`에 추출하고 `javap -public`, `javap -p -v`로 인터페이스·nullable listener·권한 어노테이션·Kotlin metadata를 확인했다. 요청한 세 신호의 메서드는 모두 존재한다. 이번 대상 중 “SDK에 없음”에 해당하는 메서드는 없다. 연결 오류·권한 오류·미수신을 메서드 부재로 기록하지 않는다.

공통 패키지:

- 진입 `ai.pleos.playground.vehicle.Vehicle`: `getTurnSignal(): TurnSignal`, `getSteeringWheel(): SteeringWheel`, `getBrake(): Brake`.
- 도메인 `ai.pleos.playground.vehicle.api`.
- listener `ai.pleos.playground.vehicle.listener`; 상위 `VehicleListener.onFailed(e: Exception)`.
- `CapabilityConfig`, `AreaBounds`: `ai.pleos.playground.vehicle.api.model.capability`.
- `TurnSignalValue`: **`ai.pleos.playground.vehicle.constant.area`**. 상수는 `OFF`, `RIGHT`, `LEFT`. 별도의 영역 인자는 없다.

확인한 시그니처를 Kotlin 표기로 정리했다. 모두 반환 `Unit`, `E = (Exception) -> Unit`이다. JVM의 Float/Boolean 콜백은 boxed 타입이며 listener의 값 매개변수는 Nullable 어노테이션을 확인했다.

```kotlin
// Vehicle.getTurnSignal()
checkTurnSignalStateCapabilities(onComplete: (CapabilityConfig) -> Unit, onFailed: E)
getCurrentTurnSignalState(onComplete: (TurnSignalValue?) -> Unit, onFailed: E)
registerTurnSignalState(listener: TurnSignalStateListener)
unregisterTurnSignalState(listener: TurnSignalStateListener)
// TurnSignalStateListener
onTurnSignalStateUpdated(state: TurnSignalValue?)

// Vehicle.getSteeringWheel()
checkSteeringWheelAngleCapabilities(onComplete: (CapabilityConfig, AreaBounds<Float>) -> Unit, onFailed: E)
getCurrentSteeringWheelAngle(onComplete: (Float?) -> Unit, onFailed: E)
registerSteeringWheelAngle(listener: SteeringWheelAngleListener)
unregisterSteeringWheelAngle(listener: SteeringWheelAngleListener)
// SteeringWheelAngleListener
onSteeringWheelAngleUpdated(angle: Float?)

// Vehicle.getBrake()
checkBrakePedalStatusCapabilities(onComplete: (CapabilityConfig) -> Unit, onFailed: E)
getCurrentBrakePedalStatus(onComplete: (Boolean?) -> Unit, onFailed: E)
registerBrakePedalStatus(listener: BrakePedalStatusListener)
unregisterBrakePedalStatus(listener: BrakePedalStatusListener)
// BrakePedalStatusListener
onBrakePedalStatusUpdated(status: Boolean?)
```

capability 결과를 로그에 남긴 뒤, cap 성공/실패와 별도로 GET과 구독을 각각 시도한다. 이 진단은 cap만으로 실수신 가능 여부를 단정하지 않는다. 초기 GET을 이후 notify처럼 재사용하거나 주기적으로 GET을 반복하지 않는다.

재현:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\inspect-drive-aar.ps1
Get-Content -Encoding UTF8 .local\w4-aar\api_TurnSignal.public.txt
Get-Content -Encoding UTF8 .local\w4-aar\api_SteeringWheel.public.txt
Get-Content -Encoding UTF8 .local\w4-aar\api_Brake.public.txt
Select-String -Path .local\w4-aar\api_*.verbose.txt -Pattern 'RequiresPermission' -Context 0,3
```

AAOS는 API 34에서 사용 가능한 기존 방식으로 구독한다:

```text
Car.createCar(Context, Handler, long, Car.CarServiceLifecycleListener): Car
CarPropertyManager.registerCallback(CarPropertyEventCallback, int propertyId, float rate): boolean
CarPropertyManager.unregisterCallback(CarPropertyEventCallback): void
CarPropertyEventCallback.onChangeEvent(CarPropertyValue<?>): void
CarPropertyEventCallback.onErrorEvent(int propertyId, int areaId): void
CarUxRestrictionsManager.registerListener(OnUxRestrictionsChangedListener): void
CarUxRestrictionsManager.getCurrentCarUxRestrictions(): CarUxRestrictions
CarUxRestrictionsManager.unregisterListener(): void
```

참조: [Car](https://developer.android.com/reference/android/car/Car), [CarPropertyManager](https://developer.android.com/reference/android/car/hardware/property/CarPropertyManager), [UX manager](https://developer.android.com/reference/android/car/drivingstate/CarUxRestrictionsManager). rate는 `SENSOR_RATE_UI`. register 반환·Car ready·초기 UX GET을 실제 변화 콜백과 분리한다. Car 재연결 시 이전 manager를 해제하고 새 manager에 재구독하며 `car.lifecycle`을 남긴다. SDK 재초기화나 서비스 자동 재시작은 수행하지 않는다.

## 로그·메모리·수명

앞선 로그 정책을 유지해 **Debug APK에서** 원시 값과 `SystemClock.elapsedRealtime()` 밀리초를 남긴다. Release는 이벤트 이름만 기록한다. 이번 시험은 Debug APK로 실행한다.

```text
event=drive.activity.onPause value=called t=... source=lifecycle pid=<UI PID>
event=drive.service.onCreate value=called t=... source=lifecycle pid=<서비스 PID> run=...
event=drive.steeringAngle value=-12.5 t=... source=notify pid=... run=... n=2
event=drive.turnSignal value=LEFT t=... source=notify pid=... run=... n=2
event=drive.brakePedal value=true t=... source=notify pid=... run=... n=2
event=drive.gear value=8 t=... source=notify pid=... run=... n=2 propertyId=... areaId=0 status=0 vehicleTimestampNs=...
event=drive.ux value=... t=... source=notify pid=... run=... n=2 requiresDO=true
event=drive.heartbeat value=alive t=... source=timer pid=... run=... counts=...
```

형식 예시이며 실기 수신 로그가 아니다. `gear`는 AAOS 원시 Int(로컬 VehicleGear 상수 P=4, D=8), `speed`는 원시 Float, UX는 `activeRestrictions` Int와 원래 `requiresDO` Boolean이다. 단위 환산·점수화·추론을 하지 않는다. `source=get/cap/framework_return/notify/timer/lifecycle`을 구분한다. `.error` 이벤트는 오류 클래스 이름만 남기며 성공 수신으로 세지 않는다. `null` 콜백과 `status != STATUS_AVAILABLE`도 정상 값 수신으로 판정하지 않는다.

서비스 메모리에는 실행 구분자와 6개 신호의 notify 카운터만 둔다. 입력값 이력·여행/위치/개인정보·파일·DB·SharedPreferences 저장은 없다. `run`과 PID는 재시작을 식별하기 위한 실행 정보다. 5초 heartbeat는 **서비스 timer 실행 증거만**이며 신호 수신 증거가 아니다. Android logcat 버퍼에는 로그가 남으며 앱이 별도 파일로 저장하지 않는다.

서비스 `onCreate/onStartCommand/onDestroy/onTaskRemoved`와 Activity `onPause/onStop`을 기록한다. `START_NOT_STICKY`라서 OS가 서비스를 종료한 뒤 자동 재시작하여 연속 수신인 것처럼 보이지 않는다. 재시작하면 run이 바뀐다. 시작을 중복으로 누르면 기존 구독·카운터를 그대로 유지하고 `duplicateStart`만 기록한다.

정상 중지 시 자기 소유 SDK listener와 Car/UX listener만 해제하고 SDK release·Car disconnect·heartbeat 취소·알림 제거·카운터 폐기를 수행한다. 끝에는 `service.cleanupComplete`를 남긴다. 시스템 강제 종료에서는 onDestroy/onTaskRemoved가 보장된다고 가정하지 않는다. 이때 PID와 서비스 상태도 확인한다.

## 로컬 검증

- Gradle은 요청대로 실행하지 않았다. APK 전체 빌드·리소스 링크·설치·실제 FGS 승격·AVD 지속 수신은 미검증이다.
- 기존 48개 + drive 로그 시험 4개 = **JVM 52/52 통과**. `scripts/test-logic-offline.ps1`, 결과 `.local/w4-jvm-tests.txt`.
- Android/Vehicle 타입은 설치된 Kotlin compiler로 전체 앱·logic 소스를 직접 컴파일하여 확인했다. `BuildConfig.DEBUG=true` 임시 stub을 사용한 **소스 타입 검사**이며 Gradle Debug/Release 빌드나 APK 생성의 증거가 아니다. 결과 `.local/w4-kotlin-types.txt`, `.local/w4-compile/source-types.jar`.
- Android API 36 SDK의 원본 android.jar/android.car.jar를 타입 검사에 사용했다. 기존 프로젝트 내 사본의 SHA-256도 원본과 일치함을 확인했다. 외부 SDK 권한 변경·파일 쓰기는 수행하지 않았다.
- 매니페스트 XML, FGS 선언·프로세스·추가 권한, 게임/탐침 로직 무변경을 확인했다. 실제 API 34의 제조사별 정책은 Console 시험으로 확인해야 한다.

JVM 재현:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-logic-offline.ps1
```

## Claude 빌드·설치·로그 수집

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
if ($info -notmatch "versionCode='4'" -or $info -notmatch "versionName='0.4.0'") { throw 'Not week-4 APK.' }
& $adb devices -l
$serial='emulator-5554' # devices 결과로 정한다.
& $adb -s $serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'Install failed.' }
& $adb -s $serial shell am start -n lab.pleos.observe/.MainActivity
& $adb -s $serial forward tcp:8088 tcp:8088
New-Item -ItemType Directory -Force .local\evidence | Out-Null
& $adb -s $serial logcat -v threadtime -s ObserveLab:I AndroidRuntime:E '*:S' |
    Tee-Object -FilePath .local\evidence\week4-drive-logcat.txt
```

UI와 서비스는 별도 PID이므로 **UI PID만으로 logcat을 필터링하지 않는다**. 기존 로그를 지우지 않는다.

다른 터미널에서 실제 상태도 확인한다:

```powershell
& $adb -s $serial shell pidof lab.pleos.observe
& $adb -s $serial shell pidof lab.pleos.observe:drive_recorder
& $adb -s $serial shell dumpsys activity services lab.pleos.observe
& $adb -s $serial shell dumpsys notification
```

## Claude Console 시험 순서

`http://127.0.0.1:8088/`를 연다. 이 시험은 해당 AVD에서만 수행한다.

| 순서 | 조작 | 확인할 증거 |
|---|---|---|
| 1 | P·속도 0·UX 제한 비활성. 속도 권한 허용 후 숨은 진단으로 이동. 시작을 누르고 알림 권한을 처리한 뒤 다시 시작 | startRequested뿐 아니라 서비스 PID에서 onCreate → onStartCommand → foreground → SDK/Car notify를 확인. 알림에 중지 버튼. 신호별 초기값 기록 |
| 2 | P를 유지하며 Steering Angle, Turn Signal State, Brake Pedal을 하나씩 변경 | 신호별 `source=notify`와 원시값을 기준 증거로 확인. 초기 GET·cap만으로는 부족 |
| 3 | **Gear D**. 화면의 플랫폼 차단 확인 | 서비스의 gear/ux, UI onPause/onStop의 실제 발생 여부·시각 기록. D가 됐다는 이유만으로 onPause 발생을 가정하지 않는다. 서비스 PID/run 유지 확인 |
| 4 | D를 유지하며 **Steering Angle** 0→양수→음수, **Turn Signal State** OFF→LEFT→RIGHT→OFF, **Brake Pedal** false→true→false | 화면 차단 뒤(onPause가 있으면 그 t 이후)의 새 SDK 3종 `source=notify`. 같은 서비스 PID/run에서 n 증가와 Console 입력에 대응하는 원시값 확인. 허용 범위는 Console을 따름 |
| 5 | D에서 AAOS gear/speed 콜백 관찰. 속도 수신이 멈췄다면 Console 속도 항목에서 0→작은 양수→0 변경(항목이 있을 때) | `drive.speed source=notify`가 차단 후에도 오는지 확인. SDK 3종만으로 CarPropertyManager의 지속 수신을 증명하지 않는다. 값이 일정할 때 알림이 없다는 이유만으로 중단 판정하지 않는다. 속도 조작 불가·차단 후 Car 콜백 없음이면 해당 부분은 미검증 |
| 6 | **P 복귀**, 속도 0·UX 제한 비활성으로 복원 | 기존 규칙대로 시작 화면 복귀. 서비스는 같은 PID/run으로 gear/ux 수신. 새 onCreate나 카운터 초기화가 없는지 확인. SDK 3종을 다시 변경하여 회복·지속 여부 확인 |
| 7 | 숨은 진단의 **중지**, 또는 알림의 중지 액션 | onDestroy → cleanupComplete, 알림 제거, 이후 heartbeat/notify가 계속되지 않음. 재시작하면 새 run·카운터 0에서 시작 |

합격 여부는 신호별로 나눈다. **서비스 생존 / SDK 3종의 유효 notify / CarPropertyManager의 차단 후 notify / UX 변화 / 같은 run 지속**을 각각 기록한다. heartbeat·초기 GET·오류 로그·재시작 후 수신만으로 합격 판정하지 않는다. 화면만 가려지고 onPause가 없었다면 그 조건을 명시하고 “Activity 중지 후 시험”으로 보고하지 않는다.

실패 시 `ForegroundServiceStartNotAllowedException` / `SecurityException` / `.error`, 초기 cap, 마지막 notify 시각, heartbeat 유무, 서비스 PID/run, dumpsys 결과를 전달한다. onDestroy 로그가 없다는 이유만으로 생존 판정하지 않는다. 시험 후 Console 입력을 원래 상태로 복원한다.

## 변경 파일

- `app/build.gradle.kts`: 4 / 0.4.0.
- `app/src/main/AndroidManifest.xml`: FGS 권한 3개, 서비스와 specialUse subtype.
- `app/src/main/java/lab/pleos/observe/MainActivity.kt`: 진단 버튼, 알림 권한, onPause/onStop 로그만 추가.
- 신규 `app/.../DriveRecorderService.kt`: FGS·알림·수명·메모리 카운터·heartbeat.
- 신규 `app/.../DriveSignalSubscriptions.kt`: 서비스 전용 SDK·Car/UX 구독.
- 신규 `app/.../DriveTrialLog.kt`: Debug 원시값·단조 시각 로그.
- 신규 `logic/.../DriveTrialLog.kt`, `logic/.../DriveTrialLogTest.kt`: 순수 로그 함수·테스트 4개.
- 신규 `scripts/inspect-drive-aar.ps1`: AAR 조사 재현.
- `README.md`, 이 문서: 빌드·검증 인계.

## Claude 에뮬레이터 실측 (2026-10-22, 0.4.0)
- P에서 [주행 기록 시험 시작] → 서비스 별도 프로세스(pid 13886, 같은 run 유지)로 onCreate→foreground(specialUse)→SDK·Car 구독.
- Console Gear D → `drive.ux requiresDO=true` 수신(t=155367), 플랫폼이 액티비티를 가림(activity.onPause t=162434).
- **D 상태에서 계속 수신:** 조향각 -120.0 → 90.0, 방향지시등 LEFT → OFF, 브레이크 true → false, 속도 콜백 지속. 모두 같은 pid/run, 카운터 증가.
- 주의: Console Steering Angle 120 입력이 앱에선 -120.0(부호 반대)로 수신 — 좌/우 해석 전에 부호 규약 확인 필요.
- 판정: **DO 미선언 앱도 포그라운드 서비스로 주행 중 Vehicle 신호 수신 가능(AVD 기준)**. 실차·앱마켓 심사에서 허용되는지는 미확인.

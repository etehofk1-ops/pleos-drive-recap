# 공개 API 확인 기록

확인일: 2026-10-22. 공식 웹 문서와 Maven 메타데이터를 읽은 기록이다. 아래 시그니처는 문서의 타입 표기를 정리한 것이며, 실제 AAR로 전체 앱을 컴파일했다는 뜻은 아니다. 원문은 각 링크가 정본이다.

## 버전과 Android 기준

- [실제 Maven metadata](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/Vehicle/maven-metadata.xml): Chrome에서 `latest=2.3.2`, `release=2.3.2`, versions `2.0.3, 2.3.2`, `lastUpdated=20261019002245` 확인. 웹 검색 도구와 PowerShell의 직접 접근은 실패했고 브라우저 접근은 성공했다.
- [SDK 가져오기](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup): `ai.pleos.playground:Vehicle:2.3.2`와 공개 Maven 저장소 안내.
- [시작 가이드](https://document.pleos.ai/docs/pleos-only/getting-started): API 34 에뮬레이터, minSdk 28 이상. 이 페이지에는 의존성이 2.3.1로 남아 있다.
- [샘플 튜토리얼](https://document.pleos.ai/docs/pleos-only/tutorial/vehicle-sdk-sample-app): release 기준 compileSdk/targetSdk 35, 샘플 minSdk 23. 배포 앱은 minSdk 28 이상을 별도로 요구한다. 이 앱은 minSdk 28, targetSdk 35, 로컬에 설치된 compileSdk 36을 선택했다.
- [AGP 8.13 호환성](https://developer.android.com/build/releases/agp-8-13-0-release-notes): Gradle 최소 8.13, JDK 17. 로컬 배포본에서 추출한 Gradle 8.14.3 wrapper를 사용한다.

## 진입점·권한·인증

- [도메인 객체 획득](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/obtain-domain-object): `Vehicle(context)`, `initialize()`, `getWindow()`, `getLight()`, 종료 시 `release()`.
- [initialize](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle/vehicle/initialize): 인자와 완료 콜백이 없는 `fun initialize()`. 초기화 호출 반환을 서비스 연결 완료로 표시하지 않는다. 앱의 “Vehicle SDK 응답 확인”은 capability 콜백 수신을 뜻한다.
- [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission): 창문 조회 `pleos.car.permission.CAR_WINDOWS`, 조명 조회 `pleos.car.permission.CAR_LIGHTS`. 개별 API 참조에서 normal 보호 수준 확인. 제어 권한은 선언하지 않는다.
- [SDK 신청과 키](https://document.pleos.ai/docs/pleos-only/create-my-project): Vehicle State는 신청 없이 사용 가능. 프로젝트 키는 심사, 클라이언트 ID/시크릿은 TTS/STT 등 서버 연결 SDK에 안내된다. Emulator 2.1.0 이후 CRN 입력 불필요. 조회용 Vehicle 초기화 문서·공식 샘플에는 Connect 토큰/앱 키를 전달하는 절차가 없다. 이 조회 앱에는 키 입력을 구현하지 않았다. 실제 에뮬레이터의 인증·권한 성공 여부는 미검증이다. 향후 인증 오류가 발생하면 오류와 현행 공식 절차를 확인해야 하며 임의 키를 넣지 않는다.
- [공식 샘플 매니페스트](https://github.com/Pleos-Playground/pleos-sample-vehicle-app/blob/main/app/src/main/AndroidManifest.xml): 토큰 메타데이터나 별도 서비스 package query가 없는 것을 확인했다.

## 창문

패키지 `ai.pleos.playground.vehicle.api`의 `Window`에서 호출한다.

| 메서드 | 확인한 매개변수 |
| --- | --- |
| [checkWindowPositionCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/check-window-position-capabilities) | `onComplete: (CapabilityConfig, Map<WindowArea, AreaBounds<Int>>) -> Unit`, `onFailed: (Exception) -> Unit` |
| [getCurrentWindowPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/get-current-window-position) | `area: WindowArea`, `onComplete: (Int?) -> Unit`, `onFailed: (Exception) -> Unit` |
| [registerWindowPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/register-window-position) | `area: WindowArea`, `listener: WindowPositionListener` |
| [unregisterWindowPosition](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/unregister-window-position) | 위와 동일한 영역과 리스너 인스턴스 |

[WindowPositionListener](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.listener/window-position-listener/entry): `onWindowPositionUpdated(position: Int?)`; 상위 [VehicleListener](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.listener/vehicle-listener/entry)의 `onFailed(e: Exception)`도 구현한다.

[WindowArea](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api.model.area/window-area/entry)는 `vararg WindowValue` 생성자와 `values` 배열을 가진다. [WindowValue](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.constant.area/window-value/entry)의 `ROW_1_LEFT`, `ROW_1_RIGHT`, `ROW_2_LEFT`, `ROW_2_RIGHT`를 관찰한다. API 원시 값을 백분율로 변환하지 않는다.

## 독서등

패키지 `ai.pleos.playground.vehicle.api`의 `Light`에서 호출한다.

| 메서드 | 확인한 매개변수 |
| --- | --- |
| [checkReadingLightStateCapabilities](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/check-reading-light-state-capabilities) | `onComplete: (CapabilityConfig, Map<SeatArea, List<LightState>>) -> Unit`, `onFailed: (Exception) -> Unit` |
| [getCurrentReadingLightState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/get-current-reading-light-state) | `area: SeatArea`, `onComplete: (LightState?) -> Unit`, `onFailed: (Exception) -> Unit` |
| [registerReadingLightState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/register-reading-light-state) | `area: SeatArea`, `listener: LightStateListener` |
| [unregisterReadingLightState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/light/unregister-reading-light-state) | 위와 동일한 영역과 리스너 인스턴스 |

[LightStateListener](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.listener/light-state-listener/entry): `onLightStateUpdated(state: LightState?)`, 상위 `onFailed(e: Exception)` 구현. 본문에 HighBeam이라고 쓰인 문서 오류가 있지만 독서등 register의 리스너 타입이 이 인터페이스임을 확인했다.

[SeatArea](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api.model.area/seat-area/entry)는 `vararg SeatValue` 생성자와 `values` 배열을 가진다. [SeatValue](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.constant.area/seat-value/entry)에는 1~3열 좌·중·우 9개가 있다. 미지원 좌석도 명시한다. [LightState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.constant.control/light-state/entry)는 OFF, ON, DAYTIME_RUNNING enum이다. 들어온 enum 이름을 그대로 표시한다.

[CapabilityConfig](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api.model.capability/capability-config/entry)의 [AccessType](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api.model.capability/access-type/entry)은 NONE, READ, WRITE, READ_WRITE. 읽기 가능하면서 지원 맵에 있는 좌석만 구독한다. capability 실패는 미지원으로 오인하지 않고 미수신과 오류를 표시한다.

## AAOS 안전 신호

- [Car](https://developer.android.com/reference/android/car/Car): `createCar(context, handler, CAR_WAIT_TIMEOUT_DO_NOT_WAIT, CarServiceLifecycleListener)`와 `disconnect()`.
- [CarPropertyManager](https://developer.android.com/reference/android/car/hardware/property/CarPropertyManager): `registerCallback(callback, propertyId, rateHz)`의 최초 상태 이벤트와 변경 이벤트를 읽고 `unregisterCallback(callback)`으로 해제한다. 반환값 false/예외/비정상 status를 확인한다. API 34 호환을 위해 이 메서드를 사용한다. SDK 36에서 deprecated 경고가 있지만 신규 메서드로 교체하면 API 34 호환성을 별도 검토해야 한다.
- [VehiclePropertyIds](https://developer.android.com/reference/android/car/VehiclePropertyIds): `CURRENT_GEAR`는 실제 변속기 기어다. `GEAR_SELECTION`과 동일하다고 가정하지 않는다. `PERF_VEHICLE_SPEED` 단위는 m/s이며 후진은 음수일 수 있다. CAR_POWERTRAIN은 normal, CAR_SPEED는 dangerous이므로 속도 런타임 권한을 요청한다.
- [CarUxRestrictionsManager](https://developer.android.com/reference/android/car/drivingstate/CarUxRestrictionsManager): `registerListener`, `currentCarUxRestrictions`, `unregisterListener`. requiresDO가 true이거나 activeRestrictions가 BASELINE이 아니면 잠근다. 제한 상태 미수신도 잠근다.
- [주행 제한 개발](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide), [DO 미선언 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy): 앱은 DO를 선언하지 않는다. 플랫폼이 주행 중 앱을 먼저 차단할 수 있다. 앱 자체 잠금과 플랫폼 차단은 다른 검증 항목이다.

## 남은 실기 확인

정확한 조회·등록·해제 시그니처는 위 공식 참조에서 확인했다. 실제 2.3.2 AAR를 이용한 전체 컴파일, 이 AVD의 좌석 지원과 값 범위, notify 발생, CURRENT_GEAR와 Virtual Console의 Gear Selection 연동, 실제 속도 변경 입력 수단, 플랫폼 DO 차단과 앱 자체 잠금의 화면 전환은 Claude가 확인해야 한다. 앱에서 가짜 차량 값을 주입하거나 SDK 제어 API를 호출하는 기능은 없다.

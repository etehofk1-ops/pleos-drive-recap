# 9주차 · 조향 × NaviHelper 융합 · 1.1.0 (13)

2026-10-23. 코드/오프라인 검증과 실제 AVD 내비 검증을 구분한다. 이 폴더 안에서만 수정했고 Gradle 빌드·APK 복사·AVD 조작·외부 AI 호출·키 생성·commit/push는 하지 않았다.

## 현재 결과와 미확인 사항

- NaviHelper 기본 의존성, 실제 타입의 초기화/리스너/조회/해제/재연결, 순수 융합 엔진, 회전 깜빡이 카드/집계/AI 계약, E 입력과 위치 재생 도구를 구현했다. 기존 게임·신호 탐침·주행 구독·잠금은 유지한다.
- **거리 의미·단위 미확인:** `TBTInfo.distance`는 Double이다. AAR에 단위 주석이 없고 공식 상세 페이지 본문도 비어 있다. 소스 JAR 다운로드도 접속 실패했다. 잔여 m라고 추측하지 않았다. 기본 `NAVI_DISTANCE_METERS=false`이며 실제 거리 판정은 보류한다. 공식 근거나 지도100m/50m 표시와 원시값 대조, 이동 시 다음 동작까지 줄어드는 잔여거리인지까지 확인한 후 `-PnaviDistanceUnit=meters`로 활성화한다. 단위 없는 `TURN_STRAIGHT`의 제한적인 보조는 가능하나, 거리 미확인 회전/IC가 함께 있으면 승격하지 않는다.
- **개별 TBT 통과 이벤트 없음:** `onDestinationArrived`/`onWayPointArrived`는 목적지/경유지 도착이며 회전 지점 통과가 아니다. `TBTInfo`에 발생 시각/고유 동작 ID도 없다. 현재 앱은 `passageAtMs`를 채우지 않는다. 타입 변경을 통과로 꾸미지 않는다. 순수 로직의 passage 입력은 향후 검증된 공급자용이며 현재 SDK 경로에서는 거리 조건만 사용한다.
- **AVD 실제 안내·지도 경로·GPS 소비 여부 미검증:** 현재127.0.0.1:8088 조회는 접속 실패했다. 과거 로컬 카탈로그의 id/label/domain/write_endpoint에는 위치/내비 전용 항목이 없었다. 최신 카탈로그 재확인·실제 경로 선정·단위 확인·E 실측은 Claude가 한다. 템플릿 좌표를 실제 경로라고 보고하지 않는다.

## AAR 근거

Gradle 캐시의 파일을 직접 추출하고 Android Studio JBR의 `javap -public`, `javap -p -v`로 확인했다. 재현 명령:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/inspect-recap-sdk.ps1
```

출력은 `.local/w5-aar/` 아래 공개 시그니처/verbose/manifest/해시 파일이다. 본체에는 helper/listener가 있고, 데이터 타입은 POM의 compile 의존성 **navi-data:3.0.4**에 있다. SDK 클래스가 없다고 잘못 판단하지 않도록 검사 스크립트도 수정했다.

| 아티팩트 | SHA-256 |
|---|---|
| NaviHelper-3.0.5.aar | `15FADD11C7C611B1ABDF7EBF9CE91F2A6CE2728E60B4E38B4343E11BB9C12E97` |
| navi-data-3.0.4.aar | `7CA00E592134B53237A48CC3B7864063ECC5E2885465E3CE9C8C2A29789A2E2B` |

실제 Java 공개 시그니처(생성 copy/component/serializer는 생략):

```java
// ai.pleos.playground.navi.helper.NaviHelper
public NaviHelper(android.content.Context);
public final void initialize();
public final void initialize(kotlin.jvm.functions.Function0<kotlin.Unit>,
    kotlin.jvm.functions.Function1<? super java.lang.Throwable, kotlin.Unit>);
public final void addListener(ai.pleos.playground.navi.helper.listener.NaviHelperEventListener);
public final void removeListener(ai.pleos.playground.navi.helper.listener.NaviHelperEventListener);
public final void release();
public final void getRouteStateInfo();
public final void getTBTInfo();
public final void requestRoute(ai.pleos.playground.navi.data.RouteInfo);
public final void cancelRoute();
public final void requestRouteWithWaypoints(ai.pleos.playground.navi.data.RouteWithWaypointsRequest);
public final void requestReRoute();

// ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
public default void onTBTInfo(java.util.List<ai.pleos.playground.navi.data.TBTInfo>);
public default void onRouteStateInfo(ai.pleos.playground.navi.data.RouteStateInfo);
public default void onNavigationStatus(ai.pleos.playground.navi.constants.NavigationStatus);
public default void onRouteStarted(ai.pleos.playground.navi.data.RouteStartInfo);
public default void onRoutingCompleted();
public default void onReRouteEnded();
public default void onRouteCancelled();
public default void onRouteEnded(); // deprecated, compatibility handling only
public default void onDestinationArrived(ai.pleos.playground.navi.data.DestinationArrivedInfo);
public default void onWayPointArrived(ai.pleos.playground.navi.data.WaypointArrivedInfo);
public default void onError(ai.pleos.playground.navi.constants.NaviErrorCode);

// ai.pleos.playground.navi.data.TBTInfo
public TBTInfo(ai.pleos.playground.navi.constants.TurnType, double, java.lang.String);
public final ai.pleos.playground.navi.constants.TurnType getType();
public final double getDistance();
public final java.lang.String getDescription(); // not read/logged by app

// ai.pleos.playground.navi.data.RouteStateInfo
public final ai.pleos.playground.navi.constants.NaviMode getNaviMode();
public final boolean isRouting(); // route search flag, not used as proof of guidance

// ai.pleos.playground.navi.data.RouteInfo
public RouteInfo(double latitude, double longitude, java.lang.Double displayLatitude,
    java.lang.Double displayLongitude, java.lang.String poiName, java.lang.String poiId,
    java.lang.String address, java.lang.String poiSubId,
    ai.pleos.playground.navi.constants.RouteOption routeOption,
    java.lang.String poiProvider, boolean isMobileRequest);
// Kotlin constructor defaults verified by direct compilation. Diagnostic uses named latitude/longitude.
```

`NavigationStatus`: `INACTIVE`, `INITIALIZING`, `FREE_DRIVE`, `ROUTE_DRIVE`.
`NaviMode`: `NOT_WORKING`, `SAFE_GUIDING`, `ROUTE_GUIDING`, `SIMULATION_GUIDING`.
안내 중은 ROUTE_DRIVE 또는 ROUTE_GUIDING/SIMULATION_GUIDING만 인정한다. onRouteStarted는 경로 탐색 시작일 뿐 안내 시작으로 쓰지 않는다.

`TurnType` 전체(실제 철자 유지, `TURN_DEVIDE_MERGE` 포함):

```text
TURN_UNKNOWN TURN_STRAIGHT TURN_RIGHT TURN_LEFT TURN_KEEP_R TURN_KEEP_L
TURN_SHARP_R TURN_SHARP_L TURN_UTURN
TURN_ENTER_IC_R TURN_ENTER_IC_L TURN_ENTER_IC_C
TURN_EXIT_IC_R TURN_EXIT_IC_L TURN_EXIT_IC_C
TURN_ENTER_UNDER_R TURN_ENTER_UNDER_L TURN_ENTER_UNDER_C
TURN_AVOID_UNDER_R TURN_AVOID_UNDER_L TURN_AVOID_UNDER_C
TURN_ENTER_OVER_R TURN_ENTER_OVER_L TURN_ENTER_OVER_C
TURN_AVOID_OVER_R TURN_AVOID_OVER_L TURN_AVOID_OVER_C
TURN_ENTER_ROUNDABOUT_02 TURN_ENTER_ROUNDABOUT_03 TURN_ENTER_ROUNDABOUT_04
TURN_ENTER_ROUNDABOUT_06 TURN_ENTER_ROUNDABOUT_08 TURN_ENTER_ROUNDABOUT_09
TURN_ENTER_ROUNDABOUT_10 TURN_ENTER_ROUNDABOUT_12 TURN_EXIT_ROUNDABOUT
TURN_WAY TURN_ARRIVE TURN_TUNNEL TURN_DEVIDE_MERGE TURN_TOLL
TURN_ENTER_SERVICEAREA TURN_RESTAREA
```

좌/우는 TURN_LEFT/RIGHT 및 SHARP_L/R만 사용한다. KEEP·로터리·고가/지하·기타 코드를 교차로 회전으로 확대 해석하지 않는다. IC ENTER/EXIT와 DEVIDE_MERGE만 합류/진출 구간으로 묶는다. 다른 enum은 관측하되 확정 승격 근거로 쓰지 않는다. 조향 부호는 여전히 미확정이다.

### 권한

- 기존 `pleos.car.permission.NAVI_ROUTE`: getRouteStateInfo/getTBTInfo의 AAR `@RequiresPermission`.
- 추가 `pleos.car.permission.NAVI_ROUTE_SEARCH`: 숨은 P 전용 시연 설정의 requestRoute/cancelRoute에 필요. AAR annotation과 [공식 권한 표](https://document.pleos.ai/api-reference/connect-sdk-pleos/NaviHelper/declaring-permissions)에서 확인했다.
- `NAVI_CUSTOM_MAP`은 requestNavigationScreen을 쓰지 않아 추가하지 않았다. 위치 조회/런타임 위치 권한도 추가하지 않았다.
- 기존 FGS specialUse·비DO·속도/기어/UX 잠금은 유지한다.

## 융합 규칙

| 조건 | 출력/표시 | 보수적 처리 |
|---|---|---|
| 안내 없음/미수신/만료 | 기존 카드 그대로 · 추정 | 빈 TBT를 직진으로 만들지 않음 |
| 회전/유턴 ≤50m + 큰 조향 + 저속 + 지속 | TURN_NO_SIGNAL/LATE_SIGNAL/SIGNALLED · 내비 확인 | 방향은 내비 enum; 깜빡이는 해당 방향 규칙, 관측 부족은 애매함 |
| 안내 중·유효 TBT에 근접 회전 없음 + 작은 왕복 | 차선 변경 · 내비 보조 | 완전한 구간 관측/속도/깜빡이 확보 시에만 애매함 승격 |
| 근접 회전 안내인데 작은/빠른 조향 | UNSURE · 내비와 핸들 불일치 | Jev도 덮어쓰지 못함 |
| 근접 IC 진입/진출/합류 + 차선 변경 모양 | 차선 변경 · 내비 보조 · 합류/진출 구간 | 교차로 회전으로 오인하지 않음 |

| 상수 | 값 | 근거/한계 |
|---|---|---|
| NEAR_METERS | ≤50m | 사용자 v9 시험 기준, 단위 검증 필요 |
| PRE_MS/POST_MS | 각3000ms | 구간 전후 안내·Binder 지연 허용의 초기 가정. 수신 시각 기준이며 원본 TBT 발생 시각은 SDK에 없음 |
| FRESH_MS | 3000ms | 구간 전체 TBT 공백/만료 방어. 빈 정보로 회전 부재를 추정하지 않음 |
| TURN_MIN_ANGLE / TURN_MAX_MPS / TURN_MIN_MS | 원시55 / 25km/h / 2500ms | 기존 저속 큰 회전 기준 재사용. 실차 검증된 표준 아님 |
| lane shape | 반전1회, 양쪽 원시6 이상, 최대35, 600..6000ms | 기존 왕복 조향 기준 재사용 |
| ASSIST_MIN_MPS | 25km/h | 내비 관측이 완전할 때 기존30km/h보다5km/h 경계만 완화. 속도 미수신/입력 공백/불완전/깜빡이 미확인은 승격 금지 |
| KEEP_MS / snapshots | 66000ms / 최대256 | 최대 조향60초+전후6초를 메모리에만 보유. TBT 목록32개 초과/잘못된 거리는 승격 차단 |
| SDK 조회 / 재연결 | 1초 / 2→4→…최대30초 | initialize 실패·조회 예외·응답 단절15초에 리스너 해제/release 후 재생성. 첫 연결 callback도20초 제한 |

깜빡이는 기존 시작 전3초~조향 중 규칙이다. 새로 좌/우 각각의 before/late/none/unknown 요약을 계산한다. 내비 우회전에 왼쪽 깜빡이만 켠 경우 좋아요로 세지 않는다. 유턴은 이 한국어 시험 규칙에서 왼쪽을 대응시키며 법률 판정으로 쓰지 않는다. 방향 불명인 차선 변경은 기존대로 어느 방향이든 관측한다.

TBT는 전체 목록의 타입·거리만 축소한다. 가장 가까운 직진 뒤에50m 내 회전이 있으면 그 회전을 놓치지 않는다. 서로 다른 가까운 좌/우회전이면 애매함이다. 안내 취소/재탐색/연결 세대가 달라진 스냅숏은 섞지 않는다. 조향 종료 후 최대3초 대기하고, 지연 안내가 없으면 원래 카드로 확정한다. 정차 직후에는 잠깐 카드가 더 도착할 수 있으며 요약 캐시도 갱신된다. 중지/날짜 전환 시 대기를 강제로 마감한다.

## 저장/AI 경계

- NavigationSnapshot/SDK enum 원문/거리/통과 시각/연결 세대/전체 SDK 객체는 메모리 전용이다. 구간 연계 종료·기간 만료·중지·삭제 시 버린다. 위치/목적지/도로명 조회를 하지 않는다. description도 읽지 않는다.
- 상세 저장 형식v2는 최종 NavigationLabel과 판정된 LEFT/RIGHT/UTURN, 깜빡이 방향별 최소 근거만 추가한다. SDK TurnType 원문이나 거리는 저장하지 않는다. v1 읽기는 유지한다. 기존 파일을 삭제/덮어쓰기해 복구하지 않는다.
- 7일 집계에 turnMissing/turnLate/turnGood 세 정수 추가. 기존4개 형식도 읽는다. 회전 건수와 차선 변경 건수는 별개다. UNSURE는 확정 건수에 넣지 않는다.
- AI `/summarize`에 TURN_NO_SIGNAL/TURN_LATE_SIGNAL/TURN_SIGNALLED와 집계3개를 추가한다. 위치/거리/TBT원문/연결세대/정확한 시각은 여전히 금지한다. `/classify` 계약은 유지하고 내비 충돌/내비 확인 회전은 Jev에 보내지 않는다. AI OFF/서버 실패에도 융합 규칙은 로컬에서 동작한다.
- 내비 시연 설정의 목적지 입력은 P 상태에서 사용자 요청으로 NaviHelper에만 직접 전달한다. 현재 위치를 조회하지 않으며 기록 서비스·AI·파일·로그에 전달하지 않는다. 자동 View 상태 저장/자동완성/최근 화면을 막고 이탈 시 입력을 지운다. 시연 목적지 요청과 차량 위치 수집은 구분한다.

## 변경 파일

- 앱: build.gradle.kts(13/1.1.0, 기본 SDK/단위 gate), AndroidManifest(NAVI_ROUTE_SEARCH/private diagnostic Activity), NaviTbtProbe(typed client), NaviDemoActivity(목적지 요청), RecapRecordingSession(메모리 융합), MainActivity(라벨/내비 상태/진단 진입), AiJson(집계).
- logic: NavigationFusion.kt, RecapModels/RecapEngine(방향별 깜빡이), RecapCodec(v2/v1), RecapText/AiReview, ReviewCounts/CountCodec, SummaryCache, NavigationFusionTest.
- 서버: contract.mjs(세 회전 kind/닫힌 v2 집계, v1 호환).
- scripts: inspect-recap-sdk.ps1, recap-scenario-e-navi.json, recap-navi-location.ps1, recap-navi-route-template.json, test-navi.mjs, test-recap-replay.mjs(오래된 D 선택 조건만 정합화), 제출 검사/모의 버전13 갱신.
- 문서: README, AI_CONTRACT, 서버 README, WORK-CHECKLIST, 본 문서.
- 작업 시작 전 RecapRepository/RecapSummaryCoordinator/README/check-submission의 기존 수정은 보존했다. 게임·SignalProbeClient·SafetyState/shouldLock·DriveSignalSubscriptions·A/B/C/D 입력·recap-replay.js는 변경하지 않았다.

## Claude 실행 순서

1. `scripts/inspect-recap-sdk.ps1`로 캐시/해시/공개 타입 재현. 기본 빌드(단위 미확인)로 내비 조회 로그부터 확인한다.
2. P에서 기록 시작→제목 길게→내비 시연 설정→공개 목적지 입력·요청→지도 앱 실제 안내 시작. 요청 반환 자체는 성공 증거가 아니며 onRoutingCompleted/안내 모드/TBT로 구분한다. 네트워크·POI ID·지도 약관은 AVD 상황에 따라 필요할 수 있고 임의 로그인/계정 생성은 하지 않는다.
3. 지도 거리100m와50m에 대한 `event=drive.navi.tbt`의 `distanceRaw`를 대조한다. 이동에 따라 줄어드는 다음 동작까지의 잔여거리인지도 확인한다. 잔여 m인지 확인된 근거를 문서 부록에 적고 `-PnaviDistanceUnit=meters`로 빌드한다. 미확정이면 gate를 켜지 않고 미확인으로 보고한다.
4. [README 내비 절](../README.md#내비-융합-시나리오-e-claude-실행)대로 현재 `/vehicle/catalog` 조사·실제 공개 도로 경로 선정·위치 템플릿 작성·DryRun. `adb emu geo fix longitude latitude` 후 지도/TBT 변화 확인. GPS OK와 내비 소비를 구분한다.
5. 브라우저 Console에서 E `right-turn` phase 실행 + 위치 재생: 실제 TURN_RIGHT≤50m의 조향 시각을 맞춘다. 기대 TURN_SIGNALLED/우회전/좋아요/내비 확인1. 다음 `uturn`: TURN_UTURN≤50m, Off, 큰 저속 조향 → TURN_NO_SIGNAL/깜빡이 없이 유턴/내비 확인1.
6. P에서 안내 취소/종료 확인 → `lane` phase → LANE_NO_SIGNAL/추정1. D 잠금, P 후 실시간 집계, 최소3초 지연 판정, 오늘의 한마디/회전 건수·피드백·재기동 저장 확인.
7. 안내가 직진/먼 회전인 구간에서 C 경계 속도 왕복 → 내비 보조 승격. 가까운 회전에서 작은 빠른 왕복 → 내비와 핸들 불일치/애매함. IC가 제공되는 경로면 합류/진출 라벨도 확인한다. TBT 미수신은 기존 규칙 유지.
8. 내비 앱/중계 종료·기록 중지/재시작·안내 재탐색·AI OFF·삭제·P/D 반복에서 오래된 스냅숏/문장 재사용이 없는지 확인한다. A/D는 안내를 종료하고 기존 카드 결과를 재확인한다.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
# 처음: 단위 gate 비활성. Gradle 빌드는 Claude가 실행한다.
.\gradlew.bat :logic:test :app:assembleDebug --no-daemon --console=plain
# 공식/실측 단위 확인 후에만:
.\gradlew.bat :logic:test :app:assembleDebug -PnaviDistanceUnit=meters --no-daemon --console=plain
# 제출 release는 기존 로컬 서명 설정 사용. APK 검사도13/1.1.0을 요구한다.
# .\gradlew.bat :app:assembleRelease -PnaviDistanceUnit=meters --no-daemon --console=plain
```

## 검증

- JVM227/227: 기존196 유지 + 신규31. 융합1~5, 거리/속도/각도/시간 경계, 누락/지연/만료/재탐색, 복수 TBT, 다른 쪽 깜빡이, 실제 reducer→융합 연결, 데이터/집계 왕복·빈/카드 있는 v1 읽기·캐시 무효화 포함. `.local/w9-jvm-tests.txt`.
- 전체 앱 Kotlin 직접 타입 검사: 실제 SDK/Android36/car JAR 대상으로 Debug/Release 검증. Gradle 리소스 병합/서명/DEX/APK 실행의 증거는 아니다.
- Node66/66: 서버27 + 기존 리플레이26 + 제출10 + 내비3. `.local/w9-node-tests.txt`. HTTP 업스트림은 모의이며 geo 시험은 DryRun으로 adb를 호출하지 않는다. 기존 D 실패2건은 변경된 코스팅 입력에 시험의 구간 선택 조건을 맞췄으며 시간/감속 상한 단언은 보존했다.
- 제출 소스 검사: 텍스트91개에서 소스 패턴 검출0건. **기존 Git 커밋 메시지 이메일18건 때문에 전체 검사는 실패**하며 이를 숨기거나 이력을 수정하지 않았다. 제외 경로 추적38개도 제출 전 별도 정리가 필요하다. 실제 APK/서명은 미검증. `.local/w9-submission-check.txt`.
- 실제 Gradle/AVD/TBT/E 검증은 Claude 인계. 단위·실제 시연 경로가 확보되기 전 ‘내비 확인 실측 통과’로 보고하지 않는다.

공식 근거: [리스너](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners), [초기화/해제](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/initialization-and-release), [목적지](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations), [거리 상세(내용 없음)](https://document.pleos.ai/api-reference/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/t-b-t-info/distance), [Android emulator console](https://developer.android.com/studio/run/emulator-console).

## Claude AVD 실측 (2026-10-23, 1.1.0)
- NaviHelper 연결·이벤트는 동작: 서비스 버전 3.0.4, `onRouteStateInfo` 주기 수신, 앱 로그 `drive.navi.guidance value=SAFE_GUIDING`(경로 없는 안전운전 모드), `drive.navi.tbt value=missing`(TBT 빈 목록 — 내비 쪽 로그도 `TASUseCase tbtData = []`).
- 내비 엔진 맵매칭: 위치 (127.094681, 37.4127212), `roadid` 무효값·`sat(0)` → 도로에 매칭 안 됨. `adb emu geo fix`로 바꿔도 내비 위치 그대로(내비는 별도 NMEA 소스 사용, NmeaParser 체크섬 경고 다수).
- 시연 설정에서 `requestRoute` 1회 전송 확인(첫 시도), 안내 시작은 확인 못 함. 두 번째 시도는 지도 화면이 앞에 와 입력이 지도에 들어감.
- 내비 엔진에 `n42simulation::api_route_simul`(경로 시뮬레이션) 흔적 — 경로가 잡히면 모의 주행으로 TBT를 만들 가능성. 미확인.
- 결론: 융합 로직은 JVM 시험 227/227로 검증, **에뮬레이터에서 실제 TBT로 시연하는 경로는 미확보**. 설명회 질문 7(TBT 시험 방법)로 확인 필요.

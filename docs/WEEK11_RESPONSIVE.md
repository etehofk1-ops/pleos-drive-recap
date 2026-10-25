# 11주차 차량 반응형 · 1.3.0(15)

2026-10-23. 이번 변경은 화면 배치·재생성·테마와 시험 절차다. 기록 서비스, 실제/모의 내비 원천, 판정 엔진·AI 계약·시나리오 입력은 변경하지 않았다.

## 구현

| 창 폭(dp) | 배치 | 여백·조작 |
|---|---|---|
| 0~599 | 복기 카드 한 열, 요약 줄바꿈 | 좌우16dp, 선택 버튼 세로 |
| 600~999 | 복기 카드 한 열 넓게 | 좌우24dp |
| 1000 이상 | 왼쪽 요약·한마디·버튼 / 오른쪽 카드 | 좌우24dp, 열 간격16dp, 두 열 독립 스크롤 |

방향 잠금·configChanges·고정 aspect/크기·호환성 선언 없이 기본 Activity 재생성을 쓴다. 모든 Activity와 application에 resizeableActivity=true. 복기 외 메인/진단/내비 입력은 한 열 스크롤을 유지하며, 좁은 진단의 선택 버튼은 세로로 배치한다. 좌석 선택의 2×2는 공간 의미를 보존한다. main은 실제 root 폭 변경도 감지한다.

핵심 제목/요약24sp 이상, 본문18sp 이상, 보조14sp 이상. 버튼64dp 이상(기존 진단88dp), 제목 길게 누르기 영역48dp 이상. 긴 본문은 측정된 세 줄 이하 문단으로 나눠 모든 근거를 표시한다. 글자 자동 축소/말줄임을 사용하지 않는다. native Material의 values/values-night 리소스 테마로 시스템 uiMode를 따르며 AppCompat 의존성은 추가하지 않는다. 동일한 순수 팔레트를 모든 View가 사용한다. foreground/muted/accent/success 각각을 background/surface/button에 올린24가지 색 조합을 JVM에서4.5:1 이상으로 검사했다. 물리적 글자 크기와 실제 렌더링 대비는 별도 측정 대상이다.

## 재생성 상태와 안전

- `onRetainNonConfigurationInstance`에 Context/View 없는 데이터만 보관한다. 화면/문제·스크롤 anchor·페이지 offset·대화상자 종류·초안·미적용 토글·시작 대기 시각·완료된 AI 결정 등을 넘긴다. 대화상자는 새 Activity에서 다시 만들며 저장/삭제/기록 시작을 자동 재실행하지 않는다.
- main은 기본 화면/페이지/스크롤 dp만 Bundle에 저장한다. **내비 목적지·진단 초안·게임 차량 값·대화상자 입력은 Bundle/파일/설정에 직렬화하지 않는다.** 내비 입력의 커서/포커스도 메모리로만 보관한다. 이미 사용자가 저장한 AI 설정은 기존 Provider 저장 방식을 따른다.
- 저장한 SafetyState로 잠금을 해제하지 않는다. 새 Car 연결/UX/기어/속도 확인 전 `WAIT`로 본문을 제거한다. 알려진 비P/UX 제한/비정상 또는 비0 속도는 `DISCARD`로 보관 상태까지 버린다. 정상 신호를 모두 받은 `READY`에서만 복원한다. 실시간 신호가 끊겨도 진행을 버린다.
- 기존 게임은 새 좌석 조회를 기다린다. 동일 상태면 동일 문제·기억 baseline을 유지하고, 값이 바뀌면 원래 무효화 규칙으로 새 문제를 낸다. 재생성 당시 진행 중이던 fresh GET은 새 클라이언트에서 다시 요청한다. 탐침은 새 구독으로 돌아오며 오래된 원시 값을 복원해 표시하지 않는다.
- 스크롤은 px 대신 논리 항목 key+dp 상대 offset으로 넘긴다. 넓은 화면의 두 위치를 보관하고 한 열로 바뀌면 마지막 스크롤한 열의 항목으로 돌아온다. 줄바꿈/높이 변화 때문에 같은 **항목·상대 위치**를 기준으로 하며 절대 px 동일성을 요구하지 않는다.
- 프로세스 종료/강제 종료 때 진단 초안·게임/모의 토글은 버린다. 이는 개인정보 비저장 규칙이며 일반적인 크기/방향/uiMode 재생성과 다르다. 기록 서비스는 별도 프로세스로 UI 재생성과 독립적이다. 이번에 서비스/구독/FGS 수명은 수정하지 않았다.

## 바뀐 파일

- `app/build.gradle.kts`:15/1.3.0.
- `app/src/main/AndroidManifest.xml`: 방향 고정 제거, 재생성·리사이즈 허용, DayNight 테마.
- `app/src/main/res/values/styles.xml`, `values-night/styles.xml`: 시스템 주간/야간.
- `app/src/main/java/lab/pleos/observe/VehicleUi.kt`: dp/팔레트·anchor·세 줄 문단·대화상자 스타일.
- `MainActivity.kt`: 폭 분기·복기 두 열·반응형 본문·메모리 checkpoint·대화상자 복원.
- `LegacyLabActivity.kt`: 진단/탐침/게임의 checkpoint·새 값 확인·스크롤·좁은 버튼·테마.
- `NaviDemoActivity.kt`: dp 전환·메모리 입력/커서/포커스·연결 콜백 때 입력 보존·테마.
- `logic/.../VehicleLayout.kt`, `logic/src/test/.../VehicleLayoutTest.kt`: 폭·팔레트/대비·복원 gate/새 snapshot 확인·15개 시험.
- `scripts/capture-responsive.ps1`, `scripts/test-responsive.mjs`: 실행 기본값은 오프라인 계획, 크기/테마 변경·복원·PNG/구성 캡처와 사전 입력 검증.
- `scripts/check-submission.ps1`, `scripts/test-submission.mjs`: 제출 APK15/1.3.0 검사/모의 fixture.
- `README.md`, `docs/WORK-CHECKLIST.md`, 본 문서.

## Claude 확인 순서

1. `gradlew.bat :logic:test :app:assembleDebug --no-daemon --console=plain`. Debug APK 설치. release는 기존 서명 절차로 별도 빌드한다. APK/실행/시각 검증은 이번에 수행하지 않았다.
2. P·속도0·UX 해제에서 D 또는 F 시나리오로 복기 카드를 준비한다. 기본 디스플레이에서 기록 시작→PID 확인→복기 페이지로 간다. 실제 SDK 내비와 모의 내비 검증은 기존 W9/W10 인계를 따른다.
3. `capture-responsive.ps1 -Action Inspect -Apply`로 원래 크기/density/config를 확인한다. `-AdbPath`와 `-Serial`을 지정한다. 아래 상태 각각에서 **앱 재실행 없이** `-Action Resize -Size 840x1212 -Apply`, 중간 폭 px, `-Size 2560x1440 -Apply`를 반복한다. 예시 px가 해당 dp 분기를 보장하지 않으므로 실제 앱 창 dp를 먼저 확인한다.
4. 세로1칸/가로2칸에서 기능 동일·카드 잘림 없음·스크롤 가능·큰 글자/버튼·핵심 문구 노출 확인. 599/600/999/1000dp 경계는 JVM으로 확인했고 실제 분기값은 Debug 하단에서 확인한다.

| 시험 상태 | 유지되어야 하는 것 |
|---|---|
| 복기 카드 중간까지 스크롤 | 복기 페이지·페이지 offset·동일 카드, 넓은 화면의 좌/우 스크롤 |
| AI 서버 설정을 열고 저장 전 주소 수정 | 열린 대화상자·초안·커서·오류, 저장 동작은 재실행 안 됨 |
| 숨은 모의 내비 대화상자의 미적용 토글 | 열린 대화상자·체크 상태, 적용 전 서비스 설정은 불변 |
| 기록 삭제 확인 대화상자 | 계속 확인을 기다림, 재생성만으로 삭제하지 않음 |
| AI ON/OFF·Debug 속도 시연 체크 | 선택 상태, 실제 기록은 중복 시작/중지 안 됨 |
| 진단·신호 탐침 중간 스크롤 | 동일 화면/항목, 새 조회·구독 값만 표시 |
| 내비 목적지 위도/경도/POI 입력 중 | 입력·커서·포커스·스크롤, 파일/Bundle 저장 없음 |
| 기존 숫자/자리 문제·무엇이 바뀌었나 | 동일 값이면 진행 유지, 새 좌석 값이 다르면 원래 규칙대로 무효화 |
| resize 직후 D/UX 제한·속도>0 | 복원되지 않고 본문 제거·대화상자 닫힘, P 복귀는 초기 화면 |
| 기록 중 반복 resize·uiMode 변경 | `pidof lab.pleos.observe:drive_recorder` 동일·drive.* 콜백/카운터 연속 |

5. `-Action Day/Night -Apply` 각각을 적용해 같은 상태 복원과 대비를 확인한다. 기본 디스플레이는 `-Action Capture -Label <고유이름> -Apply`로 PNG와 구성 기록.0.25초 반응/물리적5mm·3mm/최소 픽셀 높이는 실화면·DPI 근거로 확인하며 오프라인 통과로 간주하지 않는다. 녹화/캡처 때는 비공개 입력을 쓰지 않는다.
6. FormFactorConsole 외형 시험: standalone AVD·Go1.22+·8095/8096→Host Display Bridge8096 수신 확인→에뮬레이터 FormFactorConsole→12.9 Portrait 및14.6 Landscape→표시 dp/DPI/px 기록→가상 디스플레이 생성→DISPLAY2 앱 목록에서 실행. `UNAVAILABLE`이면 Bridge/Refresh, `DISCONNECTED`·DISPLAY2 없음이면 생성 준비 상태다. **DISPLAY2를 제거하면 앱 강제 종료**이므로 같은 창에서 하는 재생성/서비스 시험과 구별한다. capture 스크립트는 기본 display용, DISPLAY2는 해당 호스트 창을 캡처한다.
7. 시험 후 `-Action Restore -Apply`로 최초 override/야간 모드를 복원한다. 같은 serial로 다른 AVD를 쓰기 전 이전 `.local/responsive/*-baseline.json`을 별도 이름으로 보존한다. custom 야간 스케줄은 자동 변경을 거절하므로 원래 스케줄을 유지하며 수동 시험한다.

정지된 시험 AVD를 별도 창으로 시작할 때만 `emulator.exe -avd Pleos_Connect_v2_1 -skin WxH`를 사용한다. 실행 중 AVD 중복 기동/force-stop/install을 크기 변경 시험 중간에 하지 않는다. density는 스크립트에서 변경하지 않는다. Host Display Bridge 다운로드/Go 캐시도 `.local/` 아래에 둔다. 기본 파일 경로 명령은 README 참조.

## 검증 근거와 미확인

- 순수 JVM **257/257**(기존242+15): 폭 경계/음수 거부, 한 열 전환 anchor, 두 테마 텍스트 대비, 새 안전 신호 gate, 동일/변경/미완성 snapshot·기억 baseline·진단 복원.
- Node **73/73**(기존70+3): 기존 서버/리플레이/내비/제출 회귀 + 캡처 스크립트 계획/잘못된 인수/manifest·테마 정책. 실제 ADB/PNG 수신 시험이 아니다.
- 실제 Android36/car·Vehicle2.3.2·NaviHelper3.0.5/navi-data3.0.4 JAR 대상 Debug/release Kotlin 직접 타입 검사. Gradle 리소스 병합/서명 APK 시험과 구별한다. BuildConfig는 각 변형의 임시 stub이다.
- 근거 파일: `.local/w11-jvm-tests.txt`, `.local/w11-node-tests.txt`, `.local/w11-debug-types.txt`, `.local/w11-release-types.txt`. 코드 검사 후 최종 결과를 체크리스트에 갱신한다.
- 미확인: Gradle 빌드/실제 Activity 재생성·스크롤·IME/대화상자·실제 PNG/서비스 PID·모든 FormFactorConsole 프리셋·실측 반응 시간/글자 mm·심사 호환성. **Claude가 빌드·AVD 확인**한다.
- 제출 소스 검사에서 Kotlin 수신자 표기를 이메일로 인식한 오탐은 코드에서 해소했다. 현재 Git 메시지의 이메일 패턴22건과 제출 제외 추적40개는 기존 이력을 수정하지 않고 별도 인계한다. 소스 패턴0건과 전체 제출 검사 실패를 구분한다.

## 근거

- [Pleos 폼팩터 시험](https://document.pleos.ai/docs/pleos-only/app-test-guide/form-factor-test-guide): 프리셋/Orientation·dp/DPI/px, standalone/Bridge 조건, DISPLAY2 제거 시 강제 종료.
- [Android 구성 변경](https://developer.android.com/guide/topics/resources/runtime-changes): 크기·밀도·방향·uiMode 변경에 따른 재생성.
- [Activity 메모리 보존](https://developer.android.com/reference/android/app/Activity#onRetainNonConfigurationInstance()): 비구성 데이터만 메모리로 전달, 기존 Activity/View 참조 보관 금지.
- 사용자 제공 공식 화면규격 조사 문서를 읽어 분기·타이포/대비·양방향·상태 보존 요구에 반영했다. 문서에 실제 앱 창 px/dp 고정값은 없으며 임의로 프리셋 수치를 확정하지 않았다.

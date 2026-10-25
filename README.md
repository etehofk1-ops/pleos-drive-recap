# 오운복 — 오늘의 운전 복기

## 앱 이름 및 한 줄 소개

**오운복**(오늘의 운전 복기) — **점수 대신, 내가 몰랐던 순간.** 운전 중에는 차량 신호를 구간 요약으로 기록하고, 차를 세운 뒤 깜빡이 사용과 감속 순간을 돌아보는 Pleos Connect 앱입니다.

**1.6.0 / versionCode 21 — 제출 버전.** 시속 25km 이상의 차선 변경을 사건이 끝난 뒤 최대 약 9초 늦게, 사건 앞뒤 0.5초를 기준선으로 삼는 창으로 판정합니다(핸들 영점·도로 곡률 영향 제거). 공개 실주행 데이터(현대·기아 8차종 74분)에서 현대 25/32, 기아 16/28을 잡았고, 낸 카드의 61%가 자이로 센서로 옆 이동이 확인됐습니다(1.5.2는 4/32·0/28, 43%). [설계와 검증](docs/WEEK16C_DESIGN.md) · [Kotlin 이식·시험 변경](docs/WEEK16C_KOTLIN_PORT.md) · 이전 실패 시도 [1차](docs/WEEK16_REALDATA_REDESIGN.md)·[2차](docs/WEEK16B_EVENT_CURVE.md).

앱은 AI 없이 규칙 엔진으로 동작하며, AI 보조를 켜면 애매한 조향 분류와 복기 문장을 보탭니다. 차선 변경은 신호 패턴에 따른 **추정**이며 실제 차선 이동·안전성·법규 준수를 확정하지 않습니다.

1.5.2는 두 번째 꺾음 뒤 작은 보정을 최대2초 관측하고 정착된 방향으로 비대칭 차선 변경을 판정합니다. 전역 방향 복귀 기준은 유지하며 큰 왕복 조향의 정착 증거에만 제한된 보완 기준을 적용합니다. 약한 조향은2초 관측이 끊기면 확정하지 않습니다. **합성 성능 목표는 일부 미달**이며 [정착 창 상수·8조건 전후 평가·CSV3건·변경 시험·Claude 절차](docs/WEEK15_ASYMMETRIC_SETTLING.md)를 확인해야 합니다. AVD 두 회차와 실주행1분 CSV를 상시 회귀로 사용했으므로 이3건은 독립 평가 자료가 아닙니다. 초기3초 보정과 미확인 차량별 조향비/축간거리 가정도 남아 있습니다. [14주차 당시 결과](docs/WEEK14_SPEED_NORMALIZATION.md)는 별도로 보존합니다.

1.4.1은 백그라운드/프로세스 복원 대기(최대30분), 복기 틀 즉시 표시·화면 근처 카드만 생성, 화면용 속도1Hz 요청·주행 로그 절감 수정입니다. [원인·변경 파일·독립 am kill/시간/CPU 재시험](docs/WEEK13_RUNTIME_FIXES.md). 기존 AVD 결과와 이번 오프라인 검증을 구분하며 새 APK 성능은 Claude가 다시 측정합니다.

## 기능

- P에서 기록 시작/중지. 주행 중에는 플랫폼 차단과 앱 잠금을 유지하고 별도 foreground service가 요약을 만듭니다.
- 깜빡이 놓침·늦게 켬·사전 사용·급제동 후보를 근거 수치와 함께 표시합니다. `규칙 판정`, `AI 추정`, `애매함`을 구별합니다.
- 정차 후 오늘 집계·복기 카드·오늘의 한마디·맞아요/아니에요 피드백·기록 삭제를 제공합니다.
- 시작 화면 `AI 보조 사용`과 `AI 서버 설정`에서 사용 여부/서버 주소를 바꿉니다. 기본 주소는 `http://10.0.2.2:8787`입니다. HTTPS origin 또는 이 에뮬레이터 호스트의 HTTP만 허용합니다. 주소 변경 시 진행 중 요청과 이전 문장 캐시를 무효화합니다.
- 서버/키 없음·AI OFF에서는 규칙과 기본 문구로 돌아갑니다. 분류 제한3초/문장 제한20초입니다.
- NaviHelper 안내와 조향을 융합해 `내비 확인`, `내비 보조`, `추정`을 구별하고 회전 깜빡이도 복기합니다. **거리 단위는 아직 미확인**이므로 기본 빌드는 거리 기반 승격을 보류합니다. m 단위 검증 후 아래 옵션으로 활성화합니다. 실제 TBT/경로 시연은 확인 전입니다.
- Debug 숨은 메뉴의 **시연용 모의 내비**는 기본 OFF입니다. ON이면 메인·복기에 경고 배지를 표시하며 카드도 `내비 확인(모의)`/`내비 보조(모의)`로 구분합니다. release에는 입력 수신기와 토글이 없습니다. 실제 NaviHelper 경로와 선택해 사용하며 기록 도중 원천을 섞지 않습니다.

## 차량 신호와 SDK

화면은 공식 토큰을 사용하는 평면 타일·회색 구분선·반전 선택·단일 주 버튼으로 구성합니다. 메인은 기록/오늘 복기/AI 보조 타일, 복기는 전체 일자 집계 숫자와 AI 한마디·판정 아이콘·근거·맞아요/아니에요 탭을 제공합니다. 글꼴은 시스템 `sans-serif`만 사용하며 폰트 파일·브랜드 로고·전용 AI 이름/색을 포함하지 않습니다. [토큰 표·화면 변경·AVD 확인](docs/WEEK12_PLEOS_DESIGN.md).

시각 확인은 P에서 **메인→기록 시작→D 잠금/기록 지속→P 복기→넓음(요약/목록 두 열)→좁음(한 열)→주간/야간→팝업/누름·취소/피드백** 순서입니다. 기존 `capture-responsive.ps1`로 같은 실행 창을 리사이즈해 스크롤·초안·토글·서비스 PID도 확인합니다. 작은 글자에 대비가 부족한 원본 상태색은 아이콘/배지 면에 쓰고 글자는 대비를 만족하는 무채색으로 표시합니다.

| 용도 | SDK/API |
|---|---|
| 핵심 기록 | `ai.pleos.playground:Vehicle:2.3.2` TurnSignal / SteeringWheel / Brake 조회·구독 |
| 주행·잠금 | AAOS `CarPropertyManager`: `CURRENT_GEAR`, `GEAR_SELECTION`, `PERF_VEHICLE_SPEED`(m/s), `CarUxRestrictionsManager` |
| 숨은 진단 | 제목 길게 누르기. 창문/독서등, Odometer·Range·EV Battery·Ambient Light·Door·Seat·Wiper 탐침. 기존 관찰 게임도 진단 뒤에 유지 |
| 선택형 AI | 로컬 `ai-proxy/`: Jev로 애매한 조향 분류, OpenCode Go로 한국어 문장 생성 |
| 내비 | `NaviHelper:3.0.5` 기본 포함 / `navi-data:3.0.4`, `getRouteStateInfo`, `getTBTInfo`, `onNavigationStatus`, `onTBTInfo`. 기록 프로세스에서 메모리 융합 |

앱은 차량 상태 조회를 사용합니다. 숨은 내비 시연 설정에서는 P 상태에서 명시적으로 `requestRoute/cancelRoute`를 호출합니다. Console 리플레이는 에뮬레이터 시험 도구입니다. DO를 선언하지 않았습니다. 공개 SDK 키를 하드코딩하지 않았으며 AI 인증은 로컬 서버에서만 처리합니다.

## 실행 방법

### APK 설치

`Pleos_Connect_v2_1` AVD를 부팅하고 실제 serial을 확인합니다.

```powershell
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
& $adb devices -l
$serial = 'emulator-5554'
$apk = @(Get-ChildItem .\apk\*_release.apk)
if ($apk.Count -ne 1) { throw 'apk 폴더의 제출 APK 한 개를 확인하세요.' }
& $adb -s $serial install -r $apk[0].FullName
if ($LASTEXITCODE -ne 0) { throw '설치 실패. 기존 debug와 서명이 다르면 별도 AVD를 사용하세요.' }
& $adb -s $serial forward tcp:8088 tcp:8088
& $adb -s $serial shell am start -n lab.pleos.observe/.MainActivity
```

debug와 release는 서명이 달라 덮어쓰지 못할 수 있습니다. 기존 기록을 보존하려면 별도 AVD를 사용합니다. 기존 앱 삭제는 기록도 지우므로 필요한 보관 여부를 먼저 판단합니다.

### 로컬 AI 서버(선택)

Node20 이상, npm 설치/외부 패키지 없이 실행합니다. 서버 프로세스 환경에 `TYPESAFE_API_KEY`, `OPENCODE_GO_API_KEY`를 제공한 뒤 프로젝트 루트에서 실행합니다. 키를 소스·APK·명령 인수·로그에 넣지 않습니다.

```powershell
$env:RECAP_LLM_PROVIDER = 'opencode-go'
$env:RECAP_LLM_MODEL = 'deepseek-v4.1-flash'
$env:RECAP_LLM_FALLBACK_MODEL = 'minimax-m3.1-flash'
$env:RECAP_LLM_MAX_TOKENS = '1200'
node .\ai-proxy\server.mjs
```

Go 환경 키가 없으면 서버는 로컬 `~/.aside/u/0/credentials.json`의 `opencode-go` 항목만 읽습니다. 이 파일과 실제 키는 저장소에 없습니다. Jev는 환경 키만 사용합니다. 기본 바인딩 `127.0.0.1:8787`, AVD의 `10.0.2.2:8787`이 PC의 같은 서버를 가리킵니다. `Invoke-RestMethod http://127.0.0.1:8787/health`는 서버 기동만 확인하며 AI 성공을 뜻하지 않습니다.

release 앱에서 `AI 서버 설정`으로 기본 주소를 확인·저장합니다. 다른 서버는 HTTPS여야 합니다. 주소에 인증정보·쿼리·경로를 넣지 않습니다. 외부 공개/방화벽 개방은 필요하지 않습니다. Go는 개발·시연용이며 출시 공급자는 별도 검토 대상입니다. [서버 설정](ai-proxy/README.md) · [AI 계약](docs/AI_CONTRACT.md).

### 기록과 리플레이

1. P·속도0·UX 해제에서 권한 승인/기록 시작. 깜빡이·브레이크·조향의 GET 또는 notify로 `초기 신호 준비됨`을 확인하고3초 이상 기다립니다.
2. PC `http://127.0.0.1:8088` Console의 DevTools에 `scripts/recap-replay.js`를 붙입니다. `await driveRecapReplay.runFile()`로 `scripts/recap-scenario-d-demo.json`을 선택합니다.
3. 출발→정상/놓침/늦은 차선 변경→신호 대기→저속 우회전→급제동→P입니다. 기준74초+속도 대기/HTTP 비용이 걸립니다. caption은 콘솔 출력만이며 소수 속도는 preflight가 정수로 바꾸고 알립니다. 실행 중 Console을 직접 조작하지 않습니다.
4. D에서는 화면이 가려져야 합니다. P 복귀 후 복기 기대는 좋아요1·놓침1·늦음1·급제동1, 카드4·애매함0입니다. 저속 회전은 숨깁니다. 문장은 준비 중→AI 문장 또는 기본 문구입니다.
5. `await driveRecapReplay.stop()`은0·중립·Off·P 복원을 시도합니다. 실패 시 Console에서 상태를 확인합니다. 서버 중지/AI OFF로 규칙 폴백도 확인합니다.

### 시연용 모의 내비 · 시나리오 F (Debug)

이 AVD는 Claude 실측상 GNSS/NMEA 체크섬 문제로 실제 내비 TBT가 비어 있습니다. **F는 모의 내비를 사용하는 시연이며 실제 TBT 실측 증거가 아닙니다.** 실제 `PleosNaviSource`는 유지하고 별도의 `DemoNaviSource`만 선택합니다.

1. `gradlew.bat :logic:test :app:assembleDebug --no-daemon --console=plain`로 빌드·설치합니다. 모의 입력은 m 단위로 명세했으므로 `-PnaviDistanceUnit=meters`가 필요 없습니다. 이 옵션은 실제 SDK 거리 검증용으로 남겨 둡니다.
2. P·속도0에서 제목 길게 → **시연용 모의 내비** → 토글 ON·적용. 기록을 시작하고 **초기 신호 준비됨**을 확인합니다. 켜짐은 서비스 프로세스 메모리에만 있으며 프로세스 재시작 시 OFF입니다. 기록 도중 토글 변경은 거부합니다.
3. ADB forward 후 PC의 Console 브라우저 탭을 모두 닫습니다. 웹 페이지의 physicsTick이 HTTP 입력을 덮어쓰는 것을 막기 위해서입니다. Console 서버와 AVD는 계속 켜 둡니다.
4. 아래 공급기를 실행하고 Enter를 누릅니다. **공급기가 F JSON의 Console HTTP 조작과 adb 내비 방송을 같은 시계로 함께 재생합니다.** 추가 서버·시작 신호 파일·두 프로그램의 동시 시작이 필요 없습니다. 기존 브라우저 `runFile()`에 F를 넣으면 오류로 안내합니다.

```powershell
& $adb -s $serial forward tcp:8088 tcp:8088
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/demo-navi-feed.ps1 -DryRun
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/demo-navi-feed.ps1 -Serial emulator-5554
```

5. 기준73초에 속도 대기·HTTP/adb 비용이 추가됩니다. 1초마다 마지막 시나리오 내비 상태를 갱신하고, 앱은 마지막 입력 후3초가 지나면 만료시킵니다. 속도 대기 때도 공급을 유지합니다. 종료/오류/Ctrl+C 시 P·속도0·중립·Off 복원을 시도하며 복원 실패는 직접 확인합니다.
6. P 복귀 후 기대 카드4개·애매함0: **우회전 좋아요/내비 확인(모의)**, **깜빡이 없이 유턴/내비 확인(모의)**, **깜빡이 없는 차선 변경/내비 보조(모의)**, **안내 없는 차선 변경/추정**. 상단에는 **시연용 모의 내비 입력 사용 중**이 표시됩니다. 자세한 시각표·수동 방송·검증은 [W10 인계](docs/WEEK10_DEMO_NAVI.md)를 따릅니다.
7. 공급기를 끊고3초 뒤 만료 로그/상태와 기존 추정 복귀를 확인합니다. 기록 중지 후 토글 OFF로 실제 원천을 선택합니다. 저장된 모의 카드에는 출처가 남으므로 OFF/재시작 뒤에도 모의 표시가 유지됩니다. 실기록·최근 패턴과 섞지 않도록 시험 전후 **기록 삭제**를 사용합니다.

### 실제 내비 융합 시나리오 E (정상 GNSS 환경용)

1. 먼저 기본 빌드에서 P 상태로 기록을 시작합니다. 제목 길게 누르기 → **내비 시연 설정**에서 공개 시험 목적지 위도/경도·필요시 POI ID로 목적지를 요청합니다. 값은 지도 앱에만 전달하고 화면 이탈 시 지웁니다. 검색 성공/실제 안내 시작은 별개입니다. 지도 앱에서 직접 안내를 시작하는 방법도 가능합니다.
2. logcat에서 `event=drive.navi.guidance`와 `event=drive.navi.tbt`를 확인합니다. `ROUTE_GUIDING`/`SIMULATION_GUIDING` 또는 `ROUTE_DRIVE`와 실제 `TURN_RIGHT`/`TURN_UTURN`을 받아야 합니다. 앱은 위치·목적지 조회를 호출하지 않습니다.
3. **거리 의미·단위 확인:** 지도에 100m·50m가 표시되는 서로 다른 지점에서 `distanceRaw`를 대조하고, 이동할수록 다음 동작까지의 잔여거리로 줄어드는지도 확인해 근거를 남깁니다. AAR의 Double 타입과 빈 상세 문서만으로 잔여 m라고 확정하지 않았습니다. 둘 다 확인 후 `-PnaviDistanceUnit=meters`로 다시 빌드합니다. 옵션이 없으면 회전/진출 거리 판정은 보류합니다. `includeNaviProbe`는 제거했습니다.
4. 위치 경로는 `scripts/recap-navi-route-template.json`을 `.local/navi-e-route.json`에 복사해 **해당 AVD 지도에서 확인한 공개 시험 도로의 좌표**로 채웁니다. 템플릿의 null은 실행을 거부합니다. 오른쪽 회전·유턴이 실제 안내 경로에 있는지 확인하고 각 phase의 조향 시점과 거리≤50m를 맞춥니다. 임의 좌표로 TBT가 발생한다고 가정하지 않습니다.

```powershell
# 먼저 입력만 검사하며 adb는 호출하지 않음
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/recap-navi-location.ps1 -Phase right-turn -DryRun
# 지도 경로와 조향 시작 시점을 맞춘 다음 실행 (longitude, latitude 순서)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/recap-navi-location.ps1 -Phase right-turn -Serial emulator-5554
# 단일 위치 확인도 가능. 값은 직접 확인한 공개 시험 지점을 사용
# & $adb -s $serial emu geo fix <longitude> <latitude>
```

5. Console DevTools에 기존 `scripts/recap-replay.js`를 붙인 뒤 아래처럼 E JSON을 선택합니다. `runFile()`에 E 전체를 바로 넘기지 않고 phase별로 실행합니다. 각 phase는 P로 끝나므로 내비 안내와 위치 재생을 다시 맞출 수 있습니다. 속도 대기 때문에 실제 시작은 고정 초와 달라질 수 있습니다.

```javascript
const e = await new Promise(resolve => { const f = document.createElement('input'); f.type='file'; f.accept='.json'; f.onchange=async()=>resolve(JSON.parse(await f.files[0].text())); f.click(); });
driveRecapReplay.preflight(e.phases['right-turn']);
await driveRecapReplay.run(e.phases['right-turn']); // 실제 TURN_RIGHT ≤50m + 12km/h: 우회전 좋아요 / 내비 확인
// 실제 유턴 안내 지점으로 경로/위치 재생을 준비한 다음:
await driveRecapReplay.run(e.phases.uturn); // TURN_UTURN ≤50m: 깜빡이 없이 유턴 / 내비 확인
// P에서 내비 시연 설정의 안내 취소 또는 지도에서 안내 종료 후:
await driveRecapReplay.run(e.phases.lane); // 안내 없음: 차선 변경 놓침 / 추정
```

6. `geo fix`의 OK는 GPS 모의 입력 수락일 뿐 내비가 소비했다는 증거가 아닙니다. 지도/TBT가 바뀌지 않으면 그 결과를 미연동으로 기록하고 내비 앱의 자체 모의주행 제공 여부를 확인합니다. 현재 환경에서는 Console 접속이 실패했고 과거 카탈로그에는 위치/내비 전용 기능이 없었습니다. 현재 카탈로그는 다음 명령으로 다시 확인합니다. `position`만으로 검색하면 창문/좌석이 섞이므로 결과를 직접 분류합니다.

```powershell
$catalog = Invoke-RestMethod http://127.0.0.1:8088/vehicle/catalog
$catalog.features | Where-Object { ($_.id + ' ' + $_.label + ' ' + $_.domain + ' ' + $_.write_endpoint) -match '(?i)navi|route|gps|latitude|longitude|location|geo' } | Select-Object id,label,write_endpoint
```

[AAR 시그니처·융합 규칙·검증 경계](docs/WEEK9_NAVI_FUSION.md). 공식 [NaviHelper 초기화](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/initialization-and-release), [목적지 요청](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations), [에뮬레이터 geo 명령](https://developer.android.com/studio/run/emulator-console#geo).

## 테스트 환경 및 필요한 설정

| 항목 | 환경 |
|---|---|
| PC | Windows, Android Studio JBR17 이상, Node20 이상 |
| 차량 | Pleos Connect v2.1 / `Pleos_Connect_v2_1`, API34, x86_64 |
| 화면/Console | 세로·가로 리사이즈 대응, 시스템 주간/야간 / ADB forward 후127.0.0.1:8088 |
| 빌드 | minSdk28 / targetSdk35 / compileSdk36, Kotlin2.2.20, AGP8.13.0, Gradle8.14.3 |
| 권한 | 속도 조회 런타임 승인·알림 허용 권장. 거부/미수신은 잠금 또는 미확인 유지 |
| 네트워크 | HTTP 예외는10.0.2.2만, 그 외 HTTPS. 키는 서버에서만 사용 |

기존 개발 버전의 AVD 신호/백그라운드 수신과 D 구성은 확인했으나 **이번 release 서명·설정·최종 APK 실행은 확인 전**입니다. 소스/JVM/모의 시험과 실행 가능한 release 검증을 구분합니다.

## 차량 화면·해상도 시험 (Claude)

방향을 고정하거나 `configChanges`로 재생성을 우회하지 않습니다. `resizeableActivity=true`이며 시스템 주간/야간 테마를 따릅니다. **앱 창 폭 dp** 기준이며 디스플레이 px와 다릅니다.

| 앱 창 폭 | 복기 배치 | 버튼·요약 |
|---|---|---|
| <600dp | 카드 1열 | 선택 버튼 세로, 요약 줄바꿈 |
| 600~999dp | 카드 1열 넓게 | 요약 줄바꿈 |
| ≥1000dp | 왼쪽 요약·한마디·버튼 / 오른쪽 카드 | 두 열 독립 스크롤 |

핵심24sp·본문18sp·보조14sp 이상, 버튼64dp 이상입니다. 긴 문장은 내용 생략 없이 세 줄 이하 문단으로 나눕니다. 주간/야간 코드 색상 대비4.5:1 이상을 JVM 검사합니다. 실제 물리적 글자 높이5mm/3mm와 잘림·터치·반응 시간은 AVD/실화면에서 확인해야 합니다.

```powershell
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
# 기본값은 PLAN ONLY. -Apply 때만 선택한 에뮬레이터 조작.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/capture-responsive.ps1 -Action Inspect -AdbPath $adb -Apply
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/capture-responsive.ps1 -Action Resize -Size 840x1212 -AdbPath $adb -Apply
# 새 안전 신호 확인 후 화면 상태를 확인하고 캡처
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/capture-responsive.ps1 -Action Capture -Label portrait-day -AdbPath $adb -Apply
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/capture-responsive.ps1 -Action Resize -Size 1600x1440 -AdbPath $adb -Apply
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/capture-responsive.ps1 -Action Capture -Label medium-day -AdbPath $adb -Apply
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/capture-responsive.ps1 -Action Resize -Size 2560x1440 -AdbPath $adb -Apply
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/capture-responsive.ps1 -Action Night -AdbPath $adb -Apply
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/capture-responsive.ps1 -Action Capture -Label landscape-night -AdbPath $adb -Apply
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/capture-responsive.ps1 -Action Day -AdbPath $adb -Apply
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/capture-responsive.ps1 -Action Restore -AdbPath $adb -Apply
```

serial 기본값은 emulator-5554이며 `-Serial`로 선택합니다. px 예시는 공식 프리셋이 아닙니다. `wm density`, `am get-config`, Debug 하단의 dp를 확인해 세 폭 구간을 모두 시험하세요. 결과는 `.local/responsive/`에 PNG/크기·밀도·구성을 저장합니다. 같은 캡처 라벨을 덮어쓰지 않고, 최초 크기 override·야간 모드를 보관해 `Restore`로 복원합니다. density는 바꾸지 않습니다. 같은 serial로 다른 AVD를 시험하기 전 이전 baseline을 별도 이름으로 보존하세요. 비공개 좌표가 입력된 화면은 캡처하지 마세요.

수동 크기 변경은 `adb shell wm size WxH`입니다. `wm size reset`은 기존 override가 없었을 때만 원래 상태입니다. `emulator.exe -avd Pleos_Connect_v2_1 -skin 840x1212` / `-skin 2560x1440`은 **정지된 시험 AVD를 별도 창으로 시작하는 예시**이며, 실행 중 상태 보존 시험에는 재부팅 대신 `wm size`/창 리사이즈를 사용합니다. -skin만으로 앱 창 dp/차량 창 정책을 보장하지 않습니다.

[공식 폼팩터 가이드](https://document.pleos.ai/docs/pleos-only/app-test-guide/form-factor-test-guide): Go1.22+,8095/8096, 별도 창의 AVD를 준비하고 Host Display Bridge를 `.local/`에 풉니다. Go 캐시/임시 경로도 그 아래로 지정해 `go run . --avd "Pleos_Connect_v2_1"`로 실행합니다.8096 수신 로그→에뮬레이터 **FormFactorConsole**→Display preset·Orientation→**dp/DPI/px 기록**→가상 디스플레이 생성→DISPLAY2 앱 목록에서 실행합니다.12.9인치 Portrait·14.6인치 Landscape를 각각 확인하세요. UNAVAILABLE이면 Bridge 확인/Refresh, DISCONNECTED·DISPLAY2 없음이면 생성 준비 상태입니다. 스크립트는 기본 디스플레이 캡처용이므로 DISPLAY2는 해당 호스트 창을 직접 캡처합니다.

**가상 디스플레이 제거는 앱을 강제 종료합니다.** 외형 확인과 재생성 상태 시험을 구분하세요. 같은 실행 창에서 크기/방향/야간 모드를 바꾸며 복기·진단 스크롤, 열린 대화상자·입력·미적용 토글, 내비 목적지 입력과 기존 게임 진행을 확인합니다. 진단 초안/게임 값은 재생성 사이 메모리에만 유지하고 프로세스 종료 시 버립니다. 새 안전 신호를 기다릴 때 가린 뒤 복원하며 D/UX 제한/속도>0이면 보관 상태도 버립니다. 기록 서비스 PID·카운터 연속성도 확인합니다. [변경 파일·상세 확인표](docs/WEEK11_RESPONSIVE.md).

## 빌드

서명키는 빌드 담당자가 생성합니다. 비밀번호는 프롬프트에 입력하고 인증서에 실명·연락처·메일·소속을 넣지 않습니다. 아래는 개인 식별정보 없는 앱용 DN입니다.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
New-Item -ItemType Directory -Force .local | Out-Null
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v -keystore .local/release.keystore `
    -alias drive-recap -keyalg RSA -keysize 3072 -validity 10000 `
    -dname 'CN=Drive Recap,OU=App,O=Submission'
```

`.local/release.properties`를 로컬 편집기로 만들고 생성한 키와 일치하도록 네 항목을 채웁니다. `storeFile`은 프로젝트 루트 기준입니다. 비밀번호의 Properties 이스케이프 규칙에 유의하세요. 파일과 키는 gitignore 대상입니다.

```properties
storeFile=.local/release.keystore
storePassword=LOCAL_STORE_PASSWORD
keyAlias=drive-recap
keyPassword=LOCAL_KEY_PASSWORD
```

파일/필수 항목/키 파일이 없으면 release 빌드는 명확한 오류로 중단하고 unsigned/debug 서명으로 대체하지 않습니다. minify/resource shrink는 끄고 debuggable=false입니다. 속도 미수신 시연과 Debug 원시 감속 표시는 release에서 비활성입니다. 심사용 로컬 AI 예외만 release에도 적용합니다.

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
.\gradlew.bat :logic:test :app:assembleRelease --no-daemon --no-configuration-cache --console=plain
if ($LASTEXITCODE -ne 0) { throw 'release 빌드 실패. 이전 APK를 제출하지 마세요.' }
$team = Read-Host '제출 팀명'
$target = Join-Path 'apk' ($team + '_오운복_release.apk')
Copy-Item -LiteralPath .\app\build\outputs\apk\release\app-release.apk -Destination $target
```

의존성이 캐시에 있으면 `--offline`을 추가할 수 있습니다. 팀명은 아직 미정이며 자리표시자를 실제 APK로 간주하지 않습니다. 복사 뒤 최종 검사와 AVD 실행을 수행합니다.

내비 거리 m 단위의 공식 근거나 AVD 대조 검증을 확보한 뒤에는 같은 빌드 명령에 **`-PnaviDistanceUnit=meters`**를 추가합니다. 기본 `unverified`는 거리 기반 확정을 막습니다. 두 빌드 모두 NaviHelper는 기본 포함이며 내비 미수신이면 기존 규칙으로 동작합니다.

## APK 위치

**`apk/김승주_오운복_release.apk`** (팀명 김승주, 앱 이름 오운복). 서명된 release APK입니다. 심사 계정 초대는 공지 후 진행합니다. 소스+APK/release/README/접근 권한 요건은 [공식 지원·제출 방법](https://appdevchallenge.auto-hmg.io/guide/submission)을 따릅니다.

## 개인정보 처리

- 기록 서비스는 위치·VIN·경로·음성·이름·연락처·메일을 수집/저장하지 않습니다. 실제/모의 내비 동작·거리·안내 상태·방송 runId/sequence는 메모리에만 두고 버립니다. 요약에는 사건 시각·종류·근거 수치·피드백·최종 내비 판정과 모의 출처 Boolean만 있습니다. 숨은 시연 설정의 목적지 좌표는 사용자가 직접 입력해 NaviHelper로 요청하는 용도로만 사용하고 기록/AI/로그/파일로 보내지 않습니다.
- 상세 요약/피드백은 오늘·어제2일, 날짜와7개 건수(기존4 + 회전 미사용/늦음/사전사용) 집계만7일, AI 문장은 오늘 캐시만 유지합니다. 접근 시와 주기적 정리로 기간 밖 자료를 삭제하며 자정 정각 삭제는 보장하지 않습니다.
- 기록 삭제는 요약/피드백/집계/문장을 지웁니다. AI 사용 여부와 서버 주소는 유지합니다. Android 백업은 비활성입니다.
- AI ON은 허용한 요약 특징만 로컬 중계/제공자에 보냅니다. 정확한 시각은 시간대, 과거 날짜는 상대값으로 축소하며 위치·VIN·경로·원시 시계열을 보내지 않습니다. 외부 공급자 처리 정책은 별도 확인 대상입니다.
- 서버는 키를 응답/파일/로그에 남기지 않습니다. 실제 키·서명 재료는 저장소에 넣지 않습니다. release 로그는 원시 차량 값/Debug 숫자를 숨깁니다.

## 알려진 한계

- 에뮬레이터 정수 속도·순간 반영/지연 때문에 감속 측정값은 명령 평균과 다릅니다. 기존 D AVD 실측에서도21.4m/s²가 관측됐습니다. 약8~10m/s²라는 명령 목표는 실수신값 보장이 아니며 실차 정확도나 안전 효과의 증거가 아닙니다.15 초과는 `감속 큼(측정값 이상)`으로 표시합니다.
- 조향 좌우 부호 규약은 미확정이어서 조향만으로 좌우를 표시하지 않습니다. 내비 확인 회전의 좌/우는 SDK TurnType에만 근거합니다. 실제 차선 이동 관측 결과가 아닙니다.
- NaviHelper 타입/구독/융합 코드는 연결했으나 AVD TBT 실제 수신·거리 단위·시나리오 E 도로 경로는 미검증입니다. TBT 동작 통과 전용 콜백은 SDK에 없으며 목적지 도착을 회전 통과로 간주하지 않습니다. TTS/STT/FusedLocation은 사용하지 않습니다.
- DO 미선언 앱의 specialUse FGS 주행 중 수신은 AVD에서 확인했으나 **공모전 심사/배포에서 이 방식이 허용되는지는 미확인**입니다.
- AI 확률은 정답 확률이 아닙니다. 서버·키·모델이 없어도 규칙 복기는 유지합니다. Go는 개발·시연용입니다.

## 제출 검사

```powershell
# APK 없는 개발 단계: 소스 범위 검사
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-submission.ps1 -SourceOnly
# APK 복사 후: 서명/버전/비디버그까지 검사
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-submission.ps1
# 실명 대조가 필요하면 로컬 파일에 한 줄에 한 명씩 작성
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\check-submission.ps1 -NameListFile .local/submission-names.txt
```

매칭 원문 없이 파일/행/분류만 보고합니다. 소스 검사 성공은 제출 완료가 아닙니다. 최종 검사는 APK 한 개·실제 팀명 파일명·21/1.6.0·비디버그·유효 서명을 요구합니다. 현재1.6.0 후보는 회귀 실패로 제출 보류이며 해당 APK도 만들지 않았습니다. F는 Debug용이며 release의 실제 내비 입력과 구분합니다. build-tools의 aapt2/apksigner가 필요하고 `-Aapt2Path`/`-ApkSignerPath`로 지정할 수 있습니다. 화면/영상·무표식 실명·Git 작성자와 과거 파일 내용은 별도 검토 대상입니다. 현재 Git 커밋 메시지는 검사하지만 이력은 수정하지 않습니다.

오프라인 회귀: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-logic-offline.ps1`, `node --test ai-proxy/server.test.mjs scripts/test-recap-replay.mjs scripts/test-submission.mjs scripts/test-navi.mjs scripts/test-demo-navi.mjs scripts/test-responsive.mjs scripts/test-pleos-design.mjs`.

15주차8조건 측정: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/run-recap-sim-offline.ps1 -Week15Matrix -Count 200`。개별 개발 조건은 `-Seed 20261024 -Noise real -Asym`입니다. `MEASURED`는 측정 완료이며 성능 목표 통과 표시가 아닙니다. Gradle JVM 시험에서도 `replay/`의 CSV3건은 환경변수 없이 항상 실행됩니다.

W9 검증은 [인계 문서](docs/WEEK9_NAVI_FUSION.md)에 기록합니다. 기존 D 시험2건은 Claude 실측으로 바뀐 브레이크0 코스팅 구간을 선택하도록 수정하고 61단계·속도차·시간 간격·감속 상한 검사는 유지했습니다. 시나리오 D 입력과 앱의 급제동 규칙은 바꾸지 않았습니다. 오프라인 성공과 실제 release 실행 여부를 구분합니다.

## 제출 범위에서 제외할 목록

아래는 로컬 보존 자료이며 최종 제출 저장소/소스 내보내기에서 제외합니다. gitignore는 이미 추적된 파일/과거 이력을 제거하지 않으므로 제출 담당자가 별도로 제외해야 합니다. 기존 기록 삭제/Git 이력 재작성은 수행하지 않았습니다.

- `.git/` 기존 개발 이력, `.local/` 키/설정/검증, `.gradle/`, `.gradle-user-home/`, `.kotlin/`, `.idea/`, `.omc/`, `.agents/`, `.codex/`, 모든 `build/`·`node_modules/`, `local.properties`
- `docs/dev-notes/`: 루트 `_codex_log*`·`_codex_report*`·`SPEC_*` 원본 보존 이동 자료
- `docs/WEEK*.md`, `docs/WORK-CHECKLIST.md`, `docs/API_EVIDENCE.md`: 개발 기록/인계, 개인 환경 경로가 있을 수 있음
- `docs/evidence/`: 원본 화면/영상. 제출 영상은 별도로 개인정보를 검토한 결과만 선택
- `motion/`: 영상 제작용 작업 파일·원본 폰트/미디어
- 루트에 새로 생긴 `_codex_log*`, `_codex_report*`, `SPEC_*` 및 서명/환경 키/자격 파일

제출 핵심은 앱/logic 소스와 Gradle wrapper·설정, `ai-proxy/`, `scripts/`, 이 README, `docs/AI_CONTRACT.md`, 검증한 release APK입니다.

현재 개발 Git의 과거 공동작성자 커밋 메시지에서 이메일 패턴이 검출됩니다(건수는 최신 검사 결과 참조). 기존 이력은 보존했으며 검사에서 숨기지 않습니다. 제출 담당자는 검토한 소스만 내보내고 기존 `.git` 이력을 포함하지 않아야 합니다. 이 조치 전에는 현재 개발 저장소 검사 성공/익명화 완료로 보지 않습니다.

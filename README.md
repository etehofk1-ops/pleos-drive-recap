# 오운복

## 앱 이름 및 한 줄 소개

**오운복 — 운전 중에는 화면 없이 깜빡이·핸들·브레이크 신호만 기록하고, 차를 세우면 오늘 놓친 깜빡이와 급제동 같은 순간을 근거와 함께 한 장씩 돌아보게 해 주는 차량 앱**

Pleos Connect(AAOS) 앱입니다. 점수 대신 "내가 몰랐던 순간"을 돌려드립니다. 현재 버전은 **1.9.4 (versionCode 28)** 입니다.

## 실행 방법

Pleos Playground 개발 환경이 제공하는 `Pleos_Connect_v2_1` AVD를 부팅한 뒤 `apk/`의 release APK를 설치하고 실행합니다. 차량 신호를 넣는 Console(8088)은 이 AVD 안에서 이미 돌고 있어, PC에서는 포트만 연결합니다. 아래 명령은 저장소 루트(`pleos-drive-recap`)에서 실행하며, AVD가 여러 대면 `adb devices`로 번호를 확인해 `emulator-5554`를 바꿉니다. 재생 없이 결과 화면만 보려면 시연 영상(접수 메일의 링크)을 보면 됩니다.

```powershell
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb -s emulator-5554 install -r (Get-ChildItem .\apk\*_release.apk).FullName
& $adb -s emulator-5554 forward tcp:8088 tcp:8088      # 시나리오 재생용 Console
& $adb -s emulator-5554 shell am start -n lab.pleos.observe/.MainActivity
```

처음 실행하면 권한 창이 두 번 뜹니다. 첫 창은 차량 속도 권한인데 Android가 **"위치 정보"**라는 이름으로 묻습니다. 앱은 속도 값만 읽고 위치는 읽거나 저장하지 않습니다. 둘째 창은 기록 알림입니다. 둘 다 허용한 뒤 P에서 **기록 시작**을 누르고(권한 창 전에 누른 것은 무시되니 다시 누릅니다), "초기 신호 준비됨" 뒤 3초 기다린 다음 Console(`http://127.0.0.1:8088`)로 시나리오를 재생합니다([기록과 리플레이](#기록과-리플레이)). 다시 P로 놓으면 **오늘 복기**에서 카드를 한 장씩 봅니다. 기록을 중지하지 않아도 복기를 볼 수 있습니다. 기존 debug 앱과 서명이 달라 덮어쓰지 못하면 삭제 후 설치하세요(앱 기록도 지워집니다).

## 테스트 환경 및 필요한 설정

- **에뮬레이터**: Pleos Connect v2.1 (`Pleos_Connect_v2_1`), API 34, x86_64. 이 환경에서 1.9.2 release 설치·실행·기록·시나리오 재생을, 1.9.3 release 설치·실행·기록과 바뀐 카드 문구 표시, 시나리오 D 재생(카드 5장)을 확인했습니다. 1.9.4 release APK는 버전과 서명 인증서(1.9.3과 같음)를 확인하고 시나리오 D를 10회 재생해 매번 카드 5장을 확인했습니다. 1.9.4의 조향 경사가 차선 변경 시작을 0.5초가량 당겨, 시나리오 D의 깜빡이 끄기를 12.6초로 옮겨 "꺼진 뒤 3초" 판정 경계에서 떼어 두었습니다. 시나리오 G는 debug 빌드로 2바퀴(바퀴마다 6장)를 재생했습니다(회전 카드에 필요한 모의 내비 입력은 debug에만 있습니다).
- **화면 규격**: 에뮬레이터 화면 2560×1440 px, 160 dpi, 가로 방향(AVD 설정 `hw.lcd.width=2560`, `hw.lcd.height=1440`, `hw.lcd.density=160`).
- **시나리오 재생**: ADB forward 후 `127.0.0.1:8088` Console, `scripts/recap-replay.js`.
- **AI 보조는 선택 기능이며 기본값은 꺼짐**입니다. 홈의 `AI 보조` 타일로 켤 수 있고, 켜면 로컬 중계 서버(`ai-proxy/`, PC `127.0.0.1:8787` = AVD `10.0.2.2:8787`)가 필요하고, 서버가 없거나 끄면 규칙 판정과 기본 문구로 동작합니다.
- 소스 빌드: Windows, JBR 17 이상, Node 20 이상. minSdk 28 / targetSdk 35 / compileSdk 36, Kotlin 2.2.20, AGP 8.13.0, Gradle 8.14.3.

## APK 위치

**`apk/김승주_오운복_release.apk`** — 1.9.4 (versionCode 28), 서명된 release APK. 제출 방법은 [공식 안내](https://appdevchallenge.auto-hmg.io/guide/submission)를 따랐습니다.

## 검증 요약

- **단위·재생 시험**: `:logic:test` 459개 중 455개 통과, 실패 0(1.9.4). 4개는 실주행 자료 재생·합성 주행 측정용이라 환경 변수(`RECAP_REAL_DIR`·`RECAP_REAL_CSV`·`RECAP_TRACE_SPEC`·`RECAP_SIM`)로 켤 때만 돕니다.
- **구동 안정성**(AVD, release): 1.9.1에서 30분 연속 주행 크래시 0, 메모리 증가 없음(화면 28.3~31.1MB·기록 19.5~23.2MB), CPU 평균 약 0.8%. 1.9.2에서 콜드 스타트 0.47~0.50초(469/496/498ms), 홈에 다녀오거나 화면 프로세스가 종료된 뒤에도 보던 카드 복원. [자세히](docs/RUNTIME.md)
- **차선 변경 정확도**(공개 실주행 74분·차량 27대): 엄격한 자이로 확인 규칙(오통과 0.8%)으로 51장 중 29장(57%) 확인, 확인 규칙 탐지율(약 78%, 실주행 자이로에 차선 변경 모양을 얹어 잰 값)로 보정한 정밀도 약 70%(95% 구간 약 50~90%). 재현율은 현대 32건 중 25건(78%, 문턱을 정한 자료), 기아 28건 중 16건(57%, 검증용). 정밀도와 이 재현율은 1.9.3 판정기로 잰 값이며, 같은 입력에서 1.9.4는 현대 26/32·기아 16/28입니다. 운영사무국이 안내한 차량 SDK 전달 방식(1초 간격, 3초 안 같은 값 생략)으로 핸들 신호를 줄여 재생하면 1.9.4 재현율은 현대 22/32(69%)·기아 18/28(64%)로, 1.9.3(17/32·15/28)보다 높습니다. 실차 검증이 아닌 공개 자료 기준입니다. [근거와 한계](docs/ACCURACY.md)
- 변경 이력은 [CHANGELOG](CHANGELOG.md)에 있습니다.

## 기능

- P에서 기록 시작/중지. 주행 중에는 플랫폼 차단과 앱 잠금을 유지하고 별도 foreground service가 구간 요약만 만듭니다.
- 깜빡이 놓침·늦게 켬·먼저 켬·켜 둔 채 주행·급제동 후보를 근거 수치와 함께 보여 줍니다. `규칙 판정`, `AI 추정`, `애매함`을 구별합니다.
- 정차 후 오늘 집계, 복기 카드(한 장씩), 오늘의 한마디, 지난 7일과의 비교, 모은 잘한 순간, 맞아요/아니에요 피드백, 기록 삭제를 제공합니다. 점수·순위는 없습니다.
- NaviHelper 안내와 조향을 융합해 `내비 확인`, `내비 보조`, `추정`을 구별하고 회전 깜빡이도 복기하도록 구현했습니다. 다만 내비 거리 단위(m)가 확인되지 않아 기본 빌드는 내비 거리를 쓰지 않으므로 회전·합류 판정이 동작하지 않고, 직진 안내 중 차선 변경의 `내비 보조` 표시만 남습니다. 회전 판정은 `-PnaviDistanceUnit=meters` 빌드에서 동작합니다.
- 홈의 `AI 보조` 타일과 숨은 메뉴(제목 길게 누르기)의 `AI 서버 설정`에서 AI 사용 여부와 서버 주소를 바꿉니다. 기본 주소는 `http://10.0.2.2:8787`, 다른 서버는 HTTPS만 허용합니다. 분류 제한 3초, 문장 제한 20초이며 실패하면 규칙과 기본 문구로 돌아갑니다.
- 화면은 Pleos 디자인 토큰을 쓴 평면 타일, 주간·야간, 가로·세로 리사이즈에 대응합니다.

## 차량 신호와 SDK

| 용도 | SDK/API | 권한 |
|---|---|---|
| 핵심 기록 | `ai.pleos.playground:Vehicle:2.3.2` TurnSignal / SteeringWheel / Brake 조회·구독 | `CAR_LIGHTS`, `CAR_DRIVE` |
| 주행·잠금 | AAOS `CarPropertyManager`: `CURRENT_GEAR`, `GEAR_SELECTION`, `PERF_VEHICLE_SPEED`, `CarUxRestrictionsManager` | `CAR_POWERTRAIN`, `CAR_SPEED`(런타임 승인) |
| 내비 | `NaviHelper:3.0.5`(`navi-data:3.0.4`): `getRouteStateInfo`, `getTBTInfo`, `onNavigationStatus`, `onTBTInfo`. 기록 프로세스에서 메모리 융합 | `NAVI_ROUTE` |
| 기록 서비스 | foreground service(`specialUse`), 알림, 부팅 뒤 보존 기간 정리 작업 | `FOREGROUND_SERVICE*`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED` |
| 선택형 AI | 로컬 `ai-proxy/`: Jev로 애매한 조향 분류, OpenCode Go로 한국어 문장 생성 | `INTERNET` |

앱은 차량 상태 **조회**만 사용하며 제어 권한이 없습니다. 주행 중 화면 사용 허가(DO, Distraction Optimized)를 받지 않은 앱이라 주행 중에는 화면이 가려지고, 기록 서비스만 뒤에서 동작합니다. SDK 키는 코드에 없고 AI 인증은 로컬 서버에서만 처리합니다. 창문·도어·시트·배터리 등을 읽는 진단 탐침과 해당 권한은 debug 빌드에만 있습니다. [AI 계약](docs/AI_CONTRACT.md)

## 구조

```text
[기록 프로세스 :drive_recorder]                          [화면 프로세스]
DriveRecorderService (foreground)                      MainActivity
 ├ DriveSignalSubscriptions  깜빡이·조향·브레이크 구독     ├ CarSafetyClient  기어·속도·UX 제한 → 잠금
 ├ PleosNaviSource           NaviHelper 안내(메모리만)    ├ RecapScreen / VehicleUi  카드·타일 그리기
 └ RecapRecordingSession ── logic: RecapEngine 판단      └ AiProxyClient  애매한 조향 분류 (선택)
        │                                                        ▲
        ▼                                                        │
   RecapRepository ── RecapCodec 직렬화 ── RecapProvider ─────────┘  (ContentProvider, exported=false)
   ├ 요약·피드백·7일 집계·잘한 순간, 정리 작업 RecapCleanupJob
   └ RecapSummaryCoordinator ─ AiProxyClient  오늘의 한마디 생성 (선택)
```

| 모듈 | 역할 |
|---|---|
| `logic/` (순수 Kotlin, Android 의존 없음) | 판단 규칙 전부: `RecapEngine`(사건 판정), `BlinkerLead`·`DelayedLaneWindow`·`LateralMotion`(깜빡이 선후·차선 변경 창), `NavigationFusion`(내비 융합), `RecapCardView`·`RecapText`(카드 문구), `WeekComparison`·`GoodMoments`. 단위·재생 시험 459개(따로 켜야 도는 실주행 자료·합성 주행 측정용 4개 포함)가 여기에 있습니다. |
| `app/src/main` | SDK 연결, 기록 서비스, 저장, 화면. 판단은 하지 않고 `logic`에 신호를 넘깁니다. |
| `app/src/debug` | 신호 탐침·관찰 게임·모의 내비 등 개발 도구. release APK에는 들어가지 않습니다. |
| `ai-proxy/` | 선택형 로컬 AI 중계(Node, 외부 패키지 없음). 키는 이 서버에만 있습니다. |
| `scripts/` | 시나리오 재생(`recap-replay.js`, `recap-scenario-*.json`), 오프라인 시험, 공개 실주행 자료 평가(`realdata/`). |

기록 프로세스를 따로 둔 이유: 주행 중에는 플랫폼이 화면을 가리고 Activity가 멈출 수 있어서, SDK 구독과 판단을 화면 수명과 분리했습니다. 화면은 저장된 요약만 읽습니다.

## 실행 상세

### 로컬 AI 서버(선택)

Node 20 이상, npm 설치/외부 패키지 없이 실행합니다. 서버 프로세스 환경에 `TYPESAFE_API_KEY`, `OPENCODE_GO_API_KEY`를 제공한 뒤 프로젝트 루트에서 실행합니다. 키를 소스·APK·명령 인수·로그에 넣지 않습니다.

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

1. P·속도 0으로 정차한 상태(화면 위 안내 "운전 중에는 기록만, 복기는 차를 세운 뒤". 주행 중에는 "정차 후 사용"으로 잠깁니다)에서 권한 승인/기록 시작. 깜빡이·브레이크·조향의 GET 또는 notify로 `초기 신호 준비됨`을 확인하고 3초 이상 기다립니다.
2. PC 브라우저(Chrome·Edge)로 `http://127.0.0.1:8088` Console을 열고 F12 → Console 탭에 `scripts/recap-replay.js` 전체를 붙여넣습니다(브라우저가 붙여넣기 허용을 물으면 안내대로 허용). 이어서 `await driveRecapReplay.runFile()`을 입력하면 파일 선택 창이 열리고, 저장소의 `scripts/recap-scenario-d-demo.json`을 고릅니다. 파일 선택 창을 쓸 수 없으면 JSON 내용을 `await driveRecapReplay.run(<JSON 객체>)`로 직접 넘겨도 같습니다.
3. 출발→정상/놓침/늦은 차선 변경→신호 대기→저속 우회전→깜빡이를 켠 채 순항(18초)→급제동→P입니다. 주행 약 94초+속도 대기/HTTP 비용이 걸립니다. caption은 콘솔 출력만이며 소수 속도는 preflight가 정수로 바꾸고 알립니다. 실행 중 Console을 직접 조작하지 않습니다.
4. D에서는 화면이 가려져야 합니다. P 복귀 후 복기 기대는 잘한 순간 1·놓친 순간 1·늦게 켬 1·켜 둔 채 1·급제동 1, 카드 5·애매함 0입니다. 저속 회전은 숨깁니다. 급제동 카드의 시작·끝 속도는 감속이 가장 큰 구간이라 회차마다 다를 수 있습니다(명령한 감속 8.33 m/s², 측정 예 8.6 m/s²). 에뮬레이터 부하가 크면 차선 카드가 빠질 수 있으니 `-no-audio`로 켜기를 권합니다. 문장은 준비 중→AI 문장 또는 기본 문구입니다. 내비 회전 카드는 시연 영상에서 볼 수 있습니다(모의 내비가 든 시나리오 H·F는 개발용 debug 빌드와 `scripts/demo-navi-feed.ps1`이 필요합니다).
5. `await driveRecapReplay.stop()`은 0·중립·Off·P 복원을 시도합니다. 실패 시 Console에서 상태를 확인합니다. 서버 중지/AI OFF로 규칙 폴백도 확인합니다.

## 소스에서 빌드하기

서명키는 빌드 담당자가 생성합니다. 비밀번호는 프롬프트에 입력하고 인증서에 실명·연락처·메일·소속을 넣지 않습니다. 아래는 개인 식별정보 없는 앱용 DN입니다.

```powershell
$env:JAVA_HOME = '<Android Studio 설치 폴더>\jbr'
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

파일/필수 항목/키 파일이 없으면 release 빌드는 명확한 오류로 중단하고 unsigned/debug 서명으로 대체하지 않습니다. minify/resource shrink는 끄고 debuggable=false입니다. 속도 미수신 시연과 Debug 원시 감속 표시는 release에서 비활성입니다. release의 HTTP 예외는 AI 중계 주소 10.0.2.2 하나뿐입니다.

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
.\gradlew.bat :logic:test :app:assembleRelease --no-daemon --no-configuration-cache --console=plain
if ($LASTEXITCODE -ne 0) { throw 'release 빌드 실패. 이전 APK를 제출하지 마세요.' }
$team = Read-Host '제출 팀명'
$target = Join-Path '.local' ($team + '_오운복_release.apk')   # apk/에 두면 설치 명령의 *_release.apk가 두 개를 집습니다
Copy-Item -LiteralPath .\app\build\outputs\apk\release\app-release.apk -Destination $target
```

의존성이 캐시에 있으면 `--offline`을 추가할 수 있습니다. 

내비 거리 m 단위의 공식 근거나 AVD 대조 검증을 확보한 뒤에는 같은 빌드 명령에 **`-PnaviDistanceUnit=meters`**를 추가합니다. 기본 `unverified`는 거리 기반 확정을 막습니다. 두 빌드 모두 NaviHelper는 기본 포함이며 내비 미수신이면 기존 규칙으로 동작합니다.

## 개인정보 처리

- 기록 서비스는 위치·VIN·경로·음성·이름·연락처·메일을 수집/저장하지 않습니다. 실제/모의 내비 동작·거리·안내 상태·방송 runId/sequence는 메모리에만 두고 버립니다. 요약에는 사건 시각·종류·근거 수치·피드백·최종 내비 판정과 모의 출처 Boolean만 있습니다. Debug 빌드 숨은 시연 설정의 목적지 좌표는 사용자가 직접 입력해 NaviHelper로 요청하는 용도로만 사용하고 기록/AI/로그/파일로 보내지 않습니다.
- 상세 요약/피드백은 오늘·어제 2일, 날짜와 7개 건수(기존 4 + 회전 미사용/늦음/사전사용) 집계만 7일, AI 문장은 오늘 캐시만 유지합니다. 접근 시와 주기적 정리로 기간 밖 자료를 삭제하며 자정 정각 삭제는 보장하지 않습니다.
- 하루 집계에는 켬 거리 합계와 장수가 더해집니다. "모은 잘한 순간"은 누적·연속·최고 연속 숫자와 내부 기준 날짜 하나만 앱 파일에 남기며, 위치·시각·카드 id는 없습니다. 둘 다 기록 삭제로 지워집니다.
- 기록 삭제는 요약/피드백/집계/문장을 지웁니다. AI 사용 여부와 서버 주소는 유지합니다. Android 백업은 비활성입니다.
- AI ON은 허용한 요약 특징만 로컬 중계/제공자에 보냅니다. 정확한 시각은 시간대, 과거 날짜는 상대값으로 축소하며 위치·VIN·경로·원시 시계열을 보내지 않습니다. 외부 공급자 처리 정책은 별도 확인 대상입니다.
- 서버는 키를 응답/파일/로그에 남기지 않습니다. 실제 키·서명 재료는 저장소에 넣지 않습니다. release 로그는 원시 차량 값/Debug 숫자를 숨깁니다.

## 데이터 출처

- `logic/src/test/resources/replay/comma2k19_example1.csv`: comma.ai 공개 데이터셋 [comma2k19](https://github.com/commaai/comma2k19)(MIT License, Copyright comma.ai) 한 구간에서 조향·속도·브레이크·깜빡이 신호와 시각만 뽑아 바꾼 재생용 자료입니다. 영상·위치는 넣지 않았습니다.
- 정확도 평가([docs/ACCURACY.md](docs/ACCURACY.md))에 쓴 [commaCarSegments](https://huggingface.co/datasets/commaai/commaCarSegments)(MIT)는 저장소에 넣지 않았고, `scripts/realdata/`의 평가 스크립트만 있습니다.
- `avd_g_*.csv`는 Pleos 에뮬레이터에서 직접 만든 모의 주행 기록입니다.

## 알려진 한계

- 정확도는 공개 주행 자료 기준이며 Pleos 실차로 확인하지 않았습니다. 차선 변경 카드는 신호 패턴에 따른 **추정**이고 실제 차선 이동·안전성·법규 준수를 확정하지 않습니다. 근거와 한계는 [docs/ACCURACY.md](docs/ACCURACY.md).
- 에뮬레이터의 정수 속도와 순간 반영 지연 때문에 감속 측정값은 명령 평균과 다릅니다. 실차 정확도나 안전 효과의 증거가 아닙니다. 15m/s² 초과는 `감속 큼(측정값 이상)`으로 표시합니다.
- 조향 좌우 부호 규약이 확정되지 않아 조향만으로 좌우를 표시하지 않고, 차선 변경의 깜빡이도 어느 쪽이 켜졌는지만 보며 바꾼 쪽과 대조하지 않습니다. 내비 확인 회전의 좌/우는 SDK TurnType에만 근거합니다.
- NaviHelper 연동 코드는 구현했으나 에뮬레이터 기본 내비가 가상 GPS를 인식하지 않아(운영사무국 답변, 2026-11-04) 실제 TBT 수신과 거리 단위(m)는 확인하지 못했습니다. 내비 없이 기존 규칙으로 동작합니다. Debug 빌드의 시연용 모의 내비는 실제 TBT 증거가 아닙니다.
- DO를 선언하지 않은 앱의 specialUse 포그라운드 서비스가 주행 중 신호를 받는 것은 AVD에서 확인했고, 운영사무국도 이 방식의 신호 수신이 가능하다고 답했습니다(2026-11-04). 자동 시작·실차 지원·앱마켓 정책은 그 답변의 보장 범위 밖이라, 운전자가 P에서 직접 [기록 시작]을 누르는 방식을 유지합니다.
- AI 확률은 정답 확률이 아닙니다. Go 공급자는 개발·시연용입니다.

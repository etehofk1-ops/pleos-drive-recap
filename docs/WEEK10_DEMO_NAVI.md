# 10주차 · 시연용 모의 내비 · 1.2.0 (14)

2026-10-23. 코드와 모의 시험을 마쳤으며 Gradle/APK/AVD 시연은 Claude가 확인한다. 파일 변경은 이 프로젝트 안에서만 수행했다. 저장소 생성·commit/push·실제 adb/차량 조작·키 조회/외부 AI 호출은 수행하지 않았다.

## 배경과 증거 구분

사용자가 전달한 Claude AVD 실측: GNSS HAL이 NMEA 체크섬을 `*47`로 재생성하고 내비가 버려 sat(0)/무효 roadid/TBT 빈 목록이 된다. 정상 체크섬 문장을 보내도 재생성된다. 이는 Claude 보고이며 이번 작업에서 HAL을 독립 재검증한 것은 아니다. [기존 W9 실측 부록](WEEK9_NAVI_FUSION.md)에도 내비 연결·SAFE_GUIDING 수신과 빈 TBT·위치 미매칭을 구분해 기록했다.

따라서 F는 **모의 입력으로 융합 규칙을 시연**한다. 실제 NaviHelper TBT의 실측 성공이나 실차 성능 증거로 해석하지 않는다. 실제 경로는 삭제하지 않았다.

## 구조와 수명

- `NavigationSource`: `origin`, `start()`, `stop()` 인터페이스. 세션마다 원천 하나만 소유한다.
- `PleosNaviSource`: 기존 `NaviTbtProbe`의 이름/인터페이스만 정리했다. 실제 SDK 초기화·조회·리스너·재연결·해제·거리 단위 gate는 유지한다.
- `DemoNaviSource`: Debug + 숨은 토글 ON + 기록 중일 때만 입력을 받는다. 실제 source는 동시에 시작하지 않는다. 받은 DTO/거리/동작/runId/sequence/시각은 메모리 전용이다.
- 토글은 private Provider가 기록 시작 전에만 변경하며 SharedPreferences/파일에 쓰지 않는다. 서비스 프로세스가 다시 만들어지면 OFF다. 시작/중지 사이에서 원천을 바꾸거나 두 원천의 스냅숏을 합치지 않는다.
- 입력 경로는 Debug manifest에만 있는 `.DemoNaviReceiver`(process `:drive_recorder`)다. 명시적 adb 방송은 허용하지만 서비스 시작·토글 ON을 대신하지 않는다. release에는 receiver 클래스/manifest 선언/토글이 없다. 공통 소스에도 BuildConfig.DEBUG 검사를 둔다.
- 카드에는 `demoNavigation` Boolean을 남긴다. 내비 입력 자체의 저장이 아니며, 저장된 모의 판정을 실제 판정처럼 표시하지 않기 위한 최소 출처다. 상세 codec v3가 v1/v2도 읽는다. 보관 기간·삭제·AI HTTP 계약은 기존과 같다.
- 메인·복기 상단은 ON일 때 **시연용 모의 내비 입력 사용 중**. OFF/프로세스 재시작 뒤 저장된 모의 카드가 있으면 **시연용 모의 내비 판정 포함**. 페이지 밖 카드도 Provider가 전체 목록에서 검사한다.
- 판정 라벨은 **내비 확인(모의)**, **내비 보조(모의)**, 합류/진출에도 모의를 표시한다. 안내 없음/만료는 추정이며 카드에 모의 출처가 계속 남는다. 실제 source의 기존 라벨은 바꾸지 않는다.

## 입력 계약

Action `lab.pleos.observe.DEMO_NAVI`, component `lab.pleos.observe/.DemoNaviReceiver`.

```json
{
  "guiding": true,
  "action": "TURN_RIGHT",
  "distanceMeters": 50,
  "runId": "11111111-1111-1111-1111-111111111111",
  "sequence": 1
}
```

- 위5키만 허용, 최대1024자. 실제 AAR TurnType enum 이름만 사용한다. 중첩 값/중복 키/미지 필드/문자열 숫자/비유한수/음수 거리/좌표·VIN·경로·외부 시각을 거부한다. 시연에 필요 없는 escape 문자열도 받지 않는다.
- 안내 중은 action 필수, 거리 유한수0..10000000 또는 null. 안내 종료는 `guiding:false, action:null, distanceMeters:null`. 빈 TBT/unknown을 직진으로 만들어 내지 않는다.
- runId는 실행마다 생성한 무작위 UUID이고 sequence는 단조 증가 정수다. 같은 run의 중복/역순은 거부하며 만료를 연장하지 않는다. 다른 run으로 교체는 이미 입력받은 세션에서는 P에서만 허용한다.
- 수신 시각은 앱의 `elapsedRealtime()`이다. PC wall clock을 앱 시계로 비교하지 않는다. 수신 후 정확히3000ms는 유효, **3001ms부터 무효**다. source가 만료 무효화 스냅숏을 내보내 융합도 오래된 단서를 승격에 사용하지 못하게 한다. 만료/안내 종료/새 실행은 증거 세대를 나눈다.
- `--es json` 또는 `--es payloadB64` 중 하나만 허용한다. Windows/adb JSON 따옴표 손실을 피하도록 공급기는 UTF-8 Base64를 사용한다. Base64는 암호화/인증이 아니며 Debug 전용 시험 전송이다. 실제 키는 없다.
- 수신 결과는 `demo_navi_accepted` 또는 `demo_navi_rejected`뿐이며 입력 JSON을 오류/로그에 출력하지 않는다. Debug 로그는 정적인 이벤트명과 동작/sequence 정도만 남기며 파일 로그를 만들지 않는다.

수동 방송 예시(시연 토글 ON·기록 중이어야 함):

```powershell
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$frame = @{ guiding=$true; action='TURN_RIGHT'; distanceMeters=50; runId=[Guid]::NewGuid().ToString(); sequence=0 }
$json = $frame | ConvertTo-Json -Compress
$payload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json))
& $adb -s emulator-5554 shell am broadcast --receiver-foreground `
  -n lab.pleos.observe/.DemoNaviReceiver -a lab.pleos.observe.DEMO_NAVI --es payloadB64 $payload
```

반복 방송은 같은 runId에서 sequence를 늘려 보낸다. 보내기를 멈추면3초 뒤 만료해야 한다. 단일 방송이 계속 유효하다고 가정하지 않는다.

## 같은 시각표로 재생하는 방법

별도 시계를 가진 두 프로그램을 동시에 시작하면 브라우저 속도 대기/HTTP 지연과 어긋난다. **`demo-navi-feed.ps1` 하나가 F JSON을 읽어 Console HTTP + adb 방송을 함께 실행**한다. 추가 서버/신호 파일/시계 맞추기는 없다. JSON의 navi가 바뀌는 단계에서는 차량 조작 전에 먼저 방송하고, 다음 navi 단계까지1초마다 현재 시연 상태를 새 sequence로 공급한다. 속도 대기 중에도 공급한다. 앱이 스스로 입력을 갱신하는 것은 아니다.

- Console 브라우저 탭을 모두 닫아 페이지 physicsTick의 약140ms controls 덮어쓰기를 막는다. Console 서버/AVD는 켜 둔다.
- 시작 전 catalog turn-signal mode 계약과 서버 Park/0을 검사한다. app 토글/기록이 준비되지 않았으면 첫 방송이 거부돼 차량 조작 전에 실패한다.
- dry-run은 시각/정수 속도/신호/navi/시작·끝P를 검사하며 HTTP/adb를 호출하지 않는다.
- 실제 실행은 Enter 뒤 시작한다. `-StartImmediately`는 준비된 자동 시험용이며 기본은 Enter 대기다.
- 쓰기 비용은 다음 단계의 기준 시각을 함께 미룬다. 밀린 동작을 한꺼번에 보내지 않는다. 명목 간격을 유지하고 속도 gate에서도 같은 시계를 사용한다. 일정이750ms 이상 뒤처지면 중단한다.
- 종료/예외/Ctrl+C의 finally에서 P/0/중립/Off 복원을 시도한다. 프로세스 강제 종료·PC/ADB 중단 시 복원을 보장하지 않으므로 수동 확인한다. 소수 속도는 preflight에서 거부한다.
- 브라우저 `recap-replay.js`는 F나 navi 필드가 있는 입력을 단독 실행하지 못하도록 안내한다. A~E의 기존 실행은 유지한다.

## F 시각표와 기대 카드

명목73초이며 속도 안정 대기·HTTP·adb 시간이 추가된다. 실제 입력은 한 시계로 함께 이동한다.

| 명목 시각 | 장면 | 기대 |
|---|---|---|
| 0~6초 | P→D,12km/h 안정, 안내 없음 | 기록 준비/모의 배지 |
| 6초/8초 | TURN_RIGHT 200m→50m,8초에 오른쪽 깜빡이 | 조향 전에2초 신호 확보 |
| 10~14.4초 | 저속 큰 조향 후 중립 | 우회전 좋아요 · 내비 확인(모의)1 |
| 20초/22초 | TURN_UTURN 200m→50m,Off | 유턴 단서 |
| 24~28.4초 | 큰 저속 조향,깜빡이 없음 | 깜빡이 없이 유턴 · 내비 확인(모의)1 |
| 33초 | TURN_STRAIGHT,35km/h 안정 | 근접 회전 없는 안내 |
| 40~42.45초 | 작은 왕복 조향,Off | 차선 변경 놓침 · 내비 보조(모의)1 |
| 48초 | 안내 종료 | 이전 안내 세대 무효화 |
| 53~55.45초 |35km/h 작은 왕복,Off | 차선 변경 놓침 · 추정1 |
| 59~73초 |1km/h씩300ms 감속,브레이크0,P | 급제동 추가 없이 복기4장·애매함0 기대 |

이는 오프라인 기대값이다. 실제 콜백/속도/카드는 Claude가 검증한다. F의 네 판정은 실제 내비 실측이 아니다.

## Claude 실행 순서

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
.\gradlew.bat :logic:test :app:assembleDebug --no-daemon --console=plain
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
& $adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
& $adb -s emulator-5554 forward tcp:8088 tcp:8088
& $adb -s emulator-5554 shell am start -n lab.pleos.observe/.MainActivity
# 앱에서 P 확인→기록 삭제(필요시)→제목 길게→시연용 모의 내비 ON→기록 시작→초기 신호 준비됨.
# PC Console 브라우저 탭 모두 닫기. 별도 터미널에서 logcat ObserveLab 확인.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/demo-navi-feed.ps1 -DryRun
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/demo-navi-feed.ps1 -Serial emulator-5554
```

1. 모의 토글 OFF에서 수동 방송이 rejected인지 확인. 방송이 토글을 켜거나 서비스를 시작하면 안 된다.
2. ON/기록 중 F 실행. D 플랫폼 차단/앱 잠금, 같은 서비스 프로세스의 `drive.navi.demo.received` 로그 확인. 원래 속도 미수신 시연은 OFF다.
3. P 후4장/0애매함·배지·우회전/유턴/내비 보조/추정 라벨 확인. 특히 마지막 차선 변경에 ‘내비 보조’가 붙으면 실패다.
4. 수동 방송 후3초 이상 멈춰 `drive.navi.demo.expired`/미수신 상태 확인. 오래된 값으로 다음 조향이 승격되지 않아야 한다.
5. 기록 중지 후 토글 OFF, 앱 재시작/저장 복원에서도 기존 모의 카드의 라벨이 유지되는지 확인. 모의 DTO/runId/sequence/거리 자체는 파일에 없어야 한다. 실기록 전후에는 기록 삭제해 집계/AI 최근 패턴과 섞지 않는다.
6. release 빌드/merged manifest/APK에서 receiver 부재·토글 숨김·방송 미수신 확인. 실제 SDK source만 사용한다. 기존 A/D와 AI 폴백/잠금도 회귀 확인한다.

## 변경 파일과 검증

- 앱: NavigationSource/DemoNaviSource/PleosNaviSource(기존 파일 이동), Debug manifest/receiver, RecapRecordingSession/DriveRecorderService, RecapRepository/Provider, MainActivity, build.gradle.kts(14/1.2.0).
- logic: DemoNavigation(닫힌 파서/수명/중복 방지), NavigationFusion(출처 격리/라벨/배지), RecapModels/Codec(v3 출처·v1/v2 읽기), AiReview(모의 표시), DemoNavigationTest.
- 시연/검사: F JSON, demo-navi-feed.ps1, test-demo-navi.mjs, 기존 JS의 잘못된 F 실행 차단, 제출 검사/모의 버전 갱신.
- 문서: README, AI_CONTRACT(HTTP는 그대로/로컬 codec v3 명시), WORK-CHECKLIST, 본 문서.
- 게임/차량 신호 탐침/안전 잠금/실제 SDK 호출·임계값/A~E JSON은 바꾸지 않았다.

검증 결과:

- JVM **242/242**(기존227+15): 실제 입력 파서·개인정보 필드 거부·3000/3001ms·중복/역순/새 실행·만료 융합·라벨/배지·v2 복원/출처 저장·네 장면 reducer+fusion 통합. `.local/w10-jvm-tests.txt`.
- Node **70/70**(기존66+4): 공급기 dry-run/실패 입력, F 단독 JS 실행 차단, Debug 전용 manifest, 모의 HTTP Console+가짜 adb 프로세스에서 같은 단계 순서/heartbeat/마지막P 확인. `.local/w10-node-tests.txt`. 가짜 adb는 AVD 검증 증거가 아니다.
- 실제 SDK JAR+Android36/car 대상으로 Debug(수신기 포함)/Release Kotlin 직접 타입 검사 통과. `.local/w10-debug-types.txt`, `.local/w10-release-types.txt`. Gradle manifest 병합/DEX/APK 실행 증거는 아니다.
- 기존 실제 NaviHelper 파일을 HEAD와 대조해 클래스명/인터페이스/override 선언 외 본문이 그대로임을 확인했다. 게임/탐침/안전·차량 구독/A~E JSON도 diff가 없다. `git diff --check`와 Debug manifest XML 파싱 통과.
- 제출 소스 검사: 텍스트101개에서 소스 패턴 검출은 없으나 기존 Git 메시지 이메일20건 때문에 전체 검사는 실패했다. 제외 경로 추적39개도 제출 전 별도 확인이 필요하다. 이력/제외 기록은 수정하지 않았다. `.local/w10-submission-check.txt`.
- PowerShell5.1이 한글 리터럴을 ANSI로 읽지 않도록 변경한 PS1은 UTF-8 BOM으로 저장했다.
- Gradle/실제 방송/AVD 화면·카드 결과는 미실행, Claude 인계다.

## Claude AVD 실측 (2026-10-23, 1.2.0)
- Virtual Console 서버는 브라우저 세션 heartbeat(`POST /browser/session?client=<id>`, 1초 간격)가 없으면 controls를 `{"error":"emulator_inactive"}`로 거부한다. 페이지를 닫고 공급기만 돌릴 때는 heartbeat를 따로 보내야 함(Claude가 curl 루프로 해결 — 공급기에 내장 필요).
- 시나리오 F 결과: **좋아요 · 깜빡이 사용 우회전 · 내비 확인(모의) / 깜빡이 없이 유턴 · 내비 확인(모의) / 깜빡이 없이 차선 변경 · 내비 보조(모의) / 깜빡이 없이 차선 변경 · 추정**, 애매함 0. 상단 "시연용 모의 내비 입력 사용 중" 배지 표시. 캡처 `docs/evidence/w3/recap_navi_demo*.png`, 영상 `demo_navi_720p.mp4`.

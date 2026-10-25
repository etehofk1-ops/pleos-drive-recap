# 오늘 운전 복기 v1 · 2026-10-23

> **0.5.0 당시 기록.** Claude 실측에서 CURRENT_GEAR 고정으로 후보 0개가 확인돼 [5b](WEEK5B_DRIVING_STATE.md)에서 수정했다. 현재 0.5.2는 [5c 수정·실행법](WEEK5C_RULE_REFINEMENT.md)을 따른다. 아래의 DOM 리플레이·속도 미수신 시연 기본 안내·예전 기대 개수·버전 명령은 역사적 기록이며 최신 절차로 쓰지 않는다.

## 결과와 검증 범위

versionCode **5**, versionName **0.5.0**. 규칙 엔진 → 구간 요약 → 앱 내부 저장 → 정차 후 카드 → 로컬 피드백을 연결했다. 메인은 “오늘 운전 복기”이며 제목 길게 누르기로 기존 진단·게임·탐침에 들어간다. 기존 게임 reducer·안전 판정·탐침 클라이언트는 보존했다.

0.4.0의 D 상태 지속 수신은 [Claude 실측 기록](WEEK4_DRIVE_RECORDER.md#claude-에뮬레이터-실측-2026-10-22-040)을 근거로 사용했다. 이번 세션에서 0.5.0을 설치하거나 실제 주행 콜백·화면·파일 동작을 재검증한 것은 아니다. **Gradle 빌드·AVD 시험은 Claude가 한다.**

핵심 규칙과 시나리오는 JVM에서 검증했다. **NaviHelper는 AAR 부재·다운로드 차단으로 TBT 필드/거리/회전 코드 규약을 확인하지 못했다.** 선택 가능한 메타데이터 탐침까지 만들었으며, 실제 TBT를 회전 판정에 연결하는 부분은 미완료다. 기본 빌드와 규칙은 내비 SDK 없이 동작한다. 확인하지 못한 필드나 조향 부호를 추정해서 쓰지 않았다.

## 화면·기록 흐름

1. P·속도 0·UX 제한 비활성에서 “기록 시작”. 알림 권한 창을 처리했다면 기록 시작을 다시 누른다.
2. 별도 `:drive_recorder` FGS가 기존 SDK·Car 구독을 유지한다. DO 미선언과 기존 화면 잠금은 유지한다. D에서는 플랫폼 차단 및 앱 잠금으로 본문을 제거한다.
3. D→P 때 구간을 마무리하고 “오늘 복기가 준비됐어요” 알림을 만든다. 알림 권한을 거부하면 알림이 표시되지 않을 수 있으나 카드 저장은 계속한다.
4. “오늘 복기 보기”에서 시간·종류·수치 근거·애매한 이유를 확인한다. 카드는 최신순이며 100개씩 조회한다. 총 개수는 전체 카드 수다.
5. 카드별 맞아요/아니에요는 요약과 함께 로컬 저장한다. v1에서 임계값을 자동으로 바꾸지 않는다.
6. 기록 중지는 UI와 서비스 모두 P를 확인한다. 알림 중지 액션도 P일 때만 제공한다. 시스템 강제 종료는 별개이며 앱이 생존을 보장한다고 가정하지 않는다.
7. 기록 삭제는 오늘·어제의 요약과 피드백을 지운다. 기록 중 P에서 삭제하면 진행 중 요약·카운터도 초기화하여 삭제 전 구간이 다시 저장되지 않게 한다.

제목 길게 누르기 → `LegacyLabActivity`의 기존 진단 → **기존 게임** / **신호 탐침**. “복기로 돌아가기”로 새 메인에 복귀한다. 이전 `MainActivity`의 게임 UI를 별도 Activity로 옮겨 보존했다. 기존 문제·정답·잠금 로직을 새 규칙 엔진에 섞지 않았다.

## 규칙·임계값 표

아래 값은 **시제품 초기 선별값**이다. 공식 안전 기준·법규·검증된 차선 변경 검출 정확도를 주장하지 않는다. 속도 30km/h·사전 3초는 사용자 명세의 초기 예시를 채택했다. 나머지는 작은 왕복 조향/큰 회전/긴 단방향을 분리하고 재현 시나리오로 검증하기 위한 공학적 시작점이다. 실제 라벨·피드백으로 조정해야 한다.

정본: `logic/.../recap/RecapModels.kt`의 `RecapThresholds`.

| 항목 | 값 | 의도·근거 |
|---|---:|---|
| 조향 진입 / 중립 | 원시 절댓값 ≥6 / ≤3 | 중립 잡음을 줄이는 히스테리시스. SDK 원시 크기 기준 |
| 중립 유지 | 700ms | 짧은 중립 교차를 같은 왕복 구간으로 묶고, 끝의 중립 유지 때 확정 |
| 최소 구간 | 600ms | 짧은 떨림 제외 |
| 차선 변경 후보 | 0.6~6초, 양·음 각 ≥6, 최대 절댓값 ≤35, 유효 부호 반전 정확히 1회 | 작은~중간 조향의 한 번 왕복. 반복 흔들림은 한 번의 차선 변경으로 단정하지 않음 |
| 차선 변경 속도 | 구간 최소 속도 ≥30km/h(8.333…m/s) | 명세의 초기 예시. 수신한 낮은 속도를 미수신으로 바꾸지 않음 |
| 회전 후보 | 최대 속도 ≤25km/h, 최대 각 ≥55, 지속 ≥2.5초 | 저속·큰·긴 조향. 속도 없으면 동일 모양을 회전 후보/UNSURE로만 유지 |
| 굽은 길 후보 | ≥8초, 유효 부호 반전 0회 | 오래 유지한 단방향. 큰 각과 고속이 겹치면 UNSURE |
| 최대 구간 | 60초 | 끝없이 중립으로 돌아오지 않는 구간을 불완전/UNSURE로 분리 |
| 사전 깜빡이 창 | 시작 전 3초부터 구간 끝 | 명세의 예시. 켜진 채 유지하거나 창 안에서 꺼졌어도 사전 사용으로 인정 |
| 늦은 깜빡이 | 구간 시작 후 양의 지연시간 | 사전 관측이 충분하고 시작 뒤에 켠 경우. 같은 ms 관측은 순서를 단정하지 않고 UNSURE |
| 속도 신선도 | 3초 | 오래된 속도를 현재 값으로 쓰지 않음 |
| 시계/수신 공백 | 5초 | 긴 공백을 연속 주행 시간으로 더하지 않음. 진행 중 구간은 불확실 처리 |
| 급제동 | 속도 감소율 ≥3m/s² + 브레이크 true + D | 초기 선별값. 100~2,000ms 간격의 유효 속도 2점 필요 |
| 급제동 중복 억제 | 6초 | 같은 감속에서 카드가 쏟아지는 것 방지 |
| 내비 보조 조건(순수 엔진) | 회전 의미가 확인된 안내 + 거리 0~80m + 3초 이내 | 먼 “다음 회전”이나 단순 경로 존재를 현재 회전으로 보지 않음. 현재 SDK 실연결은 비활성 |

조향 분할·판정은 불변 상태와 입력을 받는 `recapStep()` 및 `classifySegment()` 순수 함수다. 원시 이벤트 배열을 모으지 않고 구간의 시작/끝·양/음 극값·반전 횟수·속도 범위·깜빡이 타이밍·품질 플래그만 유지한다. 타이머는 유지 중인 ON_CHANGE 상태를 평가하며 센서 콜백을 만들어 내지 않는다.

깜빡이 미수신/null, 사전 창 관측 부족, 늦게 도착한 깜빡이/조향, 비유한 조향, 구간 종료 전 중지·기어 이탈은 보수적으로 처리한다. 뒤늦게 온 깜빡이를 버리고 “없었다”고 판정하지 않는다. `kind=UNSURE`와 별도의 `candidate`·이유를 저장하고 카드에는 **애매함**을 표시한다. 속도 0·저속에서 정차 조향만 한 경우는 이동 구간을 만들지 않는다.

## 조향 부호·단위 근거

실측: Console 120 → SDK -120.0. 그러나 이것만으로 실제 좌·우 방향 규약을 정할 수 없다.

직접 열어 본 Vehicle 2.3.2 AAR SHA-256:

```text
e11fdfe498cad3ecc0d5a2a6992b0c4a4d135195f1455018127bc069fd5ef937
```

`SteeringWheel`, `SteeringWheelAngleListener`, `SteeringControl`의 공개/verbose 클래스와 [공식 메서드 참조](https://document.pleos.ai/api-reference/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/steering-wheel/get-current-steering-wheel-angle)를 확인했다. 공개 메타데이터는 Float 값과 callback 타입을 제공하지만 좌/우 부호 규약을 확정할 설명은 확인하지 못했다. Console 카탈로그에는 입력 단위 `deg`, `PERF_STEERING_ANGLE`이 있으나 SDK의 부호/단위 규약을 이것만으로 대체하지 않았다.

따라서 v1은 `SteeringConvention.UNKNOWN`, `ManeuverDirection.UNKNOWN`을 사용한다. 좌회전·우회전·좌측/우측 차선 변경을 표시하지 않는다. 카드에는 **핸들 원시 -12.0→9.0**처럼 표시하며 임의로 °나 방향을 붙이지 않는다. 깜빡이는 해당 구간에 LEFT/RIGHT 어느 쪽이라도 관측하면 사용한 것으로 처리한다. 이후 확인된 규약을 넣을 순수 함수 인터페이스와 양쪽 규약 테스트는 준비했다.

## 저장·삭제·피드백

- 앱 내부 `filesDir/recap-v1/YYYY-MM-DD.bin`에 버전 1의 명시적 요약 스키마로 저장한다. `AtomicFile`을 사용하며 임의 객체 역직렬화는 없다.
- 파일에는 날짜, D 주행 구간 시간, 속도 미수신 구간 시간, 분할 요약·규칙 결과·피드백만 들어간다. **위치·위경도·VIN·경로·도로명·목적지·원시 이벤트 이력은 저장하지 않는다.**
- 기본 주행 시간은 D와 이동 조건이 성립한 구간만 누적한다. 속도 미수신 완화로 더한 시간은 별도 숫자로 표시한다. 화면에 “주행 구간”으로 표시하며 장시간 수신 공백은 더하지 않는다.
- 파일 소유자는 서비스와 같은 `:drive_recorder` 프로세스의 저장소 하나다. UI는 `exported=false`인 `RecapProvider.call()`로만 조회·피드백·삭제한다. 쓰기는 소유 프로세스 main thread에서 직렬화한다.
- 카드는 생성 즉시 저장, 시간 집계는 최대 약 5초 간격과 P/중지 시 저장한다. 비정상 프로세스 종료 시 마지막 수초 집계와 아직 끝나지 않은 구간은 유실될 수 있다. 자동 재시작으로 연속 기록을 꾸미지 않는다.
- 기기 현지 달력 기준 **오늘·어제만** 보관한다. 읽기·쓰기·서비스 주기마다 기간 밖 `.bin/.bak/.new`를 지우며, 앱 화면이 닫힌 뒤에도 6시간 주기 JobScheduler 정리를 예약한다. OS가 작업을 지연/정지할 수 있으므로 정확한 자정 삭제를 보장하지 않는다. 다음 접근 시에는 기간 밖 요약을 먼저 삭제하고 노출하지 않는다. 정리 Job은 `setPersisted(true)`로 재부팅 뒤 복원한다. `RECEIVE_BOOT_COMPLETED`는 이 정리 작업 복원에만 사용하며 기록 서비스의 자동 시작은 구현하지 않았다. [JobInfo 공식 규칙](https://developer.android.com/reference/android/app/job/JobInfo.Builder#setPersisted(boolean))
- 사용자 삭제는 두 날짜의 요약과 피드백 모두를 대상으로 한다. 백업 파일도 함께 삭제한다. 삭제 실패는 화면 오류로 남기며 성공으로 꾸미지 않는다.
- 읽을 수 없는 파일은 덮어쓰지 않고 보존한다. 오류를 표시하고 해당 날짜 기록 시작을 거부한다. 사용자가 기록 삭제를 선택해야 초기화된다.
- 저장 실패도 화면에 표시한다. 외부 저장소·DB·SharedPreferences·인터넷 전송·AI 호출은 없다. 기존 Debug logcat 관찰은 별개이며 앱이 원시 로그를 파일로 수집하지 않는다.

피드백은 카드 id에 대해 YES/NO/NONE으로 저장하며 재조회·재시작 후 복원한다. id는 시각/단조 구간 식별 정보로 만들고 위치·차량 식별자로 만들지 않는다. `JevReviewAdapter`, `JevReviewInput`, `JevReviewProposal`은 다음 단계용 타입 계약뿐이며 v1에서 인스턴스를 만들거나 호출하지 않는다.

## NaviHelper: 확인한 것과 남은 것

공식 문서에서 [의존성 NaviHelper 3.0.5](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/importing-sdk), [조회 권한 NAVI_ROUTE](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions), [onTBTInfo/onRouteStateInfo 이벤트](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners), [getTBTInfo/getRouteStateInfo 메서드 이름](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.helper/navi-helper/entry)을 확인했다. 상세 TBTInfo 필드 본문은 비어 있어 단위·회전 코드 의미를 확정할 수 없었다.

프로젝트/사용자 Gradle 캐시에 NaviHelper AAR이 없었고 공개 Maven 다운로드는 “원격 서버에 연결할 수 없습니다”로 실패했다. Vehicle AAR은 위 해시로 조사했다. **NaviHelper를 AAR로 검증했다고 보고하지 않는다.**

- 기본 빌드는 NaviHelper 의존성을 포함하지 않는다. 숨은 진단의 탐침 상태는 **미수신 · NaviHelper AAR 미포함**이며 메인은 내비 보조 미사용만 안내한다. 숨은 진단은 메인에서 들어온 시점의 상태를 보여 준다.
- `-PincludeNaviProbe=true`로 선택 SDK를 넣으면 `NaviTbtProbe`가 문서에 있는 클래스·인터페이스·메서드 이름을 런타임에서 검사한다. 0인자 getter 등 기대 서명이 실제로 없으면 호출하지 않고 확인 필요로 표시한다. 이 경로의 실기 동작도 미검증이다.
- 탐침은 경로 상태/TBT의 **수신 여부만** 알린다. payload를 문자열로 기록하거나 위치 getter·경로 설정/취소·TTS·STT·FusedLocation·제어 API를 호출하지 않는다.
- **수신 여부는 회전 코드 해석 성공과 다르다.** 현재 TBT payload를 판정에 넣지 않는다. AAR에서 maneuver 의미·거리 단위·콜백 구조를 확인한 뒤 위치를 제외한 `TurnHint(isTurn, distanceMeters, receivedAtMs)`만 연결해야 한다. 순수 엔진의 신선도/근거리 보조 판정은 테스트했지만 실제 어댑터는 이 확인이 남았다.

재현/추가 조사:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\inspect-recap-sdk.ps1
# 이미 받은 AAR이 있으면 읽기 경로를 명시한다. 조사 결과는 프로젝트 .local/w5-aar에만 쓴다.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\inspect-recap-sdk.ps1 -NaviAarPath 'D:\경로\NaviHelper-3.0.5.aar'
```

## Console 확인 결과와 시연

2026-10-23에 이 PC의 `http://127.0.0.1:8088/` HTML과 **GET `/vehicle/catalog`만** 읽었다. 신호 변경·AVD 설치·0.5.0 화면 실행은 하지 않았다. 조사 결과 `.local/w5-console.html`, `.local/w5-console-catalog.json`은 정적 UI/카탈로그 근거이며 실제 주행 증거가 아니다.

| 실제 카드 | 제어 |
|---|---|
| Gear Selection | P/R/N/D 버튼 |
| Steering Angle | range -470~500, step 1, 카탈로그 단위 deg |
| Turn Signal State | Off / Blink left / Blink right / Flash left / Flash right |
| Brake Pedal | range 0~100, step 1, % |
| Cruise Target Speed | 목표 속도, m/s. **실제 속도 입력으로 쓰지 않음** |

현재 카탈로그에는 실제 차량 속도 입력 카드가 없다. AAOS에서 유효한 속도 **0**이 들어오는 것과 속도 미수신은 구별한다. 정상 모드에서 0을 임의로 미수신으로 바꾸지 않는다. 시연할 때만 P에서 Debug 체크박스 **속도 미수신 시연**을 켜고 기록 시작한다. 이 모드는 실제 속도를 규칙 입력에 사용하지 않으며 결과와 시간에 시연/미수신 표시를 남긴다. 속도나 감속 수치를 만들어 넣지 않는다.

### 실행 순서

1. Claude가 아래 명령으로 0.5.0 Debug APK를 빌드·설치하고 버전 검사. P·속도 0·UX 제한 해제·알림/속도 권한 허용.
2. 개수를 독립적으로 확인하려면 앱의 **기록 삭제**로 이전 요약을 지운다. **속도 미수신 시연**을 켜고 **기록 시작**. 표시가 기록 중으로 바뀌는지 확인.
3. Console의 Emulation Controls를 열고 **All domains**, 검색 해제. DevTools Console에 `scripts/recap-replay.js` 전체를 붙여 넣는다.
4. `driveRecapReplay.inventory()`로 표시된 카드 확인. `await driveRecapReplay.runFile()`로 프로젝트의 `recap-scenario-a.json`을 선택한다. 약 24초 시나리오가 끝나면 P로 복원된다. 중단은 `await driveRecapReplay.stop()`.
5. D에서 화면 차단, P 복귀 알림 확인. 앱에서 오늘 복기 보기 → 카드 4개. 맞아요/아니에요를 눌러 앱을 다시 열어도 선택이 남는지 확인.
6. P에서 중지 → 기록 삭제 → 같은 시연 옵션으로 시작 → `recap-scenario-b.json` 실행(약 25초). 기대 카드 2개이며 차선 변경 미사용으로 오분류하지 않아야 한다.
7. P에서 중지·기록 삭제. 요약/피드백과 알림이 제거되고 앱 재실행에도 없어야 한다. 기록 재시작 후 정상 집계되는지 확인.

JS는 `article.api-card`의 `h3`, 버튼, range input만 조작한다. 실제 HTML의 range는 input으로 수치 표시를 바꾸고 change로 전송하므로 두 이벤트를 순서대로 발생시킨다. 카드가 매번 재생성돼도 다시 검색한다. 모든 단계의 카드·범위·시간을 먼저 검증하며, 시간 지연이 500ms를 넘으면 중단한다. finally에서 조향0·깜빡이Off·브레이크0·P로 복원한다. 복원 실패 메시지가 있으면 Console에서 직접 확인한다. 크루즈 목표 속도·문·좌석·다른 제어는 만지지 않는다.

실제 속도 카드가 추가된 환경에서는 이름과 표시 단위가 확인된 `Vehicle Speed`/`Current Vehicle Speed`만 사용한다. 현재 환경에서는 이 분기를 실행하지 않았다. 단위가 없으면 추측하지 않고 미수신 시연 경로로 안내한다.

### 기대 카드

| 시나리오 | 유효 실제 속도가 입력되는 규칙 시험 | 현재 Console + 명시적 미수신 시연 |
|---|---|---|
| A | 깜빡이 사용 차선 변경(추정) 2 + 미사용(추정) 1 + 급제동(추정) 1 | 같은 조향 후보 3 + **브레이크 작동·급제동 확인 불가** 1. 4개 모두 UNSURE/애매함 |
| B | 회전 후보 1 + 굽은 길 후보 1 | 회전/굽은 길 후보 각 1, 모두 UNSURE. 좌/우는 표시하지 않음 |

속도 없이 급제동 카드에 감속 수치를 표시하거나 확정하면 실패다. 위 실제 속도 분기는 JVM 시나리오 시험으로 확인했으며 AVD 실측으로 승격하지 않는다.

추가 회귀: 조향을 먼저 움직이고 약 0.3초 뒤 깜빡이를 켜서 작은 왕복을 완성하면 늦은 깜빡이 후보. 정상 실제 속도가 없으면 역시 UNSURE다. 중립 작은 잡음·P 상태 조향만으로 차선 변경 카드를 만들면 실패다. 기어/속도 미수신·UX 제한·D에서는 기존처럼 카드 본문을 가려야 한다.

## Claude 빌드·검증 명령

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
if ($info -notmatch "versionCode='5'" -or $info -notmatch "versionName='0.5.0'") { throw 'Not 0.5.0 APK.' }
& $adb devices -l
$serial='emulator-5554' # 실제 devices 결과로 정한다.
& $adb -s $serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'Install failed.' }
& $adb -s $serial shell am start -n lab.pleos.observe/.MainActivity
& $adb -s $serial forward tcp:8088 tcp:8088
```

`$serial`은 실제 devices 결과로 정한다. 기존 AVD·키·로그를 지우지 않는다. 앱 자체 파일 저장과 별개로 필요하면 ObserveLab 로그를 프로젝트 `.local/evidence/`에 수동 수집한다. UI PID만으로 필터링하지 않는다.

선택 NaviHelper 메타데이터 탐침은 네트워크가 되는 Claude 환경에서 AAR을 먼저 조사한 다음에만 빌드한다. 기본 규칙 빌드는 이 의존성이 필요 없다:

```powershell
.\gradlew.bat :app:assembleDebug -PincludeNaviProbe=true --no-daemon --console=plain
```

기간 정리 검증은 실제 내부 파일과 재접근 결과로 한다. 날짜 함수/codec 시험 통과만으로 Android 파일 삭제가 실측 통과했다고 보고하지 않는다. 손상 파일 원본 보존·오류 표시·사용자 삭제 후 복구, 자정 날짜 변경, 강제 종료 후 마지막 저장 시점도 별도 확인한다. OS/다른 앱 상태를 바꾸는 날짜 시험은 별도 시험 AVD에서 수행한다.

## 검증 결과와 변경 파일

검증 결과:

- **JVM 107/107 통과**: 기존 52개를 유지하고 신규 55개 추가. 조향/속도/시간 경계, 늦은·잘못된 방향의 깜빡이, 관측 공백, null/NaN/감속 overflow, P 종료, 코드화/보관/피드백, 실제 JSON 2종의 속도 정상·미수신 경로를 검증했다. `.local/w5-jvm-tests.txt`.
- **Node 10/10 통과**: 오프라인 가상 DOM·시계에서 두 시나리오 실행·P 복원·중지·카드/범위 사전 확인·목표속도 제외·단위 변환·잘못된 host 차단. `.local/w5-replay-tests.txt`. 실제 브라우저/AVD 실행 증거가 아니다.
- 전체 앱·logic 소스의 **직접 Kotlin 타입 검사 통과**: 설치된 API 36 SDK와 Vehicle 2.3.2를 사용했다. 임시 `BuildConfig.DEBUG=true` stub을 사용하는 소스 검사이며 Gradle Debug/Release 빌드·리소스 링크·APK 생성의 증거가 아니다. `.local/w5-kotlin-types.txt`, `.local/w5-compile/source-types.jar`.
- `git diff --check`, manifest XML 확인 통과. 기존 CarSafetyClient·Vehicle/탐침 클라이언트·게임/잠금 순수 로직 변경 없음. Legacy의 게임/탐침 UI 본문도 원본과 동일함을 비교했다.
- **미검증**: Gradle/설치/실제 화면·IPC·Android 파일 저장/삭제·Job 등록/실행·P 알림·0.5.0 실수신. **미완료**: AAR로 확인한 실제 TBT 의미 디코더/회전 보조 연결. [체크리스트](WORK-CHECKLIST.md)의 완료 5/6과 외부 Claude 검증을 구분했다.

- `logic/.../recap/RecapModels.kt`: 임계값·구간·카드·피드백·Jev 계약.
- `RecapEngine.kt`: 순수 분할·판정·관측 품질·감속.
- `RecapCodec.kt`, `RecapText.kt`: 요약 전용 스키마·보관 날짜·피드백·카드 근거 문구.
- `logic/src/test/.../recap/RecapRulesTest.kt`, `RecapEngineTest.kt`, `RecapCodecTest.kt`, `RecapScenarioTest.kt`: 경계·오분류·데이터 공백·보관·codec·피드백·시나리오 JVM 시험.
- `app/.../MainActivity.kt`: 오늘 복기 시작/목록/P 잠금/피드백/삭제.
- `LegacyLabActivity.kt`: 기존 Main의 게임·진단·탐침 UI 보존, 진단 진입/복귀 버튼.
- `RecapRecordingSession.kt`: SDK 콜백을 순수 입력으로 연결, 요약만 전달.
- `RecapRepository.kt`, `RecapProvider.kt`: 단일 저장 소유자·AtomicFile·private IPC·보관 정리 JobService.
- `DriveRecorderService.kt`: 기존 FGS를 요약 기록에 연결, P 중지·복기 준비 알림.
- `NaviTbtProbe.kt`: 선택적 수신 여부 탐침, 미확인 payload 판정 비활성.
- `app/build.gradle.kts`, `AndroidManifest.xml`: 0.5.0·비공개 Activity/provider/job·NAVI_ROUTE 조회 권한·정리 Job 복원 권한·선택 의존성.
- `scripts/inspect-recap-sdk.ps1`, `recap-replay.js`, `recap-scenario-a.json`, `recap-scenario-b.json`, `test-recap-replay.mjs`: 근거 재현·Console 시연·오프라인 DOM 시험.
- `README.md`, 이 문서, `WORK-CHECKLIST.md`: 현재 기능·근거·인계.

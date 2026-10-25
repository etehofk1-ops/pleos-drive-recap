# 13주차 — 구동 안정성 수정 / 1.4.1(17)

2026-10-24. [1.4.0 AVD 원본 실측](WEEK13_RUNTIME_CHECK.md)은 그대로 보존한다. 이 문서는 코드·오프라인 시험 결과와 새 AVD 시험 절차다. **1.4.1 APK·AVD 시간/CPU/RAM은 Claude가 다시 측정한다.**

## 원인과 변경

| 항목 | 확인한 원인 | 변경 |
|---|---|---|
| 같은 인스턴스 복귀 | onPause의 frozenUi가 onResume의 pendingUi로 넘어가지 않았다. | 메모리 체크포인트→복원 대기→새 안전 READY→복기/페이지/두 열 앵커·포커스/대화상자 복원. |
| 프로세스 복원 | Bundle을 showRecap/offset/pendingScroll에 직접 적용하고 대기 체크포인트로 만들지 않았다. 안전 READY 전에 다시 pause/save되면 onPause가 화면 필드를 지우고, onSave는 frozenUi/pendingUi가 없어 저장하지 못한다. | Bundle도 동일한 pendingRoute로 읽는다. WAIT 중 재-pause/save에서도 유지하며 최초 캡처 시각을 갱신하지 않는다. 두 열 논리 앵커·dp 오프셋·포커스도 저장한다. |
| 복기 열기 | UI 스레드에서 데이터 해독과 카드 100개 뷰 생성. | 클릭 즉시 가벼운 복기 틀+스피너+‘불러오는 중’. 첫 draw 후 IPC, IO에서 카드/AI JSON 해독. 화면 근처+앞뒤 한 행만 프레임마다 1개 생성하고 화면 밖 뷰는 높이를 남겨 재활용. |
| 주행 CPU | 속도 고빈도 구독, 콜백마다 시각/문자열/로그 생성, 잠금 중 같은 뷰 트리 반복 생성. | 화면 클라이언트만 속도 NORMAL(1Hz 요청), 기어 ONCHANGE, 동일 상태 통지 생략. 잠금/안전 게이트가 바뀔 때만 잠금 화면 재생성. Debug 속성 로그는 전체 합쳐 5초에 1줄, Release 0줄. |
| 기록 로그 | Release에서도 원시 신호마다 값 없는 이벤트명이 남았다. | DriveTrialLog 진입부에서 차단. Release는 recap.card/segment.start/motion.*/summary.* 판정·요약 이벤트만 남긴다. 원시 콜백·카운터 문자열은 Debug에서만 만든다. |

**프로세스 종료 실측의 한계:** `.local/soak/restore.sh`를 직접 읽었다. A(일반 복귀)에서 이미 시작 화면으로 돌아간 뒤, 복기 화면을 다시 열지 않고 B(am kill)를 수행한다. B가 시작 화면을 저장한 상황과 독립적인 프로세스 복원 결함을 구별할 수 없다. 위 Bundle 대기 경로 누락은 코드로 확인한 별도 결함이며, 기존 B의 유일한 실측 원인이라고 단정하지 않는다. 아래 B는 반드시 복기를 새로 열고 시작한다. Debug `ui.create/resume/pause/save` 로그에 Bundle 존재·task·recap·offset만 남겨 새 실행과 기존 task 복원을 구별한다.

복원 만료는 `UI_RESTORE_TTL_MS = 30분`. 정확히 30분까지 허용, 초과·단조 시각 역전(재부팅 등)은 폐기한다. 알려진 주행 신호는 즉시 DISCARD, 미수신은 WAIT이며 이전 안전 값을 복원하지 않는다. 진단 입력·대화상자 초안·차량 값·AI 본문은 Bundle에 쓰지 않는다. 텍스트 내용을 포함하는 스크롤 태그도 제외한다. 안전한 화면 위치만 OS에 저장하고 기존 진단 초안은 같은 프로세스 메모리에서만 유지한다.

페이지 크기 100은 유지한다. 읽기 요청 세대·페이지가 달라지면 오래된 응답을 버리며, 잠금/백그라운드에서는 카드/예약 작업/팩토리 참조를 제거한다. 가상 행 높이는 첫 생성 전 400dp 추정이고 이후 실제 측정 높이를 보존한다. 논리 카드 앵커로 스크롤을 복원한다. 스크롤바 길이는 미측정 행의 높이가 확정되며 조금 달라질 수 있다.

기록 서비스 구독(`DriveSignalSubscriptions`)·조향/깜빡이/감속/내비 규칙·AI 계약·저장 보관 정책은 변경하지 않는다.

## 변경 파일

- `MainActivity.kt`: 수명 주기/Bundle 대기 복원, 안전 게이트·읽기 세대, 즉시 틀/IO 해독/카드 가상화 연결, Debug 시간 로그.
- `DeferredReviewColumn.kt`(신규): 화면 근처 행만 프레임별 생성·오프스크린 뷰 제거·앵커 유지.
- `CarSafetyClient.kt`: 화면 전용 속도/기어 주기·동일 통지 생략·Debug 5초 로그 표본.
- `DriveTrialLog.kt`, `DriveRecorderService.kt`: Release 원시 이벤트/문자열·카운터 작업 차단.
- `logic/.../RuntimeUi.kt`, `RuntimeUiTest.kt`(신규): 복원/만료·저장 가능 앵커·응답 세대·페이지/가시 행·로그 정책과 시험.
- `app/build.gradle.kts`, `scripts/check-submission.ps1`, `scripts/test-submission.mjs`: 17/1.4.1 정합화.
- `README.md`, `docs/WORK-CHECKLIST.md`, 이 문서: 실행·검증·인계.

## 오프라인 검증

로컬 명령:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-logic-offline.ps1
node --test ai-proxy/server.test.mjs scripts/test-recap-replay.mjs scripts/test-submission.mjs scripts/test-navi.mjs scripts/test-demo-navi.mjs scripts/test-responsive.mjs scripts/test-pleos-design.mjs
```

JVM **282/282**(기존269+신규13), Node **76/76**. Debug/release 전체 소스 직접 Kotlin 타입 검사 통과. `.local/w13-jvm-tests.txt`, `w13-node-tests.txt`, `w13-debug-types.txt`, `w13-release-types.txt`가 근거다. JVM의 가시 행 시험은 일정/페이지 정책을 검증하며 Android 레이아웃 속도·OS Bundle 전달·CPU 통과를 입증하지 않는다. 직접 Kotlin 타입 검사는 설치된 Android/Car/Pleos JAR 대상이며 Gradle/APK 빌드와 다르다.

`git diff --check` 통과. 제출 소스 패턴0건, 전체 SourceOnly 검사는 기존 Git 메시지 이메일27건으로 실패했고 제외 경로 추적43개가 남아 있다. 이력·제외 파일을 변경하거나 커밋/push하지 않았다. 새 APK는 만들지 않았다.

API 근거: [CarPropertyManager](https://developer.android.com/reference/android/car/hardware/property/CarPropertyManager) NORMAL=1Hz, ONCHANGE, registerCallback; [Android UI 상태 보존](https://developer.android.com/topic/libraries/architecture/saving-states) 시스템 프로세스 종료와 사용자 task 제거/force-stop의 구분. 장치가 지원하는 최소/최대 속도로 구독 주기가 제한될 수 있으므로 실제 수신 빈도와 CPU는 별도 확인한다.

## Claude 실행 순서

### 0. 빌드·설치

프로젝트 루트에서 실행한다. 기존 서명·기록을 보존하며 삭제/force-stop하지 않는다.

```powershell
$env:GRADLE_USER_HOME = "$PWD\.gradle-user-home"
.\gradlew.bat :logic:test :app:assembleDebug --no-daemon --console=plain
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$serial = 'emulator-5554' # devices -l의 실제 값
& $adb -s $serial install -r app/build/outputs/apk/debug/app-debug.apk
& $adb -s $serial shell am start -n lab.pleos.observe/.MainActivity
```

1.4.1(17) 확인. P·속도0·UX 해제, 이미 있는 120개 카드를 사용한다. 부족하면 기존 G를 사용하되 **기록 삭제 없이** 준비한다. 같은 해상도/AVD 코어/AI 설정으로 1.4.0과 비교한다.

### A. 일반 복귀 / B. 프로세스 종료 (독립 시험)

1. **A 시작:** 오늘 복기→다음 카드 100개→오른쪽 목록 스크롤. 왼쪽도 독립 스크롤해 페이지/첫 보이는 카드/포커스 열을 캡처한다. Home→내비→런처로 복귀. 안전 신호 확인 후 같은 복기/offset100/앵커·포커스여야 한다.
2. **B는 반드시 새로 준비:** A 결과와 무관하게 복기→offset100→스크롤을 다시 확인·캡처한다. Home→내비로 나가고 3초 후 `ui.save bundle=true recap=true offset=100`을 확인한다. 그 다음 아래 명령으로 종료·재진입한다.

```powershell
& $adb -s $serial shell pidof lab.pleos.observe
& $adb -s $serial shell pidof lab.pleos.observe:drive_recorder
& $adb -s $serial shell am kill lab.pleos.observe
& $adb -s $serial shell pidof lab.pleos.observe # 이전 메인 PID가 실제 종료됐는지 확인
& $adb -s $serial shell monkey -p lab.pleos.observe -c android.intent.category.LAUNCHER 1
& $adb -s $serial logcat -d -s ObserveLab:I > .local/w13-restore-log.txt
```

3. 메인 PID 변경, 기록 중이었다면 서비스 PID 생존 확인. `ui.create bundle=true recap=true offset=100`·같은 task인지 확인하고 READY 뒤 같은 페이지/앵커로 복원되는지 캡처한다. 메인 PID가 안 죽으면 B를 통과로 기록하지 않는다. Bundle가 없거나 task가 달라지면 시스템 실행 경로를 함께 보고한다. 최근 앱에서 task 제거·Back finish·`am force-stop`은 이 시험에 사용하지 않는다.
4. B 시작 후 Car 안전 콜백을 늦춰 WAIT 중 Home/재진입 또는 회전·리사이즈해도 pending route가 유지되는지 확인한다. 민감한 진단 입력은 프로세스 종료 뒤 복원되지 않아야 한다.
5. D/UX 활성 또는 속도>0을 받으면 카드·체크포인트가 사라지고 P 복귀 시 시작 화면이어야 한다. 30분 초과 복귀도 시작 화면(경계/시각 역전은 JVM 시험 포함).

### C. 복기 열기·페이지·리사이즈

1. P, 카드120개에서 시작 화면→오늘 복기를 여러 번 실행. 누른 프레임에 틀+스피너+‘불러오는 중’, 이어서 오른쪽 첫 카드가 나타나야 한다.
2. Debug 로그 `ui.recap.shell elapsedMs`, `ui.recap.firstCard elapsedMs offset=...`를 확인한다. 첫 카드는 실제 draw 경로에서 기록한다. **첫 카드<500ms**가 목표다. 좁은 화면은 요약 아래로 내려야 카드가 보이므로 첫 draw 목표는 넓은 화면에서 비교한다.
3. `ui.recap.slowTile`(생성≥100ms)이 없어야 한다. 이 로그는 생성만 측정하므로 UI 스레드 전체100ms 초과 여부는 System Trace/Android Studio CPU profiler로 measure/layout/draw까지 확인한다. `dumpsys gfxinfo lab.pleos.observe reset`→열기/스크롤→`dumpsys gfxinfo lab.pleos.observe framestats`도 보관한다.
4. 깊이 스크롤→맞아요/아니에요→AI 갱신→100개 다음 페이지→최근 카드 이동에서 카드 누락/중복/앵커 점프를 확인한다. 두 열→한 열→두 열 리사이즈·주간/야간도 같은 상태를 유지해야 한다. 로딩 중 Home/D 전환 시 늦은 카드가 나타나면 실패다.

### D. 주행 CPU·로그·RAM

1. 기록 시작→기존 G 실행→D 유지. UI 메인과 `:drive_recorder` PID를 별도로 기록한다. 메인 `car.property.callback`은 Debug에서 5초당 최대1줄, 동일 잠금 화면 반복 재생성이 없어야 한다. Debug drive.* 원시 로그와 판정 결과는 기존대로 유지한다.
2. 동일 AVD 코어/해상도에서 60초 이상 아래를 수집한다. 서비스 판정(놓침/늦음/급제동/좋아요) 개수가 기존 G 결과와 같아야 한다.

```powershell
& $adb -s $serial shell top -b -d 1 -n 60 > .local/w13-cpu-top.txt
& $adb -s $serial shell dumpsys meminfo lab.pleos.observe > .local/w13-main-mem.txt
& $adb -s $serial shell dumpsys meminfo lab.pleos.observe:drive_recorder > .local/w13-recorder-mem.txt
```

3. 서명 설정이 준비됐으면 `:app:assembleRelease`로 같은 시험을 별도 서명 호환 AVD에서 반복한다. Release에는 `car.property.callback`, `drive.speed/steeringAngle/turnSignal/brakePedal/gear/gearSelection/ux/heartbeat`가 한 줄도 없어야 한다. 판정 이벤트는 남아야 한다. Debug/Release를 섞어 평균을 내지 않는다.
4. 메인·기록·합계 평균/최대 CPU, top의 % 정의(코어1개 기준/전체 환산), PSS 합계를 함께 보고한다. 목표 CPU<5%/RAM≤400MB의 심사 해석과 실제 단말 검증은 별도다. 수정 전 6.2%→수정 후 측정값으로 기재하고 추정 수치를 넣지 않는다.

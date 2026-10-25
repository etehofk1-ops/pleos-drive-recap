# 2주차 검증·Claude 인계 · 2026-10-22

추리 문제 3종, 시작/결과/진단/잠금 화면과 순수 진행 reducer를 구현했다. **JVM 41/41, 실제 Vehicle SDK를 포함한 Debug·Release Kotlin 컴파일 통과. 2주차 APK 빌드·설치·화면 실기 검증은 미완료**다.

## 1주차 전제와 보존

이번 사용자 명세의 Claude 보고를 전제로 한다: 창문 네 자리 원시 0~10, 독서등 네 자리 OFF/ON 실제 수신, Console 즉시 반영, 기어 D에서 플랫폼 차단과 앱 잠금, P 복귀 해제. 이 실기 검증을 이번 세션에서 다시 수행했다고 주장하지 않는다.

Claude가 고친 `uses-library android.car required="false"`를 보존했다. Debug/Release 병합 매니페스트에서도 false를 확인했다. `CarSafetyClient.kt`, 기존 `shouldLock` 판정, 기존 10개 테스트, 두 명세 파일과 `.local/debug.keystore`는 변경하지 않았다. 새 차량 권한·신호·계정·키를 추가하지 않았다.

## 바뀐 파일

| 파일 | 변경 |
| --- | --- |
| `logic/src/main/kotlin/lab/pleos/observe/logic/Quiz.kt` | 신규. 4좌석만 파싱, 3종 생성·차이·유일성·시드 보기·릴리스 로그 정책 |
| `logic/src/main/kotlin/lab/pleos/observe/logic/LabSession.kt` | 신규. 순수 reducer, 즉시 무효화, 잠금 폐기, 새 읽기 요청·늦은 응답 차단 |
| `logic/src/main/kotlin/lab/pleos/observe/logic/Observation.kt` | 신호 매핑용 안정 ID 추가. 안전 판정은 그대로 |
| `logic/src/test/kotlin/lab/pleos/observe/logic/QuizTest.kt` | 신규 16개. 생성·차이·보기·유일성·결측·로그 |
| `logic/src/test/kotlin/lab/pleos/observe/logic/LabSessionTest.kt` | 신규 15개. 무효화·잠금·재조회·늦은 클릭/응답·재시도 |
| `app/src/main/java/lab/pleos/observe/MainActivity.kt` | 시작·3종 문제·결과·숨은 진단. 2×2 좌석, 큰 버튼. 뷰 상태 저장 해제 |
| `app/src/main/java/lab/pleos/observe/PleosObservationClient.kt` | 독서등도 검증된 4자리만 사용. 기억/비교 시 실제 GET, 4초 제한, 오래된 GET 차단 |
| `app/src/main/java/lab/pleos/observe/EvidenceLog.kt` | Debug만 차량 값·수신 시각·예외. Release는 이벤트·정답 여부만 |
| `app/build.gradle.kts` | BuildConfig 생성, versionCode 2 / 0.2.0 |
| `scripts/test-logic-offline.ps1` | logic의 전체 소스/테스트를 발견하도록 확장 |
| `.gitignore` | 프로젝트 내부 Kotlin 작업 캐시 제외 |
| `README.md` | 현재 흐름·빌드·로그·설치 안내로 갱신 |
| `docs/WEEK2_HANDOFF.md` | 이 기록과 Console 시험표 |

## 실행한 검증

| 검증 | 결과 / 근거 |
| --- | --- |
| 기존 테스트 | ObservationTest 10개 모두 통과. 원본 테스트 변경 없음 |
| 신규 테스트 | QuizTest 16개, LabSessionTest 15개 모두 통과 |
| 유일성 | 창문 값 0/5/10 네 자리 × 독서등 16조합 = 1,296개 상태를 순회. 생성된 모든 문제의 단서 교집합이 정답 하나임을 확인 |
| 보기 | 숫자 정답 0~8 모두 정답 포함·중복 없는 4개, 시드 재현성 확인. 변경 문제도 동일 |
| 무효화 | 정답 수가 그대로여도 원시 값 변경 시 새 문제. 가용 좌석 변경 시에도 무효화. 수신 시각만 변경이면 유지 |
| 기억 | 0개/2개 이상은 처음 스냅숏 유지, 정확히 1개만 출제. 같은 자리 창문+등 변경도 2개. 원래 좌석이 결측이면 정답 생성 안 함 |
| 잠금 | 전 단계·결과·진단에서 폐기, P 복귀는 시작 화면. 백그라운드·늦은 응답으로 복구되지 않음 |
| 부분 Gradle | `:logic:test :app:compileDebugKotlin :app:compileReleaseKotlin --offline`가 BUILD SUCCESSFUL / exit 0. `.local/w2-compile-and-tests.txt` |
| 실제 결과 XML | `logic/build/test-results/test/TEST-*.xml`: tests 총 41, failures=0, errors=0 |
| 로그 정책 | 순수 로그 포맷 테스트 + 생성된 Release BuildConfig.DEBUG=false 확인. Release EvidenceLog 바이트코드에 Throwable 로그 오버로드 없음. `.local/w2-release-log-bytecode.txt` |
| Manifest | 병합 Debug/Release의 android.car required=false, DO 미선언 확인 |
| 전체 APK 빌드 | 실패. `compileDebugJavaWithJavac`에서 외부 Android SDK JAR에 AccessDeniedException. `.local/w2-gradle-build.txt` |
| 기존 APK 확인 | 남아 있는 `app/build/outputs/apk/debug/app-debug.apk`는 aapt2로 **versionCode 1 / 0.1.0** 확인. 2주차 산출물로 사용하면 안 됨 |
| 실기/시각 확인 | 2주차 설치·터치·레이아웃·실제 fresh GET·릴리스 logcat은 이번 세션 미검증 |

SDK 36에서 API 34 호환용 CarPropertyManager 콜백과 기존 WindowInsets getter의 deprecated 경고가 있다. 새 API로 임의 교체하지 않았다. 전체 APK 실패는 Kotlin 코드 오류와 구별하며, SDK 파일 권한을 바꾸거나 SDK를 복사해 우회하지 않았다.

새 GET은 여러 비동기 응답으로 구성된다. 원자적 동시 스냅숏이라고 주장하지 않는다. “기억하기”와 “바뀌었어요” 후 읽기 완료 화면이 나올 때까지 입력을 바꾸지 않는 것이 시험 절차다. GET 오류/시간 초과는 오래된 캐시를 새 값으로 인정하지 않고 재시도 안내를 띄운다.

## Claude 빌드·설치

프로젝트 루트에서 README 빌드 명령을 사용한다. 전체 빌드가 실패하면 설치로 넘어가지 않는다. 성공 후 `aapt2 dump badging`으로 versionCode=2 / 0.2.0 확인 후 설치한다. 원래 AVD를 그대로 사용한다.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
.\gradlew.bat :logic:test :app:assembleDebug :app:assembleRelease --offline --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Build failed; do not install the old APK.' }
```

화면은 2560×1440 가로에서 직접 확인한다. 설명이 길 때 스크롤로 결과 버튼에 도달할 수 있어야 한다. 진단은 아래 좌석으로 스크롤한 상태에서 값이 들어와도 위치가 유지돼야 한다. 게임 중 동일한 안전 값 재수신으로 선택 버튼이 계속 다시 만들어지면 안 된다.

## Claude Console 시험표

`adb -s <실제 serial> forward tcp:8088 tcp:8088` 후 `http://127.0.0.1:8088/`를 연다. Emulation Controls의 Convenience → Window Position / Reading Lights, Motion → Gear Selection을 사용한다. 변경 전 값을 기록하고 시험 후 복구한다. 앱 설치·로그 명령은 README에 있다.

아래 배열 순서는 **1열 왼쪽, 1열 오른쪽, 2열 왼쪽, 2열 오른쪽**이다. 창문 배열은 **SDK 원시 값**이다. Console이 퍼센트를 쓰면 예를 들어 원시 5를 얻기 위해 50%를 시도하되, 제목 길게 누르기의 진단에서 실제 원시 값을 먼저 확인한다. 변환 비율을 검증 없이 고정 사실로 간주하지 않는다.

| 순서 | Console / 앱 조작 | 기대 화면·판정 |
| --- | --- | --- |
| 1 | 기어 P, 속도 0, 제한 비활성 확인 후 앱 열기 | 제목·부제·3개 버튼. 원시 창문/등 값은 시작 화면에 없음. 제목 길게 누르면 진단, 돌아오면 시작 |
| 2 | 창문 `[0,5,10,4]`, 등 `[ON,OFF,ON,OFF]`로 맞춘 뒤 문제 1 | 현재 값/켜진 자리 목록 없이 문제 문구와 보기 4개. 보기 중 **4** 포함. 4 선택 → 맞음, 등 2개(1열 왼쪽·2열 왼쪽), 창문 2개(1열 오른쪽·2열 왼쪽) 설명 |
| 3 | 같은 상태에서 문제 1 출제 후, 답하기 전에 2열 오른쪽 창문 4→5 | “차가 바뀌었어요, 새 문제를 낼게요”. 새 정답 **5**. 옛 보기의 늦은 클릭은 채점되지 않음 |
| 4 | 문제 1을 새로 열고 절반 이상 창문을 5→6 등으로 이동 | 정답 수가 같아도 원시 값이 달라졌으므로 무효화. 같은 값 재수신만으로는 무효화 안 함 |
| 5 | 창문 `[0,0,0,0]`, 등 전부 OFF 후 문제 2 | 정답을 만들지 않고 “창문이나 독서등을 하나 바꿔 보세요” 안내. 좌석 임의 선택 문제 생성 안 됨 |
| 6 | 5번에서 1열 왼쪽 독서등만 ON | 상태 변경 후 2~3개 단서와 2×2 선택 화면 생성. 단서들의 공통 좌석이 반드시 하나. 생성 시드에 따라 단서·정답이 달라질 수 있으므로 무조건 1열 왼쪽이 답이라고 가정하지 않음 |
| 7 | 문제 2 답하기 전 1열 왼쪽 등 OFF로 복귀 | 이전 문제 무효화 안내. 네 자리 동일 상태이므로 새 문제 대신 유일한 답 없음 안내 |
| 8 | 창문 전부 0, 등 전부 OFF. 문제 3 → 기억하기 → 읽기 완료 대기 | “화면을 가렸어요”, 한 사람이 하나만 바꾸라는 안내. 현재/기억 값은 화면·접근성에 없음 |
| 9 | 아무것도 안 바꾸고 바뀌었어요 | SDK GET 재조회 후 “아직 안 바뀌었어요”. 최초 기억 유지, 다시 바꿀 수 있음 |
| 10 | 1열 왼쪽 창문 원시 0→1 후 바뀌었어요 | 좌석×종류 보기 4개에 **1열 왼쪽·창문** 포함. 선택 → 맞음, “열림 정도”와 0→1 설명 |
| 11 | 8번부터 새로 시작. 1열 왼쪽 창문 0→1과 2열 오른쪽 등 OFF→ON 후 바뀌었어요 | “하나만 바꿔 주세요”. 새 기준으로 덮어쓰지 않음. 2열 오른쪽 등만 OFF로 복원 후 다시 누르면 1열 왼쪽 창문 문제 생성 |
| 12 | 8번부터 새로 시작. 같은 1열 왼쪽 창문과 독서등 둘 다 변경 | 좌석 하나여도 신호 두 개이므로 “하나만 바꿔 주세요” |
| 13 | 문제 화면 / 가림 단계 / 결과 / 진단 각각에서 기어 D | 플랫폼 차단 또는 앱 잠금. `session.discarded` 이벤트. P 복귀 후에는 **시작 화면**, 옛 문제·기억·결과 복구 안 됨 |
| 14 | 기억하기/바뀌었어요 읽기 중 홈 이동 또는 D 전환 후 복귀 | 늦은 GET 완료가 기억/문제를 되살리지 않음. 시작 화면 유지 |
| 15 | 결과에서 다른 문제 / 처음으로 | 다른 문제는 숫자→자리→변경 순환, 처음으로는 세 버튼 화면 |
| 16 | 재실행·Activity 재생성 | 스냅숏·점수·진행 복구 없음. 새 시작 |

이 AVD에서 좌석 미수신/미지원·GET 실패·시간 초과를 재현할 수 있는 공개 입력이 없다면 해당 실기 항목은 미검증으로 남긴다. 다른 신호나 비공개 주입 명령을 도입하지 않는다. JVM에서는 잘못된 값·미지원·한 신호만 수신된 좌석을 제외하고 최소 좌석 기준을 적용하는 것을 검증했다.

속도·UX 독립 입력 수단이 있으면 원래 1주차 절차로도 확인한다. Cruise Target Speed를 실제 속도로 대신하지 않는다. 잠금 판정의 미수신/음수/NaN 분기는 기존 JVM 테스트가 유지된다.

## 로그 확인과 남은 작업

Debug ObserveLab에서 `quiz.invalidated`, `quiz.*.answered correct=true/false`, `memory.read.requested`, `memory.remembered`, `memory.compare.requested`, `memory.retry`, `memory.question.ready`, `session.discarded`, `session.ready`를 확인한다. Debug에서만 `window.refresh`, `light.refresh`의 좌석·값·시각을 확인할 수 있다.

Release는 이벤트 이름·정답 여부 외의 필드가 없어야 한다. Release 실기 로그는 서명된 Release 설치 후 별도 확인이 필요하며, 이번 코드/바이트코드 검증을 실기 확인으로 올려 보고하지 않는다.

남은 일은 전체 APK 빌드, 0.2.0 설치, 위 Console 표의 실제 화면·재조회·입력·잠금 검증이다. 오류는 정확한 빌드 출력 또는 재현 단계와 화면 상태를 이 Codex 대화에 전달한다. 자료·로그는 이 프로젝트의 `.local/evidence`에만 둔다. Git 커밋·푸시·배포는 수행하지 않았다.

## Claude 에뮬레이터 검증 (2026-10-22)

Pleos_Connect_v2_1 에뮬레이터에 versionCode 2 / 0.2.0 디버그 APK 설치 후 Virtual Console로 확인. 캡처는 `docs/evidence/w2/`.

| 시나리오 | 결과 |
|---|---|
| 시작 화면 3버튼 | 통과 |
| 문제 1: 창문 [0,5,10,4]·1·2열 왼쪽 독서등 ON → 정답 4, 오답 시 이유 설명 | 통과 |
| 문제 1: 답 전 독서등 변경 → "차가 바뀌었어요, 새 문제를 낼게요" + 새 정답 | 통과 |
| 문제 2: 단서 2개로 유일 좌석, 정답 설명 | 통과 |
| 문제 2: 전부 닫힘·꺼짐 → 출제 거부·변경 안내, 하나 바꾸면 자동 재출제 | 통과 |
| 문제 3: 무변경 → "아직 안 바뀌었어요", 2개 변경 → "하나만 바꿔 주세요", 1개 → 보기 4개·정답 판정 | 통과 |
| 문제 중 기어 D → 플랫폼 차단 + session.discarded, P 복귀 → 시작 화면 | 통과 |

시험 함정: Virtual Console은 좌석 select를 바꾸면 카드가 다시 그려진다. 바꾼 뒤 카드를 다시 찾아 버튼·슬라이더를 조작해야 한다(낡은 요소를 누르면 이전 좌석으로 전송됨).

# 15주차 — 비대칭 조향 정착 창 / 1.5.2(20)

2026-10-24. **구현·회귀는 완료했지만 성능 목표는 일부 미달이다.** 제공된 AVD 재생 두 건은 모두 `LANE_SIGNALLED / LANE_NO_SIGNAL / LANE_LATE_SIGNAL / TURN / TURN / HARD_BRAKE`, UNSURE0이다. 실주행1분 fixture는 화면 카드0이다. 지정 seed의 실측 잡음 비대칭40km/h는96.5%로 개선됐지만 **60km/h78.7~82.2%는85% 목표 미달**, 대칭80km/h69.3%도70% 미달이다. 곡선/직진 오탐 상한은 충족하지만 일부 조건에서 전후0.5%p 증가하여 **비증가 조건도 완전히 충족하지 못했다.** APK/AVD 실행은 Claude의 별도 검증이다.

## 변경 구조·상수

기존 엔진은 처음 중립에 들어온 순간의 적분값을 고정했다. 이제 두 꺾음이 확인되고 두 번째 가속이 줄어드는 구간부터 최대2초 동안 작은 보정을 관측한다. 주 조향 구간의 종료/지속 시간과 정착 관측 시간을 분리하며, 깜빡이3초 lookback·시작 기준·late ms 의미는 유지한다. 중립 이후 깜빡이가 앞선 구간을 면책하지 않는다. 기존 기준으로 이미 완결된 큰 왕복 조향은 일찍 닫을 수 있다.

| 상수 | 값 | 근거·범위 |
|---|---|---|
| `SETTLE_MS` | 2,000ms | 요청한 최대 정착 관측 창. 드문 콜백에서도 해당 마감까지만 적분한다. |
| `SETTLE_MAX_MPS2` | 0.45m/s² | 기존 진입0.3의1.5배 이내를 작은 보정으로 한정. 넘는 새 꺾음에서는 기존 구간을 닫고 이후 콜백으로 새 구간을 연다. |
| `SETTLE_PEAK_FRACTION` | 0.65 | 두 번째 꺾음이 해당 최대 가속의65% 이하로 줄어들고 실제로 감소한 때만 정착 진입. 동일 시각 콜백의 같은 값을 감소로 보지 않는다. |
| `SETTLE_MIN_MS` | 500ms | 아래 보완 기준은 기존 저역 통과 시간상수0.5초 이상 관측한 뒤에만 적용. |
| `SETTLE_RAW_DEG` | 양쪽 각각5° | 정착 보완의 왕복 근거. 진입 기준이 아니며 속도 정규화 진입0.3m/s²는 그대로다. |
| `SETTLE_RETURN_RAD` | 0.08rad ≈4.58° | 위 왕복+정착 증거가 있는 경우만. 전역0.06rad·비율0.8, 약한 신호0.025rad/0.4는 유지. |
| `SETTLE_PATH_RATIO` | 2.0 | 보완 구간에서 두 꺾음+작은 세 번째 보정을 허용(누적 방향≤최대 방향의4배). 일반 기준1.6은 유지. |
| `RECIPROCAL_FIRST_IMPULSE_MPS` | 0.5m/s | 첫 반전까지5° 이상 조향 근거가 있으면 짧은 비대칭 첫 꺾음을 잡음으로 잘라 버리지 않게 함. 약한 신호는 기존0.7 유지. |

전역 RETURN_RAD를0.12로 풀지 않았다. 특히 **run2에는 중립 뒤 보정 조향이 없으므로 관측 창만 늘려서는 약4° 남는 방향을3.4° 이내로 만들 수 없다.** 0.08 보완 기준은 이 한계를 명시적으로 반영한 휴리스틱이며 실차로 보정된 정책이 아니다. 양쪽 원시5°·정착500ms·잔여 방향/최대≤0.8·변위1.75~5.5m·주 구간2.5~8초·왕복 및 누적 방향 조건을 모두 통과해야 한다. 곡선/직진 오탐 평가에 이 보완을 포함했고, 비증가 실패도 숨기지 않는다.

정착 중 전체 방향/각속도 적분을 이어 가되, 주 구간 변위에서는 **실제로 관측한 방향 보정량만** `y_body - (heading_body - heading_settled) * travel_body / 2`로 보상한다. 최종 방향을0으로 강제하지 않는다. 주 구간의 거리·지속 시간과 최종 방향 관측 범위가 다르며, 이것은 모델 특징이지 실제 차선 위치 측정값이 아니다. 고정 크기 필터/요약만 유지한다.

약한 왕복 신호는 순간적으로 모양이 맞아도2초 관측을 끝까지 요구한다. 그 전에 큰 새 꺾음·종료·P로 끊기면 불완전 STEERING으로 남겨 기존 화면 숨김 정책을 따른다. 입력 실패·시계5초 이상 공백도 확정하지 않는다. 이 보수적 조건은 오탐을 줄이는 대신 **대칭80~120km/h 재현율을 낮추는 대가**가 있다. 저속 회전·내비 융합·깜빡이 시각·급제동400ms/3m/s² 기준은 유지했다.

저장 스키마는v5: `LateralSummary.settledMs`(0~2,000) 숫자 하나만 추가. v1~v4 읽기·피드백 유지 시험을 통과한다. 위치·VIN·원시 이력을 추가 저장하지 않으며 보관 정책은 그대로다.

## 지정 seed 8조건 전후 비교

각 셀 200회, 조건당 29셀=5,800회. 전후 각각 46,400회(총92,800회). 차선3종은 BEFORE/NONE/LATE이며 짧은 깜빡이 TAP은 평균에서 제외하고 원본29셀 표에 별도 기록한다. 아래 차선 값은 **3종 평균**, 곡선은60/80/100/120 중 최대, 직진은60/100 중 최대다. 숫자는 모두 백분율이고 `전→후`다.

| seed | 잡음 | 모양 | 차선40 | 차선60 | 차선80 | 차선100 | 차선120 | 곡선 화면 오탐(최대) | 직진 화면 오탐(최대) |
|---|---|---|---|---|---|---|---|---|---|
| 20261025 | real | 대칭 | 98.7→99.3 | 91.2→91.5 | 72.7→69.3 | 50.5→43.2 | 25.8→16.0 | 0.5→0.5 | 0.5→0.5 |
| 20261025 | real | 비대칭 | 73.2→96.5 | 61.8→82.2 | 49.2→61.7 | 30.8→28.2 | 17.3→8.5 | 0.5→0.5 | 0.5→1.0 |
| 20261025 | default | 대칭 | 96.8→97.3 | 79.7→82.5 | 42.7→41.3 | 26.7→17.7 | 11.7→3.3 | 2.0→0.5 | 12.5→1.5 |
| 20261025 | default | 비대칭 | 71.2→93.0 | 53.0→73.3 | 29.5→34.2 | 20.0→12.2 | 10.5→1.7 | 5.0→0.5 | 8.0→0.0 |
| 20261026 | real | 대칭 | 98.8→99.2 | 90.0→91.3 | 72.3→69.3 | 46.5→41.7 | 24.3→15.7 | 0.0→0.5 | 1.0→0.0 |
| 20261026 | real | 비대칭 | 75.3→96.5 | 60.7→78.7 | 48.3→61.0 | 28.8→24.2 | 16.5→8.3 | 0.5→0.5 | 0.0→0.0 |
| 20261026 | default | 대칭 | 97.3→97.7 | 77.8→80.0 | 44.3→42.3 | 22.8→14.0 | 13.0→3.7 | 4.5→1.0 | 8.5→1.0 |
| 20261026 | default | 비대칭 | 70.8→93.8 | 53.2→68.5 | 29.0→32.8 | 15.3→9.3 | 10.5→3.2 | 5.0→0.5 | 7.0→0.5 |

평균만으로 목표 충족을 판단하지 않도록 **실측 잡음의 3종 중 최저 정답률**도 기록한다.

| seed | 모양 | 40(목표 대칭98/비대칭90) | 60(대칭88/비대칭85) | 80(대칭70) |
|---|---|---|---|---|
| 20261025 | 대칭 | 99.0 | 90.0 | 67.5 |
| 20261025 | 비대칭 | 96.0 | 79.0 | 60.0 |
| 20261026 | 대칭 | 99.0 | 90.0 | 67.0 |
| 20261026 | 비대칭 | 96.0 | 78.0 | 55.5 |

- **미달:** 실측 잡음 비대칭60km/h(평균78.7~82.2% <85%), 대칭80km/h(평균69.3% <70%). 기본 잡음60/80도 목표 수준이 아니다.
- **오탐 상한은 충족:** 모든8조건에서 곡선 최대1%, 직진 최대1.5%. 다만 엄격한 **전후 비증가 조건은 미충족**: seed25 real 비대칭 직진100이0.5→1.0%(200회 중1→2건), seed26 real 대칭 곡선80이0→0.5%(0→1건). 표본 수가 작아도 이 증가를 없었다고 해석하지 않는다.
- 편한 감속 급제동은8조건 모두0%, 급제동 탐지는 모두100%. 2.8~3.8m/s² 감속은 참고 셀로 원본 표에 그대로 보존(기준3m/s² 변경 없음).
- 개발은 seed20261024와 제공된 회귀3건만 사용. `.local/w15/freeze.json`의28개 해시를 고정한 뒤 seed25/26을 평가했다. 평가 후 규칙 수정/튜닝 없음. 소스/시뮬레이터/CSV/전후 JAR 해시가 모두 유지된다.
- [전후 비교 JSON](evidence/sim/week15/comparison.json), [동결 해시](evidence/sim/week15/freeze.json). 조건별 전체29셀 표·metrics·진단·실행 로그는 같은 폴더의 `w15-before/after-<seed>-stress0-<noise>-asym<0|1>.md*`에 보존한다. 이 폴더는 기존 gitignore의 로컬 증거 제외 범위다.


## 상시 CSV 회귀3건

`RealDataReplayTest`는 환경변수 없이 리소스3건을 항상 읽는다. 시작에 Gear DRIVE를 넣고 CSV의 순서/시각 그대로 재생하며 Tick 보간 없이 마지막+50ms Finish를 넣는다. 기존 선택적 `RECAP_REAL_CSV` 측정 기능도 유지한다. 오프라인 실행기에도 테스트 리소스 classpath를 추가했다.

| fixture | 수정 전1.5.1 | 수정 후1.5.2 | 화면 |
|---|---|---|---|
| `avd_g_run1.csv` | 기대6종, UNSURE0 | 기대6종, UNSURE0 | 4개 |
| `avd_g_run2.csv` | 첫 차선만UNSURE(STEERING), 나머지5종 정상 | 기대6종, UNSURE0 | 3→4개 |
| `comma2k19_example1.csv` | 저장1·화면0 | 저장1·화면0 | 숨김 STEERING1 유지 |

CSV에는 내비 입력이 없으므로 위 TURN2개는 원시 엔진 회귀에서 숨겨진다. **모의 내비를 함께 공급하는 G의 화면6개 시험**은 별도 기존 JVM 시험으로 유지했다. CSV2건의 화면이6개라는 뜻이 아니다. run2 첫 구간은 최종 방향 약-4.2°/최대5.5°·변위-2.93m·3.0초이며 guarded 정착 근거로 판정한다.

원본 SHA-256(바이트 변경 없음):

```text
avd_g_run1.csv         92A0F5F1AC22844116CA01EF263637791ACA70C6C3CA641017BC9CE69EA00120
avd_g_run2.csv         2343E80B9E88FE19F1D6A7069DFFDF8F4A20B6B4E8F17D4FB13641C2779A1A36
comma2k19_example1.csv 38712D8F3AB8BCB33064899B8009D30CC664C76317B0F549B649B21BFD08CBB3
```

이번에 사용한 실제 데이터는 사용자가 이미 제공한 이3개뿐이다. comma fixture의 MIT 표기는 사용자 제공 근거이며 이번 작업에서 원 배포/라벨을 별도 검증하지 않았다. 이3건은 회귀 및 개발 입력이므로 독립 평가로 부르지 않는다. 다른 실주행 CAN은 사용하지 않았다.

## 시험·바뀐 기대값

- JUnit **317항목, 실패0**: 실제 실행315·환경변수로 켜는 측정용2개 기본 skip. 수정 전299항목에 상시CSV3개+정착 경계15개 추가. 실제 물리 G+내비 융합은 화면6개 기대 유지.
- 신규: 실제 작은 보정·500ms/2초 경계·희소 콜백 마감·동일 시각 중복·새 큰 꺾음 후 새 구간·한쪽 곡선 거절·정착 뒤 깜빡이 비면책·입력 실패·공백·종료/P·약한 신호 중단 숨김·전역/약 신호 기준 불변·v5 왕복/v4 읽기.
- **기존 기대 변경1건:** `RecapScenarioTest.scenarioCIsNotGuaranteedToGenerateJevRequestsAfterNormalization`을 `scenarioCHasAmbiguousBodiesEligibleForJevAfterSettling`으로 변경. `[CURVE,UNSURE]`→`[UNSURE,UNSURE]`: 주 조향 종료 시각을 정착 대기와 분리해 첫 구간이8초 곡선 조건에 이르지 않는다. 두 구간 모두 관측된 깜빡이 근거를 보존해 순수 함수상 Jev 입력 적격이 된다(종전0개→2개; 숨김 카드이므로 실제 요청 발생을 뜻하지 않음). 화면은 계속0개. 다른 기존 기대·시나리오 값은 변경하지 않았다.
- Node **76/76**, Debug/release **전체 Kotlin 소스 직접 타입 검사 통과**. 설치된 SDK/AAR·BuildConfig/R stub을 쓴 검사이며 Gradle/APK 빌드나 AVD 증거가 아니다.
- 동결28파일 해시 일치, CSV3개 바이트 유지, `git diff --check` 확인. 소스 제출 패턴 검사는 텍스트138개에서 새 소스 문제0이나 **기존 Git 메시지 이메일32건 때문에 전체 실패**, 제외 대상 추적687개가 남아 있다. 이력/원본을 수정하지 않았다.

로컬 실행 로그: `.local/w15/final-jvm.txt`, `node-tests.txt`, `debug-types.txt`, `release-types.txt`, `after-*.md/.log`, `submission-source.txt`. 합성 실행기의 `MEASURED`/JUnit 성공은 **측정 코드가 끝났다는 뜻**이며 성능 목표 달성 판정이 아니다.

## 바뀐 파일

- `logic/src/main/kotlin/lab/pleos/observe/logic/recap/`: `LateralMotion.kt`(정착 모델/상수), `RecapEngine.kt`(분할·타이밍 경계/불완전 약 신호), `RecapCodec.kt`(v5와 하위 호환).
- `logic/src/test/kotlin/lab/pleos/observe/logic/recap/`: `SettlingWindowTest.kt`(신규15개), `RealDataReplayTest.kt`(상시3개), `RecapScenarioTest.kt`(C 기대), `RealisticDrivingSimTest.kt`(보고 헤더에 noise/asym 표시, 생성 파형/난수 순서 그대로).
- `scripts/run-recap-sim-offline.ps1`(Noise/Asym/8조건/동결JAR/이름 격리/환경 복원), `test-logic-offline.ps1`(리소스 경로), `check-submission.ps1`·`test-submission.mjs`(20/1.5.2 정합성).
- `app/build.gradle.kts`, `README.md`, `docs/WORK-CHECKLIST.md`, 이 문서. CSV3개·게임·탐침·화면·서비스/AI·G 시나리오는 수정하지 않았다.
- `docs/evidence/sim/week15/`에는 전후 평가/재생/해시 로컬 증거를 보존(기존 gitignore 제외 범위). 커밋/push·APK 생성 없음.

## 재현·Claude 실행 순서

네트워크 없는 순수 로직/8조건 측정:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-logic-offline.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/run-recap-sim-offline.ps1 -Week15Matrix -Count 200
# 개별 개발 조건: -Seed 20261024 -Noise real -Asym
# 같은 바이트의 엔진을 재측정: -FrozenJar .local/w15/after.jar -Week15Matrix -Label repeat
```

결과는 `logic/build/reports/sim/<label>-<seed>-stress0-<noise>-asym<0|1>.md` 및 metrics/diagnostics/log. Gradle의 측정용 시험은 환경변수 NOISE/ASYM을 설정하고 `--rerun-tasks`로 캐시를 피한다. 상시3건에는 환경변수가 필요 없다.

**Claude가 Gradle 빌드·설치·AVD 검증한다.**

```powershell
$env:GRADLE_USER_HOME="$PWD\.gradle-user-home"
.\gradlew.bat :logic:test :app:assembleDebug --no-daemon --console=plain
$adb=Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
& $adb -s emulator-5554 forward tcp:8088 tcp:8088
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/demo-navi-feed.ps1 -ScenarioFile scripts/recap-scenario-g-soak.json -Serial emulator-5554 -StartImmediately
```

1. P 상태에서20/1.5.2와 초기 GET·권한 확인. AI OFF로 규칙만 확인한다. 숨은 진단의 모의 내비ON, 기록 시작. 기존 기록을 삭제할 필요 없이 시작 전 건수를 적는다.
2. Console 브라우저 physicsTick이 덮어쓰지 않게 페이지를 닫거나 기존 리플레이 차단 기능을 쓴다. 위 PowerShell 공급기로 원본 G1바퀴 실행(차량 제어+모의 내비 동일 시각표).
3. D 잠금·주행 중 서비스 수신을 확인하고, P 복귀 후 정착 최대2초/내비 융합 최대3초+UI 갱신을 기다린다. 화면 증가분6: **LANE_SIGNALLED / LANE_NO_SIGNAL / LANE_LATE_SIGNAL / TURN_SIGNALLED(우회전·모의) / TURN_NO_SIGNAL(유턴·모의) / HARD_BRAKE**, UNSURE0 기대. 동일 원본으로2회 확인한다.
4. 첫 차선 사전 깜빡이가 늦음으로 바뀌지 않는지, 정착 뒤 켠 깜빡이가 앞선 놓침을 지우지 않는지, 연속 새 조향이 다음 구간으로 나뉘는지 본다. 기록 종료/재열기·프로세스 재시작 뒤v5 요약/피드백과 기존 파일 읽기도 확인한다.
5. **실차 성능 합격으로 판단하지 않는다.** 비대칭60·대칭80 목표 및 일부 오탐 비증가 미달을 함께 인계한다. 이번 개발에 쓰지 않은 다른 독립 데이터/AVD 변형으로 후속 평가하며, 제공된3개를 독립 검증이라고 재사용하지 않는다.

## Claude AVD 확인 (2026-10-24, 1.5.2 Debug, -cores 8 -no-audio)
- 기존 시나리오 G(차선 변경 핸들 ±8~15°, 3초) 2바퀴: 첫 차선 변경은 두 바퀴 모두 정상, 1바퀴에서 '늦게 켬'이 UNSURE. 같은 신호를 JVM 재생하면 LANE_LATE_SIGNAL — 옆 이동 1.79m가 하한 1.75m에 붙은 경계 사례(기기·재생 간 미세 차이로 뒤집힘).
- 원인은 시나리오: 3초·핸들 10~15°는 자전거 모델 옆 이동 2m 남짓(실제 차선 한 칸 ≈3.5m). 판정 하한을 낮추지 않고 **시나리오 G를 실제 크기로 수정**: 시속 60km 핸들 최대 7°·4.9초·옆 이동 3.1~3.7m (`scripts/make-recap-scenario-g.py` → `scripts/recap-scenario-g.json`). 첫 차선 변경 깜빡이는 다음 차선 변경 3.5초 전(11.6s)에 끔(3초 lookback보다 길게).
- 수정 시나리오 **4바퀴(2+2) 모두 바퀴당 정확히 6종**(LANE_SIGNALLED·LANE_NO_SIGNAL·LANE_LATE_SIGNAL·TURN_SIGNALLED·TURN_NO_SIGNAL·HARD_BRAKE), UNSURE 0. 실신호는 `logic/src/test/resources/replay/avd_g_realistic_2laps.csv`에 추가 보관.
- 남은 한계: 시속 100~120km 차선 변경 40~56%/22~28%(실측 잡음 시뮬), 실제 차선 변경 실데이터 검증 없음. 조향비·축간거리는 차량별 미확인 상수(15, 2.7m).

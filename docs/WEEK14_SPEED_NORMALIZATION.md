# 14주차 — 속도 정규화 판정 / 1.5.0(18)

2026-10-24. **규칙 구현·회귀·측정은 끝났지만 성능 목표는 미달이다.** 40km/h 차선 변경, 곡선의 보이는 카드, 편한 감속의 급제동 오탐, 급제동 탐지는 기준·평가 seed에서 목표를 만족했다. **60~120km/h 차선 변경 정답률과 100km/h 직진 오탐은 만족하지 못했다. 직진 오탐은 기존보다 악화됐다.** 이를 판정 개선 완료/실차 정확도 확보로 해석하면 안 된다.

근거는 [기존 합성 실험](WEEK13B_REALISTIC_SIM.md)과 현재 순수 함수 실행이다. **comma2k19 등 독립 CAN 데이터는 읽거나 학습·튜닝·평가에 사용하지 않았다.** APK/AVD·실주행은 Claude의 별도 확인 범위다.

## 구현과 상수

`LateralMotion.kt`가 모델·필터·적분·형상 판정을 담당한다. 작은 조향각에서 `yawRate ≈ v·θ/(조향비·축간거리)`, 따라서 `a ≈ v²·θ/(조향비·축간거리)`로 계산한다. [MathWorks 자전거 운동학 공식](https://www.mathworks.com/help/robotics/ug/mobile-robot-kinematics-equations.html)의 `v·tan(roadWheelAngle)/wheelbase`를 소각으로 근사했다. **조향비15·축간2.7m는 차량별로 확인되지 않은 기본 가정**이다. SDK 원시 각의 degree 가정과 부호 미확정도 실제 차량 검증을 대체하지 않는다. 좌우는 계속 조향 부호로 표시하지 않는다.

| 상수 | 값 | 근거·적용 |
|---|---|---|
| STEERING_RATIO / WHEELBASE_M | 15 / 2.7m | 사용자 지정 근삿값. 차량 보정값 아님 |
| CALIBRATION_MS / INITIAL_TAU_S | 3초 / 1초 | 초기 오프셋 설정. 정규화 진입은 이 준비 후 가능. 저속 회전 경로는 별도 유지 |
| BASELINE_TAU_S | 8초 | 비활성 구간에서만 느린 EMA 갱신, 활성 구간에서는 고정 |
| FILTER_TAU_S | 0.5초 | 손 보정 완화. held input의 1차 필터를 정확 적분, ON_CHANGE 침묵은 값 유지 |
| MIN_SPEED_MPS | 25/3.6m/s | 정규화 경로. 정확히25km/h·큰 각도는 기존 저속 회전을 우선 |
| ENTER_MPS2 / SIGN_MPS2 / NEUTRAL_MPS2 | 0.3 / 0.2 / 0.2m/s² | 진입·유효 반대 로브·중립의 히스테리시스 |
| RAW_ONSET_MPS2 / MAX_ONSET_LOOKBACK_MS | 0.05m/s² / 1초 | 시각 정렬용. 진입 자체는 여전히 필터값0.3 필요. 항상500ms를 빼서 사전 깜빡이를 늦음으로 만드는 방식은 쓰지 않음 |
| MIN/MAX_DURATION_MS | 2,500 / 8,000ms | 사용자 요청. 반전 횟수 하나만으로 차선 변경으로 확정하지 않음 |
| MIN/MAX_DISPLACEMENT_M | 1.75 / 5.5m | ‘약2~5m’의 모델·샘플 경계 허용치. 정확히2~5m인 정책과 다름을 명시 |
| RETURN_RAD / RETURN_FRACTION | 0.06rad(약3.4°) / 0.7 | 잔여 방향 변화의 절대값·최대 방향 변화 대비 제한. 단조 곡선 제외 |
| 약한 원시 신호의 추가 조건 | 잔여≤0.025rad·최대의40% 이하, 최대 변화≥0.035rad, 횡이동≥2.5m | 속도가 커질수록 원시 잡음도 v²로 증폭되므로 더 보수적으로 판단. 아래 한계의 일부이기도 함 |
| 강한 신호/약한 신호 구분 | 양쪽 원시 피크가 각각6° 이상인지 | **진입 문턱이 아님.** 강한 왕복 신호는 직접 이중 적분, 약한 신호는 원래 방향 복귀를 먼저 검사한 뒤 끝점 드리프트 보정 `y−heading·travel/2` 적용 |
| MAX_PATH_RATIO | 1.6 | 전체 요 변화 절대합이 한 왕복의1.6배를 넘으면 반복 흔들림으로 봄 |
| MIN_FIRST_IMPULSE_MPS | 0.7m/s | 약한 앞선 잡음 로브와 본 동작을 무작정 합치지 않고 재시작 |
| NEUTRAL_HOLD_MS | 정규화150ms / 기존 저속700ms | 양쪽 로브가 관측된 뒤 종료. 한쪽만 있으면8초 이상 곡선 조건 별도 |
| MAX_CANDIDATE_MS | 정규화10초 / 기존60초 | 긴 곡선·잡음이 다음 동작을 계속 삼키지 않도록 제한. 경계에서 불완전 요약은 UNSURE로 남을 수 있음 |
| BRAKE_MIN_DT_MS / BRAKE_MAX_DT_MS | 400 / 2,000ms | 100ms 순간 미분 대신 이 구간 평균. 기준 감속은 **3m/s² 유지** |
| BRAKE_ASSOCIATION_MS / BRAKE_COOLDOWN_MS | ±1.5초 / 6초 | 기존 정책 유지. 평균 확인 시각이 아니라 실제 속도 감소 수신 시각으로 브레이크와 대응 |

25km/h 이하·55° 이상·2.5초 이상인 기존 TURN, 내비 회전·유턴 및 좌우 방향 출처 규칙은 유지했다. 기존 `ANGLE_ENTER=6`, `LANE_MIN_MPS=30/3.6`은 **저속/속도 미수신 및 과거 형식 요약의 호환 경로**에 남는다. 속도가 알려진 새25km/h 이상 구간의 진입에는 고정6°를 사용하지 않는다. 내비 융합의 차선 형상 조회는 새 요약이 있으면 새 형상 함수를 사용하고, 저속 회전/유턴 분기는 그대로다.

깜빡이 BEFORE/DURING/NONE·3초 lookback·late ms·방향별 증거는 기존대로다. 필터 지연을 사전 깜빡이 지연으로 오인하지 않도록 raw onset으로 구간 시작을 정렬한다. 강한 왕복 신호가 원시 중립에 돌아온 뒤의 깜빡이는 필터 꼬리에 포함하지 않는다. 기존 세 동작의 시작7000/15000/22000ms, 늦음600ms 및2ms 지터 시험이 유지된다.

## 고정 크기·저장·표시

- 현재 값/필터/초기화 시각과 구간의 적분량·최대/최소 등 **고정 수의 스칼라**만 보유한다. 새 원시 배열/이력은 없다. 기존 감속 증거 목록도 최대32개 요약이다. 명시적 입력 실패·5초 초과 실제 처리 공백은 정규화 구간을 확신으로 만들지 않는다.
- `SegmentSummary.lateral`에 국소 횡이동·방향 변화·필터 극값 등8개 수치만 추가했다. 지리 좌표/위치/원시 시계열이 아니다. 저장 **v4**, v1~v3 읽기·피드백·모의 출처·2일/7일 보관 정책은 유지한다. 기록 삭제/세션 종료 시 필터·감속 시각도 초기화한다.
- `visibleReviewCards`에서 CURVE와 속도25km/h 이상인 저신뢰 STEERING을 숨긴다. 속도 미수신·저속·내비 충돌은 이 이유로 숨기지 않는다. 저장된 목록과 확정 이벤트의 `ReviewCounts`는 삭제/증가시키지 않는다. 요약 문장의 ‘애매함’은 보이는 후보만 세어 화면 후보 수와 맞춘다.
- **G 원본의 속도·각도·시각표는 바꾸지 않았다.** 로컬 `.local/recap-scenario-g-v6.json`을 `scripts/recap-scenario-g-soak.json`에 그대로 복사해 재실행 가능하게 했다. 바이트 해시도 같으며, 실제 reducer+내비 융합을 지나는 새 시험에서6장이 확인됐다. 이는 AVD 실측이 아니다.

## 전후 평가

기준 seed20261024로만 조정했다. **규칙 SHA-256을 동결한 뒤** 평가 seed20261025/20261026·스트레스를 열었다. 전후 모두29셀×200회×4조건=총46,400에피소드다. 스트레스는 조향비12~19, 축간2.5~3.1m, 손 잡음σ0.4~2°, 차선 변경T3~8초, 속도 잡음2배로 미리 고정했고 결과로 추가 튜닝하지 않았다.

표는 차선3종 합계600회의 **정확한 카드 조합** 정답률이다. 추가 오탐 카드가 있어도 오답으로 센다. 각 종류별 결과·원본29셀·분모·실행 전후 해시는 [전체 비교](evidence/sim/week14/COMPARISON.md)와 같은 폴더의 `.metrics.tsv`/`provenance.json`에 보존했다. 별도 seed는 같은 생성 모델의 평가이며 독립 실차 데이터가 아니다.

| 조건 | 40km/h 전→후 | 60 | 80 | 100 | 120 |
|---|---|---|---|---|---|
| 20261024 기준 | 89.3→95.5% | 53.7→73.5% | 19.3→43.0% | 2.7→23.2% | 0.3→14.7% |
| 20261025 평가 | 90.0→96.8% | 51.8→79.3% | 21.3→42.7% | 3.2→26.7% | 0.0→11.7% |
| 20261026 평가 | 92.0→97.3% | 52.2→77.8% | 20.3→44.3% | 2.3→22.8% | 0.2→13.0% |
| 20261024 스트레스 | 52.0→78.3% | 41.7→51.5% | 21.7→27.3% | 9.2→14.7% | 3.8→9.3% |

| 조건 | 곡선의 보이는 카드(합계) 전→후 | 직진60km/h | 직진100km/h | 편한 감속→급제동 | 급제동 탐지 |
|---|---|---|---|---|---|
| 20261024 | 51.9→1.6% | 0.0→0.0% | 1.0→6.5% | 14.5→0.0% | 100→100% |
| 20261025 | 49.8→1.1% | 1.0→0.0% | 2.0→12.5% | 15.2→0.0% | 100→100% |
| 20261026 | 55.0→1.9% | 1.0→0.0% | 1.0→8.5% | 16.8→0.0% | 100→100% |
| 스트레스 | 64.2→3.0% | 9.0→0.0% | 8.0→8.5% | 85.0→0.0% | 100→100% |

급제동 탐지는 HARD_BRAKE가 하나라도 있는 비율이고 ‘그 카드만 나온 정확한 조합’ 비율과 다르다. 곡선 표는 요청한 **숨김 후 보이는 카드** 비율이므로 곡선 종류 분류의 정확도 개선으로 읽으면 안 된다. 원본 `stored` 열은 숨기기 전 요약 개수를 담는다. 기존 시뮬레이터처럼 약13초 준비·8초 정리에서 나온 카드도 오답에 포함한다(직진 본구간60초). 채점 구간을 바꿔 유리하게 만들지 않았다.

정책 경계 **감속2.8~3.8m/s²**가 급제동으로 판정된 비율(50/80km/h): 기준 `100/100→89/92%`, 평가1 `100/100→92/91%`, 평가2 `100/100→94.5/93%`, 스트레스 `100/100→97.5/99%`. 입력에3 미만도 포함돼 있으므로 모두 급제동이어야 하는 집합이 아니다. 400ms 평균에도 경계 잡음·최대 구간 선택 효과는 남는다.

목표별로는 일반3 seed 모두40km/h 각 종류94.5~98.5%라 통과한다. 곡선은 모든 속도별 셀10% 이하, 편한 감속의 급제동 오탐0%, 급제동 탐지100%다. **60~120km/h ≥85%와 직진 ≤2%는 실패다.** 핸들 잡음도 v²로 증폭되고 적분 드리프트·잡음으로 잘못 열린 구간이 남는다. 이 구현의 약한 신호 조건은 진짜 고속 차선 변경도 많이 제외한다. 추가 seed 결과에 맞춰 수치를 재조정하지 않았으며, 현재 수치로 고속 판정의 신뢰성을 주장할 수 없다.

## 시험과 바뀐 기존 시험

일반 JVM **297개 통과 + 선택 시뮬레이션1개 기본 건너뜀**(JUnit 표기298). 별도 시뮬레이션8회(전후4조건)는 실제 실행했다. 신규 `NormalizedMotionTest`15개에는 깨끗한 물리 파형40~120km/h×깜빡이3종, 작은 각도에서 내비 보조/충돌, 적분의 패킷 주기 독립성, 기준선 고정, 형상/시간 경계, v3/v4 저장, 시계 공백, 400ms 감속·짧은 잡음·G6장 등이 있다. **깨끗한 파형 통과가 잡음 포함 목표 달성을 뜻하지 않는다.** Node76/76, Debug·release 전체 소스 직접 Kotlin 타입 검사도 통과했다. Gradle/APK/AVD는 실행하지 않았다.

기존 시험은 삭제하지 않았다. 기대값/파형/확인 시각을 바꾼 항목은 아래와 같다. 그 외 게임·잠금·내비 회전·AI 계약·저장/피드백 회귀는 유지됐다.

| 파일/기존 시험 | 변경 이유·현재 검증 |
|---|---|
| RecapEngineTest Feed 공통 | 스피드·각도 사이에50ms held-value/tick을 공급, 명시 속도를 유지하며 같은 ID는 실제 앱처럼 upsert. 기존2초 lane fixture는 새 최소2.5초를 충족하는4초 왕복으로 교체 |
| neutralHoldMergesTwoLobesAndProducesOneCard | 고정2,000ms 대신 새2,500~8,000ms 범위, 카드1개/중립 종료 유지 |
| signalAfterSteeringIsLate / outOfOrderBlinkerInvalidatesAbsenceEvidence | 파형을3.5~4초 균형 왕복으로 보완, 늦음200ms·늦은 도착의 INPUT_GAP/SIGNAL_GAP 의미 유지 |
| tooShortObservationWindowCannotProveMissingSignal | 초기 각도만 먼저 제공해 필터를 초기화. 깜빡이 관측 기간 부족은 계속 UNSURE |
| neutralAndParkCanFinalizeCompleteSegmentBeforeHoldTimer | 1.5초 구간을 중립+P만으로 확정하지 않음: LANE_NO_SIGNAL→UNSURE |
| staleAndNonfiniteInputsCannotBecomeConfidentEvents / finishedStreamCannotLeavePendingSegment | 필터의 진입 확인 tick 후 실패/Finish 주입, INPUT_GAP·종료 불확실/정리 의미 유지 |
| hardBrakeNeedsSpeedDropAndPressedPedal | 두 점의 순간 점프 대신10m/s² 선형 감속 공급. 400ms 평균·브레이크 조건 검증 |
| RecapMeasuredTimelineTest offGetWithoutAnyNotifyIsEnoughToProveAbsence / missingInitialGetAndNoNotifyStillCannotInventOff | 필터 꼬리 종료를 기다리는 tick8,200→9,000ms. GET만으로 알려진 OFF/미수신 구별 유지 |
| twoMillisecondCrossSignalJitterKeepsOriginalLateSignalTime / preSteeringSignalDeliveredTwoMillisecondsAfterAngleIsStillBefore / signalAfterNeutralEndDoesNotLeakIntoThePreviousManeuver | ≥2.5초 왕복 파형으로 보완. 늦음200ms/BEFORE/원시 중립 이후 신호 제외 유지 |
| threeSecondHeldAngleDoesNotBecomeAnInputGap | 한쪽3초+반대0.85초는 큰 잔여 방향 변화로 LANE_NO_SIGNAL→UNSURE. 침묵을 INPUT_GAP로 보지 않는 조건 유지 |
| heldCurveUsesLastValueAndExplicitFailureRemainsUncertain | 필터 종료까지 대기, bounded 요약의 candidate=CURVE와 숨김 확인. 명시 실패는 UNSURE |
| strongerDecelerationUpdatesOneCardAndKeepsFeedback | 400ms 평균 확인까지 기다린 후 최대값 증가·같은 ID·피드백 유지 확인 |
| twentyMillisecondSpeedUpdatesStillMeasureDecelerationOverHundredMilliseconds | 이름의 Hundred→FourHundred, 20ms 수신에서도 400ms 평균10m/s²와41ms 브레이크 펄스 유지 |
| DrivingStateTest.stuckCurrentGearWithUxAndSpeedFeedsRealLaneEventThenParks | 초기 각도·50ms tick과2.8초 왕복을 공급, CURRENT_GEAR=P가 고정돼도 UX/속도로 DRIVE/PARK 결정 유지 |
| DemoNavigationTest.persistedCardContainsOnlyProvenanceNotInputSnapshotAndReadsVersionTwo | 단일 카드 v2 fixture 작성 시 v3 출처+v4 geometry 존재 플래그2바이트 제외. 과거 파일 읽기 검증 유지 |
| deterministicFourScenesPassThroughTheActualReducerAndFusion | 35km/h·옛±8~15°의 작은 파형은 새 모델에서 약2m 미만이라 마지막2개 LANE_NO_SIGNAL/ASSISTED→UNSURE/ESTIMATED. 앞의 우회전/유턴과 모의 출처는 동일 |
| AiReviewTest.summaryUsesHoursRelativeDaysAndRuleKindsNotAiLabels / overLimitDayFallsBackInsteadOfSilentlyDroppingOldCards | 숨기는 고속 STEERING 대신 보이는 LANE_CHANGE 가설로 입력 fixture 변경, 시간대·상대일·501개 제한 계약 유지 |
| RecapPresentationTest.uncertainHypothesisIsSeparateAndWholeDayCountsMayExceedOnePage | 보이는 가설의 ‘애매함1’·전체101개 유지, 숨긴 STEERING은 요약 애매함 수에 포함하지 않는 검증 추가 |
| RecapScenarioTest.scenarioBSeparatesTurnAndCurveFromLaneChange | TURN+CURVE 한 덩어리→TURN+불완전 CURVE/STEERING 요약. 후자 모두 숨김, 실제 저장 요약은 남음 |
| scenarioCLeavesTwoUncertainSteeringSummariesForJevWithoutChangingRules → scenarioCIsNotGuaranteedToGenerateJevRequestsAfterNormalization | 기존25~35 경계 파형은 CURVE+UNSURE(STEERING)로 바뀌고 보이는 카드/분류 요청을 보장하지 않음. C 입력 시각·제어값은 유지, 설명만 갱신 |
| RealisticDrivingSimTest.realisticDrivingReport | seed/스트레스/분모·추가 카드·급제동 포함 지표 출력. 목표 달성을 assert하지 않는 기존 측정 시험 성격 유지 |

A·D의 명확한 차선3종/급제동/숨김 회전 기대는 그대로 통과했다. `measuredThreeManeuversStaySeparateAndHaveNoUncertainty`의 세 시작 시각·늦음600ms도 그대로 통과했다. F의 예전35km/h 작은 파형과 C의 AI 유도 문구는 W14에서 같은 결과를 약속할 수 없으므로 G 또는 단위 fixture와 구분한다.

## 바뀐 파일

- `logic/.../LateralMotion.kt`(신규), `RecapEngine.kt`, `RecapModels.kt`: 모델·필터·적분·구간·400ms 평균/브레이크 시각 대응.
- `DrivingState.kt`, `ReviewCounts.kt`, `NavigationFusion.kt`, `RecapCodec.kt`: 표시 필터/요약·새 형상 전달·v4 저장과 과거 읽기.
- `app/.../RecapRecordingSession.kt`: 기록 삭제 때 추가 상태 초기화1행. 서비스 구독·UI·권한은 변경 없음.
- `NormalizedMotionTest.kt`(신규)와 위 표의 기존 시험8개 파일(RealisticDrivingSimTest 포함).
- `scripts/recap-scenario-g-soak.json`(원본 그대로 신규), `run-recap-sim-offline.ps1`(신규), `recap-scenario-c.json`(설명만).
- `app/build.gradle.kts`, `scripts/check-submission.ps1`, `scripts/test-submission.mjs`, `README.md`, `docs/WORK-CHECKLIST.md`, 이 문서.
- `docs/evidence/sim/week14/`: 합성 전후 결과·비교·분모/해시. 기존 gitignore의 로컬 증거 제외 대상이며 이력/원본은 삭제하지 않음.

## 재실행·Claude 확인

설치된 JBR/Kotlin/JUnit만 사용하는 오프라인 재현:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-logic-offline.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/run-recap-sim-offline.ps1 -All -Count 200
# 개별 조건: -Seed 20261025 또는 -Seed 20261024 -Stress
```

결과는 `logic/build/reports/sim/offline-<seed>-stress<0|1>.md`와 `.metrics.tsv`다. 공개 실행기로 기준 seed를 다시 실행해 동결 평가와 metrics 파일이 같은 것도 확인했다. Gradle에서는 테스트 캐시를 피하려고 `--rerun-tasks`를 사용한다.

```powershell
$env:GRADLE_USER_HOME="$PWD\.gradle-user-home"
.\gradlew.bat :logic:test :app:assembleDebug --no-daemon --console=plain
$env:RECAP_SIM='1'; $env:RECAP_SIM_N='200'; $env:RECAP_SIM_SEED='20261024'; $env:RECAP_SIM_STRESS='0'
.\gradlew.bat :logic:test --tests lab.pleos.observe.logic.recap.RealisticDrivingSimTest --rerun-tasks --no-daemon --console=plain
Remove-Item Env:RECAP_SIM,Env:RECAP_SIM_N,Env:RECAP_SIM_SEED,Env:RECAP_SIM_STRESS
```

Claude: 18/1.5.0 설치→P·권한/초기 GET 확인→모의 내비ON·기록 시작→Console 브라우저의 physicsTick 쓰기 차단/페이지 닫기→기존 G를 한 바퀴 실행한다. 기존 기록은 유지하고 증가분을 비교한다.

```powershell
$adb=Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
& $adb -s emulator-5554 forward tcp:8088 tcp:8088
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/demo-navi-feed.ps1 -ScenarioFile scripts/recap-scenario-g-soak.json -Serial emulator-5554 -StartImmediately
```

P 복귀 후 내비 융합 지연(최대3초)+화면 폴링을 기다려 증가6개: **LANE_SIGNALLED / LANE_NO_SIGNAL / LANE_LATE_SIGNAL / 우회전 TURN_SIGNALLED(모의) / 유턴 TURN_NO_SIGNAL(모의) / HARD_BRAKE**. 모의 표시·주행 잠금·2ms 타이밍·400ms 감속 근거/피드백·저장 재읽기를 확인한다. A/D는 기존 기대 유지, C/F는 위 변경 목록 참고. CPU/RAM과 실제 SDK 콜백 주기의 영향은 이번에 재실측하지 않았다.

그 뒤 Claude가 **독립 실주행 CAN**으로 별도 평가한다. 현재 합성 평가의 고속 목표 미달·직진 오탐 악화를 함께 넘겨야 하며, 같은 생성 모델의 평가 seed를 독립 실데이터 검증으로 대신하지 않는다. 커밋/push·APK 생성은 이 작업에서 하지 않았다.

## Claude 독립 확인 (2026-10-24)
- **실주행 잡음 측정**(comma2k19 Example_1, 62~66km/h): 핸들 손 보정 잔차 σ 0.46~0.53°, 자기상관 0.7~0.8s, 기준선 0.5° 이내. 기본 시뮬 잡음(σ 0.4~1.5°)은 실측의 약 2배 → `RECAP_SIM_NOISE=real`(σ 0.3~0.8°, 기준선 ±0.5°) 추가. 판정 규칙은 그대로, 잡음 모델만 실측에 맞춤.
- **실측 잡음 시뮬(seed 20261025·20261026, 칸당 200)** 1.4.1 → 1.5.0:
  - 차선 변경 3종: 40km/h 87~98% → 98~99.5%, 60 48~57% → 88~93%, 80 16~26% → 70~76%, 100 1.5~4.5% → 40~56%, 120 0% → 22~28.5%
  - 고속도로 곡선 보이는 카드: 29~42% → 0~0.5%, 직진 60초: 0% → 0~1%, 편한 감속 급제동: 10~20% → 0%, 급제동 100% 유지
  - 고속 미탐은 전부 '카드 없음'(틀린 판정이 아니라 침묵).
- **실주행 1분 재생**: 1.4.1 카드 0, 1.5.0 저장 1·화면 0(11.9~13.2s 숨김 STEERING) — 정답(차선 변경·깜빡이·급제동 0, 영상 확인)과 화면 기준 일치.
- 한계: 운전자 1명·1분·62~66km/h만 측정. 실제 차선 변경 탐지는 미검증(고속도로 출퇴근이 담긴 comma2k19 청크 필요).

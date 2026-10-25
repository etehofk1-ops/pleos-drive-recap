# W16c — 끝점 기준선 지연 창 탐지기 Kotlin 이식 (1.6.0)

2026-10-25 · 브랜치 `w16c` (`w16-candidate-failed`에서 분기, push 안 함) · 명세 `SPEC_W16c_Kotlin이식.md` · 참조 구현 `spikes/w16c/lanewin.py`

## 결과 요약

- 필수 회귀 4개 전부 통과. Kotlin 사건 종류·시작 시각이 파이썬 참조와 ±0.5초 안에서 일치한다(아래 표).
- `:logic:test :app:assembleDebug` 전체 통과: **326항목 · 실패 0 · 생략 4**(환경변수가 있어야 도는 측정용 시험 4개). APK 빌드 성공.
- 실데이터 91구간(commaCarSegments) 일괄 재생 → `C:\data\commaCarSegments\engine_v160.jsonl` 생성(채점은 Claude).
- 파이썬 참조는 고치지 않았다. 파이썬과 Kotlin의 차이를 맞추려고 시험 기대값을 바꾼 일은 없다.
- **명세 안의 모순 1건**(표시 정책 3.5m)을 아래 "열린 문제 1"에 적었다. 결정이 필요하다.

## 바꾼 파일

| 파일 | 내용 |
|---|---|
| `logic/.../recap/DelayedLaneWindow.kt` | 창 기하 전부 교체. 중심 이동평균(`clean`)·문맥 잡음 배수·`ROAD_CHANGE_*`·`RAW_EDGE_FRACTION`·`LOBE_*` 삭제. 10Hz 링버퍼·깜빡이 이력·NMS·`windowCard` 재사용 |
| `logic/.../recap/LateralMotion.kt` | `normalizedLaneShape`의 창 분기를 새 기준(2.5~8초·1.8°·복귀 0.4/0.8·옆 이동 2.5/2.0~6m)으로 교체 |
| `logic/.../recap/RecapEngine.kt` | `delayedLaneStep`에 내비 힌트(`s.hint`) 전달 |
| `logic/.../recap/RecapCodec.kt` | v6 창 길이 허용 범위 3~8초 → 2.5~8초 |
| `logic/src/test/.../LaneWave.kt` (신규) | 시험용 물리 차선 변경 조향각 파형 |
| `spikes/w16c/lanewin.py` | 작업 트리에 있던 미커밋 변경(`STRONG_LAT_MIN` 2.0m)을 그대로 커밋(참조 구현 정본이므로) |
| 시험 파일 14개 | "바꾼 시험" 절 참조 |

기하 상수(`LaneWindowPolicy`)는 명세 값 그대로다: RATIO 20.0, 창 2.5~8.0초(0.5초 간격), 시작 0.2초 간격(시작 번호는 홀수 5+2k, 첫 시작 31 = 3초 워밍업), 끝점 0.5초, 곡선 0.012rad/s, 방향 최대 1.8°, 복귀 max(1.5°, 0.4·최대)/강한 조향 0.8·최대, 옆 이동 2.5(강한 조향 2.0)~6m, 봉우리 비 0.3(0.12)·합 0.8, 사인 상관 0.7, 시작 20%. 핸들각은 ON_CHANGE 계단 유지(보간 없음)이고 단위 시험 `steeringAngleIsAStepHeld...`가 확인한다.

### 파이썬과 다르게 한 곳(의도적)

1. 확정 지연: 파이썬은 전체 배열에서 한 번에 NMS를 하지만 Kotlin은 스트리밍이다. 창 끝 `b` 뒤 0.5초 표본이 차면 검사하고, 그 창의 끝이 다음 미검사 시작보다 앞서고 점수가 더 높은 겹침 후보가 아직 열려 있지 않을 때 확정한다(최대 지연 약 9초). P·Finish·입력 공백(5초 초과)·25km/h 미만 때는 남은 시작을 있는 표본의 끝점 평균으로 검사하고(0으로 채우지 않음) 남은 후보를 확정한다.
2. 격자 시작: 처음 유효 입력 시각을 격자 원점으로 삼는다(후보 브랜치는 +500ms였다). 파이썬 `resample()`과 같은 위상이라야 비교가 맞는다. 처음에 +500ms를 쓰면 91구간 중 사건 14건이 어긋났고, 원점을 맞추자 51/52가 일치했다. `unknownMotionRestartsTheWarmup...` 시험은 10_700 → 10_200으로 바꿨다.
3. 속도: 파이썬은 선형 보간, Kotlin은 ON_CHANGE 계단 유지(엔진 기존 방식). 사건 시각 차이 0.1~0.2초, 옆 이동 차이 0.04~0.3m 안쪽이다.
4. 25km/h 미만 속도가 들어오면 Kotlin은 상태를 닫고 3초 워밍업부터 다시 시작한다(엔진 기존 동작). 파이썬은 창 안에서만 속도를 검사한다.
5. 내비 힌트: 후보 브랜치의 창 경로에는 `nearNavigationTurn`이 없어 "내비 근처 회전" 판정이 사라져 있었다(`RecapEngineTest.freshNearTurnHint...`). 80m 안 회전 힌트의 수신 시각 최대 64개를 30초 보관하고 사건 `[시작-3초, 끝]`과 겹치면 `nearNavigationTurn`을 켠다(기존 `nearTurn` 규칙과 같다).

## 필수 회귀: 기대 vs 실제

기대는 `PYTHONUTF8=1 C:\data\tools\capvenv\Scripts\python.exe spikes/w16c/lanewin.py regress` 출력. 실제는 `RealDataReplayTest.replayRealDrive`(엔진 전체 경로). 시각은 사건 시작.

| 자료 | 파이썬 기대 | Kotlin 실제(종류, 시작, 옆 이동) | 결과 |
|---|---|---|---|
| avd_g_run1 | 사전 15.4 · 없음 22.6 · 늦음 29.9 | LANE_SIGNALLED 15.5 · LANE_NO_SIGNAL 22.7 · LANE_LATE_SIGNAL 30.0 (−2.10/+2.08/−2.08m) | 통과(+TURN 2, 급제동 1 유지) |
| avd_g_run2 | 15.4 · 22.5 · 29.7 | 15.4 · 22.5 · 29.7 (−2.63/+2.23/−2.06m, 파이썬과 소수 둘째 자리까지 같음) | 통과(+TURN 2, 급제동 1) |
| avd_g_realistic_2laps | 16.1·23.3·30.7 / 110.7·117.8·125.1 | 16.1·23.5·30.7 / 110.8·117.9·125.2 (사전·없음·늦음 순서 그대로 6장) | 통과(+TURN 4, 급제동 2) |
| comma2k19_example1 | 8.7s 후보(깜빡이 이력 없음 → 숨김), 보이는 0 | UNSURE/LANE_CHANGE 8.7~14.0s, SIGNAL_GAP, 숨김 | 통과(보이는 카드 0) |

`RealDataReplayTest`의 `expectedG`와 2바퀴 시험은 코드 변경 없이 이 표를 검사하고 통과한다(종류 목록 비교). 새 단위 시험이 같은 파형으로 시작 시각(±0.7초 이내)·확정 지연(끝 뒤 0.5~10초)을 검사한다.

### 실데이터 91구간 파이썬 대조

`engine_v160.jsonl`의 차선 카드와 같은 구간의 `lanewin.detect()`를 비교했다(시작 시각 ±0.5초).

- 파이썬 52건 · Kotlin 51건 · 일치 51건 · Kotlin만 0건 · 파이썬만 1건.
- 파이썬만 1건(`HYUNDAI_ELANTRA_2021…00000010--e734bec76f__13`, 43.2~49.5초)은 그 구간 앞쪽에서 속도가 25km/h 아래(최저 11km/h)로 내려가 Kotlin이 상태를 닫고 3초 워밍업을 다시 거친 경우다(위 차이 4). 알고리즘 차이가 아니라 엔진의 속도 게이트 때문이다.
- 격자 원점을 +500ms로 둔 첫 이식본은 일치 38/52였다(파이썬만 14, Kotlin만 15). 원점을 맞춘 뒤 위 결과가 나왔다.

### 일괄 출력 요약(채점은 Claude가)

91구간 · 91분. 카드 종류(kind): LANE_NO_SIGNAL 39 · LANE_SIGNALLED 11 · LANE_LATE_SIGNAL 1 · TURN 7 · HARD_BRAKE 3 · UNSURE 41(TURN 후보 8·CURVE 2·STEERING 20·BRAKE_UNVERIFIED 11). 차선 카드 51장 모두 `visible=true`. LANE_NO_SIGNAL 39장 중 옆 이동 3.5m 이상은 21장이다(열린 문제 1 참조).

## 바꾼·지운 시험과 이유

바꾸지 않은 것: 급제동(`RecapMeasuredTimelineTest`의 brake 8개·`NormalizedMotionTest` 감속 3개·`RecapEngineTest`의 brake 6개), 회전(저속 TURN·`RecapScenarioTest` 회전), 내비 융합(`NavigationFusionTest` 전체·`DemoNavigationTest`의 파서/라벨 시험), 저속 legacy(`Feed(null)`·`strictPolicy`·`demoMode` 시험), 코덱(`RecapCodecTest`, `populatedVersionThreeFileStillReadsExactly`).

공통 이유: 엔진이 25km/h 이상에서는 옛 정착(settling)/normalized 상태 경로를 더 이상 타지 않고(후보 브랜치부터), 새 창은 약 9초 뒤 또는 Finish/P에서 확정된다. 옛 시험은 짧은 계단 조향(350ms 간격·2.45초)과 5~6초 안 확정을 전제로 했다.

| 시험 | 조치 | 이유 |
|---|---|---|
| `RecapEngineTest.neutralHoldMergesTwoLobes…` | 이름 바꿔 재작성(`oneLaneChangeProducesOneCard…`) | 중립 유지 타이머 경로 삭제. 물리 파형 1회 → 확정 전 카드 없음, Finish에서 1장 |
| `RecapEngineTest` 신호 시험 8개(`signalHeldLongBefore`·`signalOffWithinLookback`·`olderSignal…`·`signalAfterSteeringIsLate`·`unknownConvention`·`knownConvention`·`noIndicatorReception`·`tooShortObservationWindow`) | 입력 파형을 `LaneWave`로, 시작을 8.5초로, 끝에 Finish | 새 창은 3초 워밍업 + 2.5초 이상 사건이 필요. 기대 종류는 그대로 |
| `RecapEngineTest.parkTransition…`→`parkTransitionDuringAnUnfinishedManeuverInventsNoCard` | 기대: UNSURE 카드 → 카드 없음 | 끝점 기준선 없는 미완성 창은 추측하지 않고 버린다 |
| `RecapEngineTest.neutralAndPark…`→`…WithinTwoPointFiveSeconds…` | 기대 UNSURE → 카드 없음 | 최소 창 2.5초 미만 |
| `RecapEngineTest.staleAndNonfinite…` | UNSURE/INPUT_GAP 카드 → 카드 없음, 상태 비교는 `delayedLane`만 제외 | 각도 소실이면 창을 재시작(카드 만들지 않음) |
| `RecapEngineTest.finishedStreamCannotLeavePendingSegment` | UNSURE 카드 → 카드 없음 + 창 상태 비움 | 같은 이유 |
| `RecapEngineTest.outOfOrderBlinker…` | "UNSURE/INPUT_GAP" → "확신하는 없음/사전 카드를 만들지 않음" | 늦게 온 깜빡이는 창 상태를 비운다(후보 브랜치 설계). 결과가 카드 없음일 수 있어 확신 카드 부재만 확인 |
| `RecapEngineTest.freshNearTurnHint…` | 힌트 수신 시각을 8_000으로 | 사건이 8.5초부터라 힌트가 사건 3초 전 안에 있어야 한다. 구현은 위 5번 |
| `RecapMeasuredTimelineTest` 7개(`measuredThreeManeuvers…`·`offGet…`·`missingInitialGet…`·`twoMillisecondCrossSignalJitter…`·`preSteeringSignal…`·`signalAfterNeutralEnd…`·`threeSecondHeldAngle…`) | 물리 파형 + Finish. 사건 시작은 정확히 7000이 아니라 `[시작, 시작+700ms]`(20% 임계 지점) | 위와 같음 |
| `RecapMeasuredTimelineTest.heldCurve…`→`heldCurveUsesLastValueAndExplicitFailureStaysOutOfLaneCards` | CURVE 후보 카드 → 카드 없음 | 끝점이 달라지는 곡선 진입/이탈은 창에서 거절. 숨김 카드로도 저장하지 않음 |
| `NormalizedMotionTest.cleanPhysicalLanes…` | 파형 조향비 15 → `LaneWindowPolicy.RATIO`, 끝에 Finish | 검출기 모델(20)과 같은 비로 물리 파형 생성 |
| `NormalizedMotionTest.aTenMinuteStream…` | `lateralFilter.atMs` 단정 → 링버퍼·후보·깜빡이 상한 | 25km/h 이상 모션은 옛 필터가 아니라 `delayedLane`에 있다 |
| `NormalizedMotionTest.aRealClockGap…` | UNSURE/INPUT_GAP 카드 → 확신하는 차선 카드 없음 | 입력 공백으로 끊긴 창은 버린다 |
| `NormalizedMotionTest.scenarioGKeepsSixVisibleCards…` | `recap-scenario-g-soak.json` → `recap-scenario-g.json`, 끝에 Finish | soak는 옛 계단 파형이라 3번째(늦은 깜빡이) 사건이 2.5초 미만. 파이썬도 같은 2건만 찾는다. G는 파이썬이 3건을 찾는다 |
| `NormalizedMotionTest.navigationCanAssist…` | 위 파형 수정으로 통과(코드 그대로) | — |
| `DrivingStateTest.stuckCurrentGearWithUx…` | 파형 교체, 카드 확인을 P 전환(Finish급 flush) 뒤로 | 확정이 P에서 일어난다 |
| `DemoNavigationTest.deterministicFourScenes…` | 카드 4장 → 2장(TURN 둘) | 35km/h 옛 ±8~15° 파형이 만들던 숨김 UNSURE 2장이 사라졌다. TURN/내비 라벨·uncertainty 단정은 그대로 |
| `RecapScenarioTest.actualScenarioA…` | 5장/보이는 4 → 3장/보이는 2(NO_SIGNAL·급제동·TURN) | A 스크립트의 350ms 계단 사건은 2.45초. 첫째는 D 직후 워밍업 안, 셋째는 2.5초 미만. 파이썬도 같은 1건 |
| `RecapScenarioTest.scenarioBSeparatesTurnAndCurve…` | [TURN, CURVE, STEERING] → [TURN] | 고속 곡선 숨김 카드를 더 이상 만들지 않는다. TURN 판정 그대로 |
| `RecapScenarioTest.scenarioC…AfterSettling` | **삭제** | 정착 창 경로 전제(Jev 대상 애매 카드 2장). 경로가 없어졌고 C는 카드 0 |
| `RecapScenarioTest.demoD…`(2개) | 차선 3 → 2(사전·없음), 요약 "차선 변경 2번…", 주기 속도 없는 변형은 [사전, 급제동] | D 스크립트의 늦은 깜빡이 사건은 2.45초 계단 파형. 주기 속도 없으면 3초 뒤 속도 만료로 창이 재시작된다 |
| `SettlingWindowTest` 엔진 구동 12개 | **삭제**(`reciprocalSettledGate…`·`settlementDoesNotForce…` 순수 함수 시험은 유지) | 정착 상태(`settleSince` 등)는 25km/h 이상에서 도달 불가 |
| `SettlingWindowTest.versionFive…` | 카드를 엔진 대신 직접 구성 + v5 읽기 단정 추가, v4는 `dropLast(12)` | 코덱 단정은 그대로. v6이 4바이트(창 길이)를 붙여 옛 `dropLast(8)`이 맞지 않았다 |
| `DelayedLaneWindowTest` 강한/약한 증거 값 | 방향 최대 1.5° → 2.0° (약한 쪽은 1.3° 유지) | 새 하한 1.8° |
| `SummaryCacheTest` 8개 | 카드 후보 LANE_CHANGE → STEERING, 최저 속도 25 → 24km/h | 표시 정책이 LANE_CHANGE(깜빡이 정보 부족) 후보를 숨기도록 이미 바뀌었고, 25km/h 정각 STEERING도 숨는다. 저속 legacy 가설은 계속 보인다 |
| `AiReviewTest` 2개 | 같은 이유로 24km/h 카드 사용 | 위와 같음 |
| `RecapPresentationTest.uncertainHypothesis…` | 보이는 애매 카드를 `speedMissing` 카드로 | 위와 같음 |

새로 추가한 시험(`DelayedLaneWindowTest`): 조향 영점 4° 오프셋 무관·확정 지연·인접 사건 비병합·곡선 진입/이탈 거절·손 잡음 직진 거절·워밍업·Finish 부분 끝점·30km/h 미만·장시간 상한·계단 유지. `RealisticDrivingSimTest`에 `RECAP_SIM_NOISE=ccs` 모드(속도대별 σ = (<60: 1.25, <100: 0.51, 그 외 0.28)/0.6 × U(0.4, 1.9)) 추가, 기존 `real` 모드는 그대로.

## 표본당 계산량

- 링버퍼 ≤160표본(16초), 깜빡이 전이 ≤256, 후보 ≤512, 내비 힌트 시각 ≤64. 주행 시간에 비례하는 반복은 없다.
- 표본마다: 목록 복사 ≤160. 두 표본에 한 번(시작 0.2초 간격): 길이 12개 × 최대 80표본 × 선형 패스 약 8회 ≈ 8 KFLOP, `sin` 최대 약 1,000회(80표본×12길이, psi·사인 템플릿). 후보가 있을 때만 NMS 1회(정렬 + 최악 512²/2 겹침 비교, 실제로는 수 개).
- 91구간 일괄(약 5.4만 표본) 재생이 Gradle 기동 포함 25초 안에 끝난다. 기기 측정(Pleos)은 하지 않았다.

## 합성 시뮬(RealisticDrivingSimTest, seed 20261025, 칸당 100회, 비대칭, 판정 정확도는 보이는 카드 종류 일치)

`docs/evidence/realdata/w16c/sim_default.md`·`sim_ccs.md`(gitignore 대상이라 작업 폴더에만 있다). 실측 잡음(`ccs`) 기준:

| 정답 | 40 | 60 | 80 | 100 | 120 km/h |
|---|---|---|---|---|---|
| 사전 깜빡이 | 86% | 91% | 84% | 90% | 82% |
| 깜빡이 없음 | 85% | 92% | 82% | 95% | 90% |
| 늦은 깜빡이 | 84% | 85% | 75% | 82% | 78% |
| 곡선 오탐(카드가 생김) | - | 27% | 46% | 16% | 27% |
| 직진 60초 오탐 | - | 47% | - | 42% | - |

파이썬 `final_sim_ccsnoise.txt`의 직진 오탐 47/44%, 곡선 28/49/14/20%와 같은 수준이다. 이 시뮬은 단일 사건 길이 60초 직진이고 실측 잡음 분포가 넓어 오탐 수치가 높게 나온다(실주행 91분에서는 시간당 약 34건의 차선 카드).

## 남은 위험과 열린 문제

1. **표시 정책 3.5m(결정 필요).** 명세는 "`LANE_NO_SIGNAL`은 |lat| ≥ 3.5m일 때만 보이는 카드, 미달이면 UNSURE/WEAK_LANE_EVIDENCE로 숨김"이라고 하지만, 같은 명세의 필수 회귀는 AVD 사건 `22.6s 깜빡이 없음`(옆 이동 2.12m)이 `LANE_NO_SIGNAL` 카드이길 요구하고(`expectedG`가 종류를 검사) 시나리오 G의 보이는 6장(`scenarioGKeepsSixVisibleCards`)도 요구한다. 둘을 동시에 만족할 수 없어 **필수 회귀를 우선했다**: 카드는 `LANE_NO_SIGNAL`로 저장·표시하고 3.5m 숨김은 코드에 넣지 않았다(`LaneWindowPolicy.NO_SIGNAL_VISIBLE_METERS = 3.5` 상수만 남김). 채점할 때는 `engine_v160.jsonl`의 `lat_m`로 3.5m 미만을 숨겼을 때 점수를 따로 낼 수 있다(차선 카드 중 LANE_NO_SIGNAL 39장, 3.5m 이상 21장). 앱에서 정말 숨기려면 `visibleReviewCards`에 한 줄이면 되지만, 그러면 AVD 데모의 "깜빡이 놓침" 카드가 화면에서 사라진다(시나리오 G·AVD 재생 시험 기대를 함께 바꿔야 함).
2. **오탐.** 곡선·직진 합성 오탐률은 위처럼 높고 파이썬 참조와 같다. 실주행에는 카메라 정답이 없어 검증할 수 없다(`WEEK16_REALDATA_DIAGNOSIS.md`). 가속·감속 중 속도 변화가 조향 영점 오프셋과 곱해져 요레이트 기울기가 생기면 창이 열린다(합성 급제동 80km/h에서 급제동 카드와 함께 `LANE_NO_SIGNAL`이 15~23% 붙음). 끝점 직선 기준선은 선형 기울기만 지운다.
3. **속도 만료.** 속도 패킷이 3초 이상 없으면 창이 재시작된다(AAOS 속도가 ON_CHANGE만이라 정차 가까운 구간에서 끊길 수 있음). 실차 속도가 연속 알림인지 Pleos에서 확인되지 않았다.
4. **지연.** 카드는 사건 끝 뒤 약 0.5~9초에 나온다(복기는 정차 뒤에 보므로 허용). 주행 중 곧바로 알리는 용도로는 쓸 수 없다.
5. **시험 변경이 큼.** 후보 브랜치에서 실패하던 60항목을 위 표대로 조정·삭제했다. 동작 보장이 줄어든 곳은 옛 정착 경로(삭제)와 늦은 깜빡이 사건을 짧은 계단 파형으로 만든 D 시연 스크립트(시나리오 G·AVD CSV가 대체)다. 시연 영상용 D 스크립트(`recap-scenario-d-demo.json`)는 새 검출기에서 차선 2건만 보인다.
6. 기기(Pleos AVD) 구동 확인은 하지 않았다. APK는 빌드만 했다.

## 재현 명령

```
PYTHONUTF8=1 C:\data\tools\capvenv\Scripts\python.exe spikes/w16c/lanewin.py regress
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :logic:test :app:assembleDebug --no-daemon --console=plain
$env:RECAP_REAL_DIR='C:\data\commaCarSegments\csv'; $env:RECAP_REAL_OUT='C:\data\commaCarSegments\engine_v160.jsonl'
.\gradlew.bat :logic:test --tests '*RealDataBatchTest*' --no-daemon --console=plain
```

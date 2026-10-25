# 1.8.0 복기 화면 C안 "한 장씩" 구현 보고서 (2026-10-25)

브랜치 `w18-ui-c` (master 3397603 기준, push 안 함). 설계서: `docs/WEEK18_UI_C_SPEC.md`, 시안: `docs/design/ui_v2/C_frame.png`.

## 결과 요약
- 정차 후 복기 화면을 시안 C대로 바꿨다. 순간 하나를 큰 카드로 보여 주고 ‹ 이전 · 맞아요 · 아니에요 · 다음 › 와 위치 점 누르기로 옮긴다.
- 문구·칩·막대·점 위치·집계는 전부 logic 모듈 순수 함수(`RecapCardView.kt`)로 뺐고 단위 테스트를 먼저 쓰고(red) 구현했다.
- 엔진 임계값·판정, 저장 스키마(RecapCodec), AI 계약, 홈 화면, 주행 중 잠금은 건드리지 않았다. 릴리스 빌드·서명 파일은 열지 않았다.
- "위반"이라는 말은 어디에도 없다(모든 종류 × 후보 조합을 도는 테스트로 확인). 기준은 "권장 거리".
- 놓침 집계 수정: 이전에는 "깜빡이 놓침" 타일이 켜 둔 채 주행을 합쳐 셌다. 이제 잘한 순간·놓친 순간·늦게 켬·켜 둔 채·급제동을 따로 센다(0인 줄은 숨김).

## 커밋 (w18-ui-c)
1. `feat: 복기 카드 문구·칩·막대·위치 점·종류별 집계 순수 함수 (C안 한 장씩)` — logic + 테스트
2. `feat: 복기 화면 C안 한 장씩 (진행 칸·큰 카드·막대·위치 점·오늘 한눈에)` — app UI, IPC 키 교체, 안 쓰는 코드 제거
3. `fix: 복기 큰 제목은 낱말 가운데서 줄 바꿈하지 않고, 다시 열 때 스크롤이 밀리지 않게 포커스를 먼저 옮긴다`
4. 이 보고서 + 스크린샷(`docs/design/ui_v2/impl/`) + 설계서 파일

## 파일별 변경
logic
- `logic/.../recap/RecapCardView.kt` (신규): `MomentGroup`(잘한/놓친/늦게/켜 둔 채/급제동/애매) · `momentHeadline`(종류별 사람 말 제목, 불확실하면 "~ 같아요", AI 라벨이 받아들여진 경우만 "교차로 회전 순간이에요") · `momentCaption`("잘한 순간 · 1 / 7 · 08:12") · `momentEvidenceChips`(핸들 원시값 없이 "속도 58 km/h", "핸들 5.1초", "권장 거리 이상"/"권장 30m보다 짧아요"/"고속 기준(속도로만 구분)", `numbersShown`이면 큰 숫자와 겹치는 칩 제외) · `trustBadge`("규칙 판정 · 추정", "내비 확인", "AI 추정" 등 판정 출처 칩 하나) · `momentBar`(켬 거리 막대: 권장 30m/고속 100m 눈금, 켜 둔 채 막대: 초 + 15초 눈금) · `momentBigNumbers`(막대 없는 카드의 큰 숫자 2~3개) · `chronologicalCards` · `timelineDots`/`timelineTicks`(첫~마지막 순간을 0..1로, 속도 곡선 없음) · `recapGlance`/`glanceRows`(종류별 집계) · `recapSubtitle`("10월 25일 일요일 · 주행 34분").
- `logic/.../recap/RecapText.kt`: `roundMeters` internal 공개, 애매한 이유 문구를 `uncertaintyLabel`로 뽑음(`recapEvidence` 출력은 그대로).
- `logic/.../PleosDesign.kt`: `momentColor`(파랑·빨강·주황은 Pleos 토큰, 보라는 시안 값, 급제동은 글자색, 애매함은 회색), `legibleTint`(글자로 쓸 때 4.5:1이 되도록 같은 색을 필요한 만큼만 어둡게). 옛 `RecapTileCounts`/`recapTileCounts` 제거(놓침에 켜 둔 채를 합치던 함수).
- 테스트: `RecapCardViewTest.kt`(신규 24개), `PleosDesignTest.kt`(색 2개 추가, 옛 타일 테스트 대체), `BlinkerLeftOnTest.kt`(켜 둔 채가 놓친 순간에 안 합쳐지는 것으로 기대값 변경).

app
- `RecapScreen.kt` (신규): `MomentBarView`(켬 거리/시간 막대), `MomentTimelineView`(위치 점, 터치로 이동), `ChipFlow`, `RecapPainter`(카드·진행 칸·오늘 한눈에·오늘의 한마디 카드). 카드 야간 모드는 배경과 같은 색이라 옅은 테두리를 준다.
- `MainActivity.kt`: `renderRecap`을 한 장씩 화면으로 교체(`reviewTile` 삭제), 진행 칸을 머리 아래에 추가, 날짜·주행 시간을 머리 둘째 줄로, 넓은 화면은 왼쪽 큰 카드(2.85) + 오른쪽 좁은 열(1, 약 420dp), 좁은 화면은 열이 카드 아래로. 선택 카드는 id로 기억(피드백 저장·재그리기에도 유지, 기기 회전 체크포인트에도 보관). 복기를 새로 열면 첫 장부터. 렌더 뒤 포커스를 먼저 옮기고 스크롤 복원(다시 열 때 스크롤이 밀리던 문제 수정). `ui.recap.firstCard` 디버그 로그는 첫 카드 그린 뒤로 옮겨 유지.
- `RecapProvider.kt`: IPC 키 `tile*` → `glanceGood/Missed/Late/LeftOn/HardBrake`(영속 저장 형식 아님).
- `VehicleUi.kt`: `pleosStates` internal, `VehicleParagraph`에 `wordBreaks`(Android 13+에서 한국어 낱말 중간 줄 바꿈 방지, 그 아래는 기존 동작).
- `DeferredReviewColumn.kt` 삭제: 카드 100장 목록용이라 쓸 곳이 없어짐(관련 logic 순수 함수와 테스트는 그대로).

## 테스트 전/후
- 전(master): `:logic:test` 367개, 건너뜀 4, 실패 0.
- 후: 391개(+24), 건너뜀 4, 실패 0. `:app:assembleDebug` 성공. `node.exe --test scripts/test-responsive.mjs` 3/3 통과.
- red 확인: 테스트만 먼저 쓴 상태에서 `:logic:test` 컴파일 실패(미정의 함수)를 보고 구현했다. `numbersShown` 칩 중복 제거도 같은 순서.
- 시안의 요일 표기("토요일")는 달력과 달라(2026-10-25는 일요일) 실제 달력을 따르는 테스트로 고정.

## 스크린샷 (`docs/design/ui_v2/impl/`)
에뮬레이터 `emulator-5554`, 1.8.0 디버그 APK, 시나리오 H로 만들어 둔 카드 5장(오늘 먼저 만든 것 재사용, `feed_nonavi.ps1`은 다시 돌리지 않음).
- `01_first_card.png` 첫 장(잘한 순간, 켬 거리 막대)
- `02_after_timeline_dot_tap.png` 위치 점(늦게 켬)을 눌러 이동 — 3/5
- `03_left_on_card_via_next.png` 다음 › 로 켜 둔 채 카드(시간 막대 23초 + 15초 눈금)
- `03b_after_prev.png` ‹ 이전으로 되돌아옴
- `04_hard_brake_card_last.png` 마지막 장(급제동, 큰 숫자 3개, › 비활성)
- `05a_feedback_no_saved_card3.png` 아니에요 저장 직후
- `05b_reopened_first_card.png` 처음으로 → 오늘 복기를 다시 열면 첫 장
- `05c_reopened_card3_feedback_kept.png` 다시 연 뒤 3장에서 아니에요가 눌린 채 남아 있음
- `06_narrow_560dp_card1.png`, `06b_narrow_560dp_scrolled.png` 좁은 폼팩터(창 폭 560dp, NARROW): 카드 아래로 오늘 한눈에·AI 한마디가 내려옴
- `07_night_wide.png` 야간 모드
좁은 화면은 `wm size`로 화면을 줄이면 AAOS 레이아웃이 다시 나뉘지 않아 앱 창이 화면 밖에 남는다. 대신 `am task resize`로 앱 창만 560×1210으로 줄여 캡처했다. 캡처 후 `capture-responsive.ps1 -Action Restore`로 화면 크기·야간 모드를 원래대로 돌렸고 앱 창도 원래 폭으로 되돌렸다.

## 시안과 다른 점과 이유
- "18.4 km"는 쓰지 않았다(거리를 저장하지 않음). "지난주 평균 28m" 칩도 없다(지난주 켬 거리를 저장하지 않음, 새 저장 금지). 머리는 "날짜 · 주행 N분".
- 시안은 위치 선 위에 속도 곡선이 있지만 설계서대로 곡선 없이 종류 색 점만 그렸다.
- 요일은 시안의 예시(토요일)가 아니라 실제 달력.
- 종류 색 글자(윗줄 캡션)는 흰 카드 위 4.5:1을 맞추려고 같은 색을 조금 어둡게 썼다(`legibleTint`). 점·막대·집계 점은 원색.
- 막대·칩이 겹치는 숫자는 한 번만 보여 준다(막대가 없는 카드는 큰 숫자 칩, 근거 칩에서 같은 숫자 제외). 판정 출처 칩("규칙 판정 · 추정" 등)은 윤곽선 칩으로 구분해 남겼다.
- 시안에는 없는 것: 카드 아래 "처음으로", 내비 안내 문구, 기록 삭제 버튼, Debug 줄. 기능·고지라 지우지 않았고 카드 아래(스크롤 영역)에 둬서 넓은 화면에서 카드가 화면 끝까지 늘어나지는 않는다.
- "AI 다시 확인" 버튼은 오른쪽 AI 카드 안으로, "오늘 요약 문장"(`recapSummary`)은 집계 줄과 겹쳐 화면에서 뺐다("후보 N개 · 규칙 애매함 M개"만 남김). 제공 데이터(IPC 키)는 그대로.
- 핸들 원시값은 Debug 빌드의 흐린 한 줄(`recapEvidence(debug = true)`)에만 남았다. 이전에는 릴리스 화면에도 "핸들 원시 7.0→-6.0"이 보였다.

## 남은 이슈·확인 필요
- 시나리오 H 카드가 1분 안에 몰려 있어 위치 점이 서로 가깝다(첫 4개가 거의 붙음). 실제 주행은 분 단위로 퍼지지만, 몇 초 간격 순간이 많은 날은 점이 겹칠 수 있다. 눌렀을 때는 가장 가까운 점을 고르고(36dp 안), 진행 칸 막대는 같이 쓸 수 있다. 겹침이 문제면 최소 간격 보정이나 칸 눌러 이동을 후속으로 제안한다.
- 카드가 100장을 넘는 날은 페이지(최근 100개) 안에서 "n / N"을 센다. 안쪽 정렬은 오래된 것 먼저(시안과 같음). 다음 100개 버튼은 카드 아래에 있다. 진행 칸은 24개를 넘으면 간격을 줄이지만 100칸은 매우 가늘다.
- `LineBreakConfig`(낱말 단위 줄 바꿈)는 Android 13+만. 그 아래 기기에서는 제목이 낱말 중간에서 꺾일 수 있다(기존 동작과 같음).
- 에뮬레이터 데이터: 3번째 카드(늦게 켬)에 맞아요 피드백이 저장돼 있고(테스트 중 아니에요로 바꿨다가 맞아요로 되돌림), 카드 5장은 오늘 만든 것 그대로다. 기록 삭제로 비우면 깨끗해진다.
- 작업 트리의 `docs/evidence/**`, `docs/store/**`, `.omc` 변경과 `.claude/`는 이 작업과 무관해 커밋하지 않았다. `motion/`은 손대지 않았다.
- 실제 차량(Pleos Connect)에서는 보지 못했다. 에뮬레이터(2560×1440, 1682dp 폭)에서만 확인.

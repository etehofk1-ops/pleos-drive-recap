# 12주차 Pleos 디자인 언어 · 1.4.0(16)

2026-10-23. 평면 타일·무채색·구분선·반전 선택·시스템 sans-serif를 적용했다. 브랜드 로고/워드마크·전용 AI 이름/색·폰트 파일을 추가하지 않았다. 실제/모의 내비·기록/판정/AI 계약은 유지한다. AVD 시각 시험은 Claude가 수행한다.

## 근거와 값 선택

- 읽기 전용 공식 자료의 `디자인토큰_2026-10-23.md`, `design_tokens.json`, `화면규격_2026-10-23.md`와 로컬 원문 foundations/colors·typography·interaction·iconography, components/basic-button·system-popup·toast·segmented-menu를 읽었다.
- 원본 JSON SHA256: `765DD46EE5E849624A5182C97C80C6575FD8C258E1289F21D6BA641CAB1F9ACA`.
- **Color 컬렉션 key `994235267138968c297762f0578b4dfddb3b8126`, version49421:*를 우선**했다. 요청문 요약/다른 컬렉션과 값이 다르다. dark 파랑은 #0082FF, 활성은 #02C265/#00E074, dark 오류는 #FF4C28, divider secondary/tertiary는10%/20%다. 임의 색 보정으로 원본을 덮어쓰지 않았다.
- 주의·포커스만 브랜드 theme의 `color/informative/error`, `color_hmc/stroke/focused`에서 가져온다. 자율주행 보라·전용 AI 파랑은 팔레트에서 제외했다.
- `.local/pleos_ref/vehicle.png`, `apps.png`를 직접 열어 평면 타일/좌상단 선 아이콘/제목·회색 상태/이동 화살표 구조를 확인했다. 참조 이미지 자체의 브랜드 표시는 앱에 복제하지 않았다.
- connect-s 원본 Figma 수치를 dp/sp 토큰으로 채택했다. 실제 패널·창 dp/DPI와 물리적 글자 높이는 AVD/기기에서 확인한다. 폭에 따라 제목40→32, 타일/버튼 본문30→26, 숫자60→48 등 **원본 위계 안의 작은 크기**로 바꾼다. L/W 기종을 창 폭만으로 추측하지 않으며 임의 배율 곱셈을 하지 않는다.

## 토큰 표 (원본 HEX, 불투명도 별도)

| 이름 | 라이트 | 다크 |
|---|---|---|
| background/primary | #F7F8FA | #1D1E21 |
| surface/basic | #FFFFFF | #1D1E21 |
| background/popup | #FFFFFF | #313236 |
| dim/dark/primary | #131417 5% | #FFFFFF 5% |
| text/dark/primary | #131417 | #FFFFFF |
| text/dark/secondary | #131417 84% | #FFFFFF 84% |
| text/dark/tertiary | #131417 64% | #FFFFFF 64% |
| text/dark/quaternary (원본 보존, 실제 비활성 글자는 아래 예외) | #131417 32% | #FFFFFF 32% |
| text/light/primary | #FFFFFF | #131417 |
| divider/secondary | #131417 10% | #FFFFFF 10% |
| divider/tertiary | #131417 20% | #FFFFFF 20% |
| button/filled/enabled | #44464E | #E0E1E6 |
| button/filled/pressed | #313236 | #C7C9CE |
| button/basic/enabled | #131417 5% | #FFFFFF 5% |
| button/basic/pressed | #131417 10% | #FFFFFF 10% |
| informative/positive | #0064FF | #0082FF |
| informative/active | #02C265 | #00E074 |
| informative/negative | #FE3D16 | #FF4C28 |
| color/informative/error (주의) | #FFA202 | #FFA202 |
| color_hmc/stroke/focused | #6ABFFB | #6ABFFB |
| color_hmc/stroke/focused_pressed (원본 보존) | #0794F8 | #0794F8 |

**대비 예외를 명시적으로 처리한다.** 원본 상태색·32% 비활성 글자를 모든 배경에 그대로 올리면4.5:1을 보장하지 못한다. 원본 ARGB 토큰을 보존한 뒤 8비트 알파로 합성한다. 텍스트는 실제 면 대비를 검사해 부족하면 #131417 또는 #FFFFFF 중 대비가 높은 색을 사용한다. 주의/경고 상태는 원본 색 배지 면+대비 글자, 판정 아이콘은 원본 색+종류 텍스트로 표시한다. 실제 비활성 글자는84% 보조색으로 올린다. 밝은 주의색을 일반 본문으로 쓰거나 원본 강조색을 임의로 어둡게 바꾸지 않는다.

대비 시험은 실제 허용 조합(배경·기본 면·팝업·리스트·기본 버튼 enabled/pressed의 주/보조/설명/비활성/상태 글자, 채움 enabled/pressed·반전 선택·토스트, 상태 배지)을 포함한다. **아이콘·구분선 색을 텍스트 색인 것처럼 검사하지 않는다.** 실제 화면 대비·DPI/mm·터치 반응은 별도 확인이다.

| 요소 | 적용 토큰 |
|---|---|
| 페이지 제목 | 넓음40/52, 중간/좁음32/40sp,700 |
| 타일 제목·Medium 버튼 | 넓음30/38, 중간/좁음26/34sp,700 |
| 본문·근거 | 26/34 또는28/36sp,400 |
| 라벨·출처 | 24/28·20/24sp,400 |
| 집계 숫자 | 넓음60/80, 중간/좁음48/64sp,700 |
| 버튼 | Medium80dp, 좌우32dp, 상하16dp, 반경24dp |
| 팝업 | 폭 최대808dp/창 폭-48dp, 반경48dp, 텍스트 버튼 아래·오른쪽 |
| 타일 | 평면 직사각,1px 구분선,48dp 아이콘, 그림자/색 테두리 없음 |
| AI 묶음 면 | 리스트5% 면, 반경32dp, 색 테두리/그림자 없음 |
| 배지·토스트 | 반경16dp |
| 토스트 | 좌우/아래24dp 이상, 최대792dp, 앱 창 내 임시 overlay, pause 때 제거 |

앱의 `PleosPrimaryButton`은 Figma의 **Type=Secondary(채움)**에 대응한다. 앱의 보조 버튼은 외곽선 스타일이다. Figma 변형 이름을 앱 동작 위계와 혼동하지 않는다.

## 화면별 전후

| 화면 | 이전1.3.0 | 이번1.4.0 |
|---|---|---|
| 메인 | 텍스트/버튼·체크박스 세로 목록 | 기록·오늘 복기·AI 보조 스위치 타일, 모의 입력 사용 중일 때 경고 타일. 기록 시작/중지만 채움, 서버/삭제는 외곽선 |
| 넓은 복기 | 요약 문장+색 테두리 한마디, 둥근 카드 | 왼쪽 전체 일자 숫자2×2·회색 AI 한마디·버튼 / 오른쪽 평면 순간 목록 |
| 중간·좁은 복기 | 한 열 카드 | 같은 집계 타일/한마디/순간을 한 열로 재배치, 모든 근거 보존 |
| 순간 카드 | 큰 문장·일반 피드백 버튼 | 48dp 판정 아이콘·제목·근거 출처 배지·시각/속도/핸들/깜빡이·Box 피드백2칸. 선택 시 반전 |
| 팝업 | 일반 Material 스타일 | 토큰 제목/본문·48 반경·오른쪽 아래 텍스트 버튼·대비 글자 |
| 진단/내비 입력 | 이전 팔레트·임의 글자 크기 | 색·서체/위계만 토큰화, 입력/SDK/진행 로직 유지 |

판정 의미는 바꾸지 않는다. 놓침/급제동은 negative 색, 늦음은 caution, 잘한 순간은 positive 파랑 체크다. 애매함/급제동 미확인은 확정 경고 아이콘으로 꾸미지 않는다. 좌우가 미확정인 차선 변경은 중립 양방향 아이콘을 쓰고, 회전 좌우/유턴은 기록의 내비 종류에만 근거한다. AI 추정/규칙 판정/AI 미사용/모의 출처·측정값 이상 보호 문구는 계속 표시한다.

정보 문장은 논리적으로 한 줄 내용이며, 좁은 창에서는 잘라내지 않고 세 줄 이하 문단으로 줄바꿈한다. 한 줄을 강제해 근거를 숨기지 않는다. 긴 AI 문장도 같은 규칙이다.

## 상태/기능 보존

- <600/600~999/≥1000dp, 메모리 checkpoint·대화상자/초안/토글·새 안전 신호 확인·D 잠금/진행 폐기를 유지했다.
- 격자 내부 타일도 논리 스크롤 key로 추적한다. 2열→1열에서 같은 타일/카드로 돌아가고, 레이아웃 계산 뒤 위치를 복원한다. 잠금/새 렌더로 본문이 바뀌면 이전 복원 예약은 무시한다.
- 기록 서비스·차량/내비 구독·규칙·시나리오·AI 요청/응답/저장 형식은 변경하지 않았다.
- Provider에 화면용 건수 네 개만 추가했다. **페이지에 나온100개가 아닌 오늘 전체 규칙 집계**이며 회전 놓침/늦음/정상도 합산한다. 기존 문장 요약의 차선/회전 구분은 유지한다. 저장·AI 입력을 바꾸지 않는다.
- 터치95% 축소/상태별 면 변화, 밖으로 나가면 CANCEL. 기본 click/long-click/접근성 경로를 유지한다. 준비/기록 시작/IPC/요약 대기에는 즉시 진행 상태를 보여 준다. 이 코드는 실제0.25초 응답 실측 증거가 아니다.

## 변경 파일

- `app/build.gradle.kts`:16/1.4.0.
- `app/src/main/java/lab/pleos/observe/VehicleUi.kt`: Tile/Primary/Secondary/Tabs/Divider/Toast·서체·팝업·누름/취소·중첩 anchor.
- 같은 경로 `MainActivity.kt`: 메인 타일·두 열/한 열 복기·숫자·판정 아이콘·출처·Box 피드백·앱 내 토스트.
- `LegacyLabActivity.kt`, `NaviDemoActivity.kt`: 토큰 색/서체·누름·반경, 기존 상태/입력/연동 유지.
- `RecapProvider.kt`: 전체 일자 화면용 숫자만 IPC에 추가.
- `app/src/main/res/values/styles.xml`, `values-night/styles.xml`: 중립 배경/글자·시스템 sans-serif·파랑.
- `app/src/main/res/drawable/ic_*.xml`14개: record/recap/ai_summary/lane_change/lane_left/lane_right/turn_left/turn_right/uturn/hard_brake/late_signal/good/navigation/arrow_forward. 직접 작성,24 viewport/48dp, stroke1.8=48 기준3.6, 단색·round cap/join.
- `logic/.../VehicleLayout.kt`: 대비 함수·기존 반응형 유지, 팔레트는 새 파일로 분리.
- `logic/.../PleosDesign.kt`, `logic/src/test/.../PleosDesignTest.kt`: 원본 토큰·합성/대비·서체·판정 매핑·집계 타일.
- `VehicleLayoutTest.kt`: 대비 조합을 실제 사용 역할의 enabled/pressed/disabled/popup/selection/toast로 확장, 기존 폭/복원 시험 유지.
- `scripts/test-pleos-design.mjs`: 벡터 참조/그리드/서체·브랜딩 금지·기존 manifest/release/Debug 경계.
- `scripts/check-submission.ps1`, `scripts/test-submission.mjs`:16/1.4.0 정합화.
- `README.md`, `docs/WORK-CHECKLIST.md`, 본 문서.

## 검증

- JVM269/269(기존257+12), Node76/76(기존73+3).
- 설치된 Android36/car·Vehicle2.3.2·NaviHelper3.0.5/navi-data3.0.4 JAR 대상 Debug/release 직접 Kotlin 타입 검사. 로컬 임시 BuildConfig/R stub를 사용하며 실제 R ID/APK 생성·리소스 병합 시험이 아니다.
- 설치된 AAPT2로 모든 main 리소스 컴파일 통과. XML/vector 컴파일과 실제 아이콘 렌더링을 구분한다.
- 근거: `.local/w12-jvm-tests.txt`, `w12-node-tests.txt`, `w12-debug-types.txt`, `w12-release-types.txt`, `w12-resource-check.txt`. 최종 결과/제출 검사는 체크리스트에 갱신한다.
- Gradle 빌드·APK 설치·실화면/터치/0.25초·DPI/mm·재생성/서비스 실측은 수행하지 않았다. **Claude가 빌드·AVD 확인**한다. 공식 기본 앱과의 픽셀 단위 일치나 공식 인증으로 주장하지 않는다.
- 최종 소스 패턴0건. 전체 제출 검사는 기존 Git 메시지 이메일24건으로 실패하며, 제출 제외 추적41개를 별도 인계한다. 기존 이력/파일을 삭제·재작성하거나 commit/push하지 않았다.

## Claude 실행 순서

1. `gradlew.bat :logic:test :app:assembleDebug --no-daemon --console=plain`→설치. release는 기존 로컬 서명 절차로 별도 확인한다.
2. P·속도0·UX 해제에서 라이트 메인 확인: 큰 제목/설명, 기록/복기/AI 타일, 주 버튼 한 개만 채움, 서버·삭제 외곽선. AI 스위치/타일을 각각 눌러 한 번만 바뀌는지 확인한다.
3. 기록 시작→준비 표시/주행 시간 갱신→D에서 화면 제거와 drive.* 지속→D 또는 F 시나리오→P. F면 모의 경고·모의 출처를 구별한다.
4. 복기 **≥1000dp**: 왼쪽2×2 숫자·회색 AI 한마디/출처, 오른쪽 순간 아이콘/근거/Box 피드백. 전체 일자 숫자가 페이지 이동으로 줄어들지 않아야 한다. 회전 놓침도 합산되며 문장 요약은 종류를 구분한다.
5. **600~999dp, <600dp**: 모두 한 열, 긴 제목/근거/AI 문장·피드백이 잘리지 않아야 한다. 현재 타일·카드까지 스크롤한 뒤 같은 실행 창에서 리사이즈해 같은 항목으로 돌아오는지 확인한다.
6. 다크 전환: 회색 면/흰 글자·밝은 채움 버튼/검정 글자·반전 선택, 경고/주의/좋아요 아이콘·글자 병기 확인. 대비와 실제 글자 크기는 실화면/DPI로 확인한다.
7. 누른 채 밖으로 이동→놓기:95% 축소/면 변화가 취소되고 기록/피드백/설정이 실행되지 않아야 한다. 탭/키보드·접근성 click도 확인한다. 제목 길게 메뉴는 유지한다.
8. AI 서버·삭제 확인·모의 내비 팝업의 오른쪽 아래 버튼·닫힘/초안/미적용 토글을 확인한다. 기록 중 숨은 메뉴에서 모의 내비 원천 변경을 시도해 거절 토스트의 최대 폭/여백을 확인한다. 주소 오류는 입력칸 오류 표시다. resize/주간·야간에서 초안·커서 유지, D에서는 팝업/기억 상태 제거를 확인한다. 상태 검증은 같은 창에서 하며 FormFactorConsole 디스플레이 제거/앱 강제 종료와 구분한다.
9. 기존 `capture-responsive.ps1`로 light-main/light-recap-wide/light-recap-narrow/dark-recap 등 고유 라벨로 캡처한다. 스크립트 기본값은 명령 계획, 실제 변경은 `-Apply`. 실제 공개/비공개 데이터 취급은 기존 README 절차를 따른다.

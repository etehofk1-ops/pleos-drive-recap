# 오늘 운전 복기 체크리스트

## W16b 지연 창 2차 (회귀 실패로 중단, 2026-10-25)

- 범위: 작업 트리 후보1.6.0(21), 커밋/push 없음. `.local/` 키·설정 미열람/미변경.
- 집계: W16b **1/4개 완료(25%)**. 검증/보고 완료 항목 수이며 기능 완성률이 아니다.
- 다음: W16B-02 재개 전, 이웃 조향 사건을 배경 잡음으로 세는 문맥 오염 문제와 원신호/고역 봉우리 일치 설계를 검토한다. 이번 세션은 수정2회 후 중단하며 더 튜닝하지 않는다.

- [ ] W16B-01: NMS·각 창 독립 신호·곡선/잡음 거절·미관측 후보 숨김 구현, 단위9/9. 중심 고역3봉우리 독립 보장은 미완료이며 필수2바퀴 사건 누락이 남음.
- [ ] W16B-02: 최종run1/run2·Example_1 통과, 실측 잡음 seed20261025 대칭/비대칭 곡선≤1%·직진≤0.5%. **2바퀴CSV 미사용2·늦음2 누락으로 실패**. 최초→수정1→수정2 후 중단.
- [ ] W16B-03: 선행 회귀 실패로 현대/기아 batch·채점 모두0회. 새 실데이터/속도대 수치는 미측정, 기아 결과로 값 변경 없음.
- [x] W16B-04: [지정 보고서](WEEK16B_EVENT_CURVE.md)·라운드/회귀/시뮬/미평가 표·추가시험3건·원본 불변 해시·남은 위험·전체 검증 실행 결과 인계. JVM/Gradle329중60실패·4생략, APK/AVD 미확인. 기존 시험 기대/삭제0건. 커밋/push 없음.

아래 W16 결과는 1차 시도 당시 기록이며 W16b 결과와 구분한다.

## W16 당시 전체 상태

- 범위: W16 실주행 지연 창 후보, 1.6.0(21) — **회귀 실패로 중단·사용 보류**.
- 갱신: 2026-10-25. 현대만으로 값 선택. 기아 추론/채점0회, 원본 정답/채점 변경 없음.
- 집계: W16 **3/6개 완료(50%)**. 진단용 구현/측정/보고 항목 수이며 기능 완성률이 아니다. 독립 검증·회귀·커밋은 완료하지 못했다.
- 다음: W16-04 재개 조건 — 별개 사건을 합치는 창 정책·곡선 전이 거절·애매 후보 표시 정책을 먼저 다시 정한다. 현재 후보에 값을 더 골라 맞추지 않는다. [중단 수치·재현·남은 위험](WEEK16_REALDATA_REDESIGN.md).

## W16 실주행 지연 창 (현재: 멈춤 조건으로 종료)

- [x] W16-01: 사전 진단/원본 detect/채점 확인. 현대54구간만 실행·정답 불변·16조합 정책 비교,4.0m/1.5° 선택. 보이는 clean19/35, 정답 밖2.78/시간·그중 미사용1.39/시간. 원시 후보29/35와 구분.
- [x] W16-02: 진단용10Hz/160점/신호256/중심 평균/지연 창/합치기·v6·세션 정리 후보와 순수 단위6/6. 고정 크기·연산 상한 주석. 기존 서명 키/설정을 읽지 않는 격리 검증 경로.
- [ ] W16-03: 기아70%·브랜드별 경고 상한 — **필수 회귀 실패로 기아 평가에 진입 안 함**. 기아 수치는 미평가이며 성공/실패로 추정하지 않는다.
- [ ] W16-04: 필수 회귀/전체 Gradle/시뮬 — AVD3개 CSV 사건 병합·Example_1 표시1 실패, 마지막 JVM326중52실패. 첫 Gradle325중51실패, 재확인은android.car.jar AccessDenied. 곡선120km/h오탐69.5~70%로 실패. 값 추가 선택·기대값 낮추기 없이 중단.
- [ ] W16-05: 로컬 커밋 — 이 세션 `.git` 권한이 읽기 전용이므로 미수행. 다른 저장소로 우회하거나 push하지 않음. 검토 가능한 source-only 패치로 인계.
- [x] W16-06: [지정 보고서](WEEK16_REALDATA_REDESIGN.md)·현대/속도대/시뮬/CSV 표·중단 원인·해시29개·로컬 증거 보존. 기존 dirty build 산출물과 새 후보 소스 구분. APK/AVD 성공 주장 없음.

## 15주차 비대칭 조향 정착 창 (당시 기록)

- [x] W15-01: 최대2초/작은 보정0.45·실제 감소 진입·큰 새 조향 분리·신호/주 구간 종료 고정·500ms 왕복 보완·약한 신호2초 관측·v5 정착 시간/하위 호환. 고정 크기 상태 유지.
- [x] W15-02: JVM317항목 실패0(실행315·측정2 기본skip), 신규18. CSV3건 상시 기대 통과·바이트 유지·기존 G6개 유지. Node76/76·직접 Debug/release 타입 검사 통과. 기존 기대 변경은 C1건으로 이유 기록.
- [ ] W15-03: **성능 목표 미달.** 전후 각8조건×29셀×200회 및28파일 해시 동결 검증 완료. 실측 잡음 비대칭40 평균96.5%·곡선≤1%·직진≤1.5% 상한 통과. 비대칭60 평균78.7~82.2%/대칭80 평균69.3% 미달, 일부 real 조건 오탐0.5%p 증가로 비증가도 실패. 평가 seed에 재튜닝하지 않음.
- [x] W15-04: 20/1.5.2·README/제출 검사 정합성·[보고서](WEEK15_ASYMMETRIC_SETTLING.md)·동결 전후/CSV 증거·Claude 절차 완료. 소스 문제0이나 기존 Git 메시지 이메일32건으로 전체 제출 검사는 실패. 커밋/push/APK 없음.
- [ ] CLAUDE-W15 (집계 제외): Gradle/APK·AVD 원본 G2회·잠금/깜빡이 타이밍·연속 구간·v5 재읽기/피드백·독립 자료 검증. 실제 성능 목표 충족으로 간주하지 않는다.

## 14주차 속도 정규화 판정 (당시 기록)

- [x] W14-01: 횡가속 근사·비활성 구간8초 EMA·0.5초 저역 통과·고정 크기 적분 특징. 기존 저속 회전·깜빡이 시각/2ms 지터·내비 회전 경로 회귀 통과.
- [x] W14-02: 차선 형상/곡선 숨김·400ms 평균 감속·v4 저장/v1~v3 읽기. 일반 JVM297 통과+측정1 기본 skip(JUnit298), 신규15개. Node76·Debug/release 직접 타입 검사 통과.
- [ ] W14-03: **합성 성능 목표 미달.** 전후4조건×29셀×200=46,400회 측정·해시 동결·평가 전용 seed/스트레스 보고는 완료. 표준3 seed에서40km/h/곡선/편한 감속/급제동 목표 통과, 60~120km/h 정답률·직진100km/h 오탐 실패(기존보다 악화). 결과를 보고 추가 튜닝하지 않음.
- [x] W14-04: 기존 시험 유지/변경 이유 목록·원본 G 그대로 순수 reducer+fusion 카드6장·18/1.5.0·README·오프라인 재현 명령·Claude 인계. 커밋/push/APK 없음.
- [ ] CLAUDE-W14 (구현 집계 제외): Gradle/APK·AVD G 6장·독립 실주행 CAN 검증. 그 데이터는 이번 작업 입력에서 제외.

## 13주차 구동 안정성 (당시 기록)

- [x] W13-01: frozen→pending→READY, Bundle도 WAIT 중 보존, 30분 만료·DISCARD, 안전한 두 열 앵커·포커스만 저장. 실제 B 시험의 A 실패 영향과 Bundle 대기 경로 누락을 분리 기록.
- [x] W13-02: 즉시 복기 틀/진행 표시·IO 해독·가시 행 프레임별 생성/재활용·100개 페이지/오래된 응답 차단. UI 속도1Hz 요청·같은 잠금 재생성 억제·Debug 표본/Release 원시 로그 차단.
- [x] W13-03: JVM282/282(기존269+13)·Node76/76·직접 Debug/release 타입 검사 통과. 복원/만료/가시 행/페이지/오래된 응답/저장 태그/로그 정책 검증. 실제 ms/CPU·Android 동작 증거와 구분.
- [x] W13-04: 17/1.4.1·README/검사 정합성·diff/소스 검토·Claude 인계. 소스 패턴0건, 기존 Git 메시지 이메일27건으로 전체 검사 실패·제외 추적43개 보존. 커밋/push/APK 없음.
- [ ] CLAUDE-W13 (구현 집계 제외): 독립 일반 복귀/am kill·WAIT 재-pause·D 폐기/만료·120개 첫 카드/프레임·주행 CPU/RAM·Release 로그/서명 APK.

## 12주차 Pleos 디자인 (당시 기록)

- [x] W12-01: 원본 Color 994235… 토큰·버튼/타이포·14개 벡터·대비/판정 매핑 순수 함수. JVM269/269(기존257+12).
- [x] W12-02: 공통 Tile/Primary/Secondary/Tabs/Divider/Toast·95% 누름·시스템 팝업, 메인/복기 타일·전체 일자 숫자·기존 잠금/메모리 복원 보존. Debug/release 직접 타입 검사 통과.
- [x] W12-03: Node76/76(기존73+3)·AAPT2 리소스 컴파일. 실제 터치/렌더링 시험과 구분.
- [x] W12-04:16/1.4.0·README/토큰 표/화면 전후·최종 diff/소스 검사·Claude 인계. 소스 패턴0건·기존 Git 이메일24건으로 전체 실패, 제외 추적41개 보존.
- [ ] CLAUDE-W12 (구현 집계 제외): APK·메인/기록/복기 넓음·좁음/다크·팝업·눌림/취소·토스트·복원/잠금·전체 집계 실측.

## 11주차 차량 반응형 (당시 기록)


- [x] W11-01: manifest 재생성/리사이즈·폭 분기·복기 두 열·단위 dp/sp·세 줄 문단·주간/야간 팔레트.
- [x] W11-02: main/복기/진단/탐침/게임/내비 입력 메모리 checkpoint, 대화상자·초안·스크롤·토글, 새 안전/값 재확인·주행 시 폐기.
- [x] W11-03: JVM257/257(기존242+15)·Node73/73(기존70+3), Android/Pleos JAR 직접 Debug/release Kotlin 타입 검사.
- [x] W11-04:15/1.3.0 제출 검사 정합화·README/캡처/폼팩터 절차·XML/diff 검토/보고. 소스 패턴0건, 전체 검사는 기존 Git 메시지 이메일22건으로 실패·제외 추적40개 별도 인계.
- [ ] CLAUDE-W11 (구현 집계 제외): Gradle/APK·세 폭/두 방향/두 테마·복기/진단/초안/게임/잠금·서비스 PID·mm/대비/반응 시간/호환성 실측.
- 근거: `.local/w11-jvm-tests.txt`, `.local/w11-node-tests.txt`, `.local/w11-debug-types.txt`, `.local/w11-release-types.txt`. 아래는 이전 기록이다.

## 10주차 모의 내비 (당시 기록)

- [x] W10-01: NavigationSource/실제 PleosNaviSource 유지·Debug DemoNaviSource/전용 receiver·기본OFF/기록 전 토글·메모리 입력/3초 만료/원천 격리.
- [x] W10-02: 닫힌 파서/중복·역순·시각 방어/수명·출처 라벨/저장 v3·v1/v2 읽기. JVM242/242(기존227+15).
- [x] W10-03: 메인/복기 배지·저장 카드 모의 출처 유지·Debug/release 직접 Kotlin 타입 검사·release receiver/토글 부재 정적 검사. 실제 APK 병합은 별도.
- [x] W10-04: F JSON/단일 시계 PowerShell Console+adb 공급·Enter 시작·1초 heartbeat/속도 gate·실패 복원, Node70/70(기존66+4),14/1.2.0/인계 문서.
- [ ] CLAUDE-W10-F (구현 집계 제외): 실제 Debug 빌드/방송 수신/배지/F4장·0애매함/만료·저장 복원/기록 삭제/P-D 잠금 확인.
- [ ] CLAUDE-W10-RELEASE (구현 집계 제외): release manifest/APK에서 receiver·토글 없음·실제 source 유지·14/1.2.0 서명/제출 확인. 모의 시연 자료는 실제 TBT 증거와 구분.
- 근거: `.local/w10-jvm-tests.txt`, `.local/w10-node-tests.txt`, `.local/w10-debug-types.txt`, `.local/w10-release-types.txt`. 아래는 이전 당시 기록이다.

## 9주차 내비 융합 (당시 기록)

- [x] W9-01: 본체/데이터 AAR javap·해시/실제 enum·권한 확인, 단위 미확인/개별 passage 없음 명시. SDK 기본 포함13/1.1.0.
- [x] W9-02: 순수 융합1~5/지연 대기/세대 격리/방향별 깜빡이/저장 v1 호환. JVM227/227(기존196+31), 실제 JAR 기반 앱 Debug/Release Kotlin 타입 검사 통과.
- [x] W9-03: typed 서비스 클라이언트/재연결·P 시연 목적지 요청·내비 라벨/회전 카드/집계/닫힌 AI 계약. 좌표/SDK TBT 목록 저장·AI 전송 없음.
- [x] W9-04: E phase3/geo preflight·템플릿/실행 문서·Node66/66. 기존 D 시험 선택 조건만 실측 코스팅에 정합화하고 단언/입력/판정 유지.
- [ ] CLAUDE-W9-UNIT (구현 집계 제외): 지도100m/50m와 SDK distanceRaw 대조 또는 공식 단위 근거 확보. 확인 전 기본 거리 gate 비활성 유지.
- [ ] CLAUDE-W9-AVD (구현 집계 제외): Gradle/실제 TBT·재연결·공개 도로 경로/geo 소비·E 우회전/유턴/차선변경 및 충돌/IC·A/D 회귀/잠금 확인.
- [ ] CLAUDE-RELEASE/SCOPE (구현 집계 제외): 새13/1.1.0 서명 APK·제출 검사·실제 화면/영상/개인정보 검토. 현재 Git 메시지 이메일18건·제외 추적38개는 이력 변경 없이 보고.
- 근거: `.local/w9-jvm-tests.txt`, `.local/w9-node-tests.txt`, `.local/w9-debug-types.txt`, `.local/w9-release-types.txt`, `.local/w9-submission-check.txt`. 아래는 이전 당시 기록이다.

## 8주차 제출 준비 (당시 기록)

- [x] W8-01: release 서명 속성/누락 검증 task·minify/shrink off·12/rc1 구현 및 정적 검토. 실제 AGP 실행은 별도 확인.
- [x] W8-02: release 서버 설정/private IPC/저장/서비스 전환·캐시 무효화,10.0.2.2 한정 HTTP. JVM196/196, Debug/Release 직접 Kotlin 타입 검사 통과.
- [x] W8-03: 제출 README/개인 홈 경로 정리·제외 목록·apk/.gitkeep,37개 원본 이동(36해시 일치/열린 로그1해시 미검증).
- [x] W8-04: 검사 스크립트/새 모의 시험10/10·소스 검토본84텍스트0건. 기존 Git 이메일17건/실제 APK 없음은 검사 실패로 유지. 이력/저장소 생성/push 없음.
- [ ] CLAUDE-W8-RELEASE (구현 집계 제외): 실제 키/누락 오류/서명 release12·rc1 빌드·APK 복사/검증, release AVD·설정/폴백/잠금.
- [ ] CLAUDE-W8-SCOPE (구현 집계 제외): 기존 Git 이력/제외 경로 미포함·실명/화면/영상/인증서와 실제 팀명 확인. 최종 제출/접근 권한은 이후 승인 범위.
- [ ] BASELINE-D-NODE (기존 별도 확인): 실측 후 브레이크0으로 변경한 D와 기존 Node 단언2개 불일치. 코드/단언은 이번에 변경하지 않음.
- 근거: `.local/w8-jvm-tests.txt`, `.local/w8-submission-tests.txt`, `.local/w8-debug-types.txt`, `.local/w8-release-types.txt`, `.local/w8-source-review-check.txt`. 아래는 이전 당시 기록이다.

## 7b D 실측 수정 (0.6.3 당시 근거)

- [x] W7B-01: 기존 Math.round 보존, preflight 정수 정규화/held·HTTP 일치/변환 알림, 정수 전용 모의 서버 회귀.
- [x] W7B-02: D 완만 감속1km/h·250ms/급제동3km/h·100ms/끝 정지 이정표1.25초, HTTP 지연 뒤 따라잡기 방지. Node53/53.
- [x] W7B-03: 실제 GET/notify 현재 값으로 준비 표시, 오류/복구 순수 시험, 카드 위 보조 버튼 이동. JVM191/191, 전체 Kotlin 직접 타입 검사 통과.
- [x] W7B-04: 버전/README/현재 타임라인·실측 경계/Claude 실행 문서. 규칙 임계값·구독·잠금·게임/탐침 보존.
- [ ] CLAUDE-W7B (구현 집계 제외): 급제동1·약8~10m/s²/애매함0, 깜빡이 notify 준비됨, 첫 화면 카드/버튼/잠금 실측.
- 근거: `.local/w7b-jvm-tests.txt`, `.local/w7b-node-tests.txt`, `.local/w7b-kotlin-types.txt`.0.6.2 실측 원본은 [7b 문서](WEEK7B_DEMO_FIX.md)에 연결했다. 아래는 이전 구현 당시 기록이다.

## 7주차 다듬기/시연 (0.6.2 당시 근거)

- [x] W7-01: 규칙 판정/AI 확인 중/보류/미사용/추정 구별, 감속>15 일반 표시 보호/Debug 원시값. HTTP 스키마와 규칙 결과 유지.
- [x] W7-02: 첫 Provider 대기8초/시작 준비 중/중복 시작 방지, 전체 집계 한 줄/큰 카드/강조 문장. 전체 Kotlin 직접 타입 검사 통과.
- [x] W7-03: A/D 단계 급제동/완만 감속/D 매 스텝 한 줄 caption 콘솔 전용. JVM182/182(기존170+12), Node49/49(서버27+리플레이22).
- [x] W7-04: 변경 파일/시나리오 D 표/Claude 실행 및 실측 경계 문서화. Gradle/AVD/키/실제 AI 미실행.
- [ ] CLAUDE-W7 (구현 집계 제외): 콜드 스타트 한 번 시작,10·0.6.2 화면·D/감속·라벨/캐시/잠금 실제 확인.
- 근거: `.local/w7-jvm-tests.txt`, `.local/w7-node-tests.txt`, `.local/w7-kotlin-types.txt`. 0.6.1 A/문장 실측은 [6c 부록](WEEK6C_SUMMARY_LATENCY.md)에서 확인했다. 아래는 이전 구현 당시 기록이다.

## 6c 지연 대응 (0.6.1 당시 근거)

- [x] W6C-01: Go DeepSeek low,1200토큰,15초 공유 예산/빈 content 대체1회. 서버27+리플레이18 = Node45/45.
- [x] W6C-02: P 서비스 선요청, 카드/맥락 해시·메모리/오늘 로컬 캐시, 삭제/재주행/AI OFF/늦은 응답 보호. JVM170/170(기존160+신규10).
- [x] W6C-03: 요약20초/분류3초, 준비 중/템플릿→문장 교체, offset null/양수 규약. 앱 전체 Kotlin 직접 타입 검사 통과.
- [x] W6C-04: AI 계약/실행 문서/회귀 예시·실측 미제공 구분, 변경 파일/검증 인계. Gradle/APK 미실행.

## 6c 별도 인계 (구현 집계 제외)

- [ ] CLAUDE-W6C: 9·0.6.1 APK/실제 Go1200토큰/화면 없는 P 선요청/프로세스 재실행 캐시/서버 중지 후 강제 폴백/대기 중 D·삭제·AI OFF를 확인한다.
- [ ] LIVE-TEXT-3: deepseek-low/minimax/glm 실제 text3개를 받아 동일 문장으로 회귀 시험. 본문을 요청했고, 현재 합성3개는 실측 증거가 아니다.
- 증거: `.local/w6c-jvm-tests.txt`170, `.local/w6c-node-tests.txt`45, `.local/w6c-kotlin-types.txt`. 외부 API/키 파일/AVD를 이번에 실행하지 않았다.

## 6주차 AI (0.6.0 당시 근거)

- [x] W6-01: 공식 Jev/Go 형식 확인, 닫힌 [계약](AI_CONTRACT.md), 환경/승인된 Go 키 읽기, 모의 업스트림19개 통과. 실제 키/API 미사용.
- [x] W6-02: 순수 Jev 특징/0.6 채택/보류·오류 폴백, 요약·문구, 원래 규칙 근거 보존. 기존137 + 신규23 = JVM160/160.
- [x] W6-03: AI 토글/3초/취소/세션/기본 문구, Debug 호스트 HTTP 제한,2일 상세+7일 집계/삭제. 전체 Kotlin 직접 타입 검사 통과. Android 저장/리소스 실기는 별도.
- [x] W6-04: 시나리오 C, Node 총37/37, 인계/README/계약, XML 및 diff 정적 검토. 기존 A/B와 게임·탐침·주행 구독 보존.

## 별도 인계 (W6 집계 제외)

- [ ] CLAUDE-W6: Gradle/8·0.6.0 APK 설치/실제 Jev/Go·A/C·AI OFF·3초 폴백·주행 잠금/늦은 응답·삭제/집계 보관 확인.
- 증거: `.local/w6-jvm-tests.txt`, `.local/w6-node-tests.txt`, `.local/w6-kotlin-types.txt`. 실제 공급자 지연/채택률은 미측정. 서버8초보다 앱3초 제한이 우선한다.
- W5c AVD 통과는 기존 문서의 Claude 부록으로 확인했다. 과거 아래 문구는 당시 오프라인 작업 범위 기록이며 W6 성공 증거가 아니다.

## 5c 수정 (0.5.2 당시 근거)

- [x] W5C-01: 초기 GET 재시도·새 notify 우선·준비 상태/미확인 표시, 세션 재시작/중지 예약 정리. 직접 소스 타입 검사 통과.
- [x] W5C-02: 350ms 실측 파형·작은 처리 시각 역전 보정·ON_CHANGE 유지·깜빡이 구간 대응. JVM 137/137(신규18).
- [x] W5C-03: 브레이크 전후1.5초·고빈도 속도100ms 묶음·최대치 동일 카드 갱신/피드백 보존, HTTP 스텝 속도/브레이크 유지. Node 17/17.
- [x] W5C-04: [5c 인계 문서](WEEK5C_RULE_REFINEMENT.md), 최신 README·구간 로그/초 표시·`git diff --check`. 빌드/AVD와 분리 보고.

## 5b 수정 (0.5.1 당시 근거)

- [x] W5B-01: AAOS GEAR_SELECTION·속도 단위·Console 카탈로그 근거 확인. [최신 보고서](WEEK5B_DRIVING_STATE.md).
- [x] W5B-02: 선택 기어/UX/속도 OR 주행·모두 정차 시 PARK, 구독/서비스 연결·판단 근거 로그, 단위/목록/알림 일치. 전체 Kotlin 소스 타입 검사 통과.
- [x] W5B-03: HTTP 시나리오 A/B·서버 속도 대기·페이지 자체 쓰기 격리/복원. 모의 테스트 14/14 통과.
- [x] W5B-04: 전체 JVM 119/119(신규 12), 변경 검토·최신 README/실행 순서. `git diff --check` 통과.

## 기존 5주차 항목 (0.5.0 당시 근거)

- [x] W5-01: SDK 근거·임계값·미확정 정책 문서화. Vehicle AAR 조사 및 NaviHelper AAR 다운로드 실패를 구분 기록.
- [x] W5-02: 순수 분할·분류·깜빡이·급제동 규칙과 JVM 검증. 기존 52 + 신규 55 = 107/107 통과.
- [ ] W5-03: 서비스 연결·내부 요약 저장·2일 보관·삭제·피드백 구현. **TBT 실제 보조 입력 연결은 미완료** — NaviHelper 3.0.5 AAR 경로/필드·단위·회전 의미 확인 필요. 메타데이터 탐침·미수신 대체·순수 보조 입력 계약까지 준비.
- [x] W5-04: 새 시작/복기 화면과 P·UX 잠금, 기존 진단 보존. 전체 소스 타입 검사와 기존 game/probe UI 본문 비교 통과. 실제 화면은 아래 Claude 항목에서 검증.
- [x] W5-05: 시나리오 JSON 2종과 Console JS. Node 오프라인 DOM 10/10, 실제 JSON을 쓰는 JVM 시나리오 4종 통과. Console 카탈로그는 읽기만 수행.
- [x] W5-06: 전체 JVM·직접 Kotlin 타입 검사·변경 검토·[인계 문서](WEEK5_RECAP.md). Gradle/AVD 실측과 구분.

## 별도 인계(위 집계 제외)

- [x] CLAUDE-AVD-W5C: [5c 실측 부록](WEEK5C_RULE_REFINEMENT.md)에 Claude의 0.5.2 통과 기록이 추가됨. 아래는 당시 인계 문구: 0.5.2 Gradle 빌드·설치·초기 GET/시각 대응/41ms 펄스와 최대 감속/HTTP A 기대4개·애매함0 확인. 0.5.1 주행 상태 정상과 애매함3·급제동0은 Claude가 실측했고 5c에 반영했다. 이후 실측은 5c 문서 부록 참조.

## 검증 근거 / 남은 경계

현재 5c: `.local/w5c-jvm-tests.txt` 137/137, `.local/w5c-replay-tests.txt` 17/17, `.local/w5c-kotlin-types.txt` 직접 타입 검사 통과. Gradle/APK/HTTP 제어/AVD 실행 없음. 초기 GET 재시도와 Android 파일 최대치 upsert 등 실제 동작은 Claude 인계다. 이후 문단은 이전 검증 당시 기록이다.

현재 5b: `.local/w5b-jvm-tests.txt` 119/119, `.local/w5b-replay-tests.txt` 14/14, `.local/w5b-kotlin-types.txt` 직접 타입 검사 통과. Gradle/APK/실제 서버 제어는 실행하지 않았다. 0.5.1에서는 CarSafetyClient·DriveSignalSubscriptions를 수정했으며 shouldLock·게임/탐침 본문은 유지했다. 아래는 기존 0.5.0 증거다.

- `.local/w5-jvm-tests.txt`: JUnit 107개, 실패 0.
- `.local/w5-replay-tests.txt`: Node 10개, 실패 0. 가상 DOM/시계이며 실제 Console 동작 증거가 아님.
- `.local/w5-kotlin-types.txt`: 설치된 SDK/AAR에 대한 직접 소스 타입 검사. BuildConfig.DEBUG 임시 stub 사용, APK 빌드 증거가 아님.
- `git diff --check` 통과. 기존 Car/Vehicle/탐침 클라이언트와 게임 순수 로직 변경 없음. 게임/탐침 UI 본문은 원본과 동일함을 비교.
- 실제 Android 파일 쓰기·Job 등록/삭제·알림·화면·0.5.0 수신은 미검증. NaviHelper 메타데이터 선택 빌드도 미검증.

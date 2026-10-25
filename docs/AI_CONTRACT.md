# AI_CONTRACT v2 · 1.2.0

2026-10-23. 앱과 중계 서버 사이의 계약이다. 서버는 아래 키만 허용하며 알 수 없는 키·범위 밖 값은 거부한다. 위치, VIN, 경로, 원시 시계열, 카드 ID, 정확한 시각, 실제 날짜, 피드백은 전송하지 않는다. AI 결과는 표시용이며 규칙 카드·수치·피드백·집계를 바꾸지 않는다.

## 전송·실패

- 로컬 서버: `127.0.0.1:8787`, 앱 기본값: `http://10.0.2.2:8787`. Debug/release 모두 앱의 **AI 서버 설정**으로 origin을 저장한다. HTTP는10.0.2.2 한정이며 나머지는 HTTPS다. 인증정보/쿼리/fragment/경로는 허용하지 않는다. 키는 APK에 없다. 서버 변경 시 분류 요청과 요약 작업·이전 문장 캐시를 무효화하며 서비스 프로세스와 UI에 같은 주소를 적용한다. HTTP JSON 스키마는 바꾸지 않았다.
- 두 POST 공통: `Content-Type: application/json`, `X-Recap-Session: <무작위 UUID>`. UUID는 앱 프로세스 수명 동안 유지하고 디스크에 저장하지 않는다. Go에만 `x-opencode-session`으로 전달한다. 사용자·차량 식별자가 아니다.
- 요청 64KiB 이하. 앱 응답 읽기 16KiB 이하. JSON 응답은 `Cache-Control: no-store`.
- 성공 200. 잘못된 입력 400 `{"error":"invalid_request"}`, JSON 이외 415, Origin 헤더가 있는 요청 403, 없는 경로 404. 공급자 오류·응답 검증 실패·키 없음·시간 초과는 503 `{"error":"unavailable"}`. 업스트림 오류 본문은 반환하지 않는다.
- 앱 분류 제한은 총3초, 요약 제한은 총20초다. 서버 Jev 제한2.8초, 생성형은 첫 모델과 대체 모델을 합쳐15초다. 앱 연결 종료 시 업스트림도 취소한다. Go의 빈 content에만 대체 모델로1회 요청하며 오류/401/5xx/타임아웃/문구 검증 실패에는 재시도하지 않는다.
- 요약은 기록 서비스가 D→P 정차를 확인하면 화면과 무관하게 미리 요청한다. Activity onPause는 서비스의 요약을 취소하지 않는다. 다시 주행/미확인 상태가 되거나 AI 끄기/삭제 시에는 취소한다. UI는 잠금 중 모든 문구를 가리고, 분류 요청도 취소한다.
- 자동 폴링은 같은 카드 목록의 실패한 요약을 반복 요청하지 않는다. 정차 후 **AI 다시 확인**은 캐시를 지우고 명시적으로 새 요청한다. 규칙 카드와 템플릿은 바로 표시하고, 대기 중에는 **AI 문장 준비 중**, 완료 후에는 문장으로 교체한다. 캐시 적중이면 HTTP 요청이 없다.
- `GET /health` → `{"ok":true,"provider":"opencode-go"}`. 프로세스 생존 확인이며 키 유효성·실제 AI 성공의 증거가 아니다.

## POST /classify

규칙 `UNSURE` 중 조향 구간만 대상이다. 급제동 후보, 깜빡이 관측 자체가 불명확한 구간은 호출하지 않는다. 확정 카드에는 호출하지 않는다.

```json
{
  "durationMs": 2450,
  "peakAbsRaw": 32,
  "signReversals": 1,
  "speedKphMin": 25,
  "speedKphMax": 35,
  "indicator": {"state": "none", "offsetMs": null},
  "naviHint": "unknown"
}
```

| 필드 | 규약 |
|---|---|
| durationMs | 정수 600..60000, 구간 길이만 |
| peakAbsRaw | 유한수 0..10000, 절댓값 최대. 원시 조향 단위·좌우를 추정하지 않음 |
| signReversals | 정수 0..1000, 부호 반전 횟수 |
| speedKphMin/Max | 둘 다 null 또는 유한수 0..400, min≤max. 기기 m/s를 km/h로 변환한 구간 범위 |
| indicator.state | `none`, `before`, `late`. 미수신을 none으로 만들지 않음 |
| indicator.offsetMs | late이면 시작 뒤 켜진 상대 시각 정수 0..durationMs. 나머지는 null. 사전 켜짐의 정확한 오프셋은 기존 요약에 없어 전송하지 않음 |
| naviHint | `none`, `turn_ahead`, `unknown`. 기존 축약 필드만 유지. 거리/SDK 코드/위치는 전송하지 않으며 내비 확인/불일치 카드는 Jev 대상에서 제외 |

offsetMs는 **조향 구간 시작=0, 이후가 양수**다. `before`는 사전 사용이라는 상태만 나타내므로 앱은 반드시 null을 보낸다. `{state:"before",offsetMs:-1500}`은 현재 계약에서400이며 음수로 사전 시간을 전달하지 않는다. `none`도 null이다. `late`만0..durationMs의 상대 지연을 보낸다. 앱 순수 특징 추출/JVM 시험과 서버 HTTP 시험에서 이 규약을 함께 확인한다.

```json
{
  "label": "차선 변경",
  "probabilities": {"차선 변경": 0.7, "교차로 회전": 0.1, "굽은 길": 0.1, "판단 보류": 0.1},
  "model": "jev-preview",
  "latencyMs": 420
}
```

응답은 위 네 키만 허용한다. probabilities는 한글 선택지 네 개를 모두 포함하는 0..1 유한수이며 합계 오차는 0.02 이하다. label은 최댓값 선택지다. model은 공급자가 실제 반환한 모델 식별자이며 예시와 다를 수 있다. latencyMs는 서버가 잰 정수 경과 시간이다.

앱 채택: 최댓값 **≥0.6**이고 label이 `판단 보류`가 아닐 때만 **AI 추정 · 종류**를 덧붙인다. 유효한 응답의 낮은 확률/보류는 **애매함 · AI 판단 보류**, 요청 중은 **애매함 · AI 확인 중**, AI OFF/서버·응답 오류는 AI 대상 애매한 카드에만 **애매함 · AI 미사용**이다. 규칙 확정 카드와 AI 대상이 아닌 카드는 **규칙 판정**으로 표시한다. 이 값은 **정답 확률이 아니다**. Jev의 별도 confidence 필드를 정답 확률이나 채택 기준으로 쓰지 않는다. 깜빡이 유무·늦음은 기존 규칙 결과만 표시하고 근거 수치는 보존한다. 이번0.6.2는 표시 상태만 구별하며 HTTP 스키마/채택 기준은 유지한다.

Jev 업스트림은 `POST https://api.typesafe.ai/v1/systemone`, Bearer 환경 키, `model: "jev-preview"`, `state: <특징값>`, `questions.kind: {type:"choice", instructions, criteria: {선택지: 설명}}`이다. **criteria는 배열이 아닌 객체**다. `answers.kind.choice`와 `answers.kind.probabilities`를 읽는다. 근거: [System One](https://docs.typesafe.ai/api/system-one), [Choice](https://docs.typesafe.ai/primitives/choice), [Confidence](https://docs.typesafe.ai/confidence).

## POST /summarize

```json
{
  "today": [
    {"kind": "LANE_NO_SIGNAL", "hourBucket": 18, "speedBand": "mid", "indicator": "none"},
    {"kind": "UNSURE", "hourBucket": 19, "speedBand": "unknown", "indicator": "before"}
  ],
  "recentDays": [
    {"date": "D-1", "counts": {"missing": 1, "late": 0, "good": 2, "hardBrake": 0}}
  ]
}
```

- today는 오늘의 표시 대상 규칙 카드 전체, 최대 500개다. 화면 페이지(100개)와 무관하다. 500개를 넘으면 임의 표본을 전송하지 않고 템플릿을 사용한다.
- kind: `STEERING|LANE_CHANGE|LANE_SIGNALLED|LANE_NO_SIGNAL|LANE_LATE_SIGNAL|TURN|CURVE|HARD_BRAKE|BRAKE_UNVERIFIED|UNSURE|TURN_NO_SIGNAL|TURN_LATE_SIGNAL|TURN_SIGNALLED|BLINKER_LEFT_ON`. BLINKER_LEFT_ON(1.7.0, 깜빡이를 켠 채 주행)은 kind 이름만 전달되고 새 필드는 없다. 회전3종은 내비와 조향의 로컬 융합 결과다. AI 분류를 규칙 kind로 덮어쓰지 않는다. 기존 순수 조향의 숨긴 회전은 포함하지 않는다.
- hourBucket: 기기 현지 시간대의 0..23 정수. 분·초·epoch 없음.
- speedBand: max 속도 기준 `low`(<30km/h), `mid`(30 이상60 미만), `high`(60 이상). **미수신을 저속으로 꾸미지 않기 위해 `unknown`을 추가**했다.
- indicator: `none|before|late|unknown`. 분류 입력과 달리 오늘의 불확실한 규칙 카드도 요약하므로 unknown을 허용한다.
- recentDays: 최대6개, 중복 없는 `D-1`..`D-6`. 실제 기록된 날짜만 보낸다. 기록 없는 날을0건으로 만들지 않는다. v2 counts는 `missing/late/good/hardBrake/turnMissing/turnLate/turnGood` 일곱 키의0..100000 정수다. 차선 변경과 회전은 분리한다. 서버는 기존 v1 네 키도 허용하지만5/6개 같은 부분 확장은 거부한다. 위 JSON은 호환 가능한 v1 예시다.

```json
{"text":"오늘 기록에서 깜빡이 사용을 돌아볼 순간을 모았어요. 정차한 지금 근거를 차분히 살펴보세요.","patterns":[],"model":"deepseek-v4.1-flash"}
```

응답 키는 text/patterns/model뿐이다. text는 한글을 포함한 8..700자, 2~3문장. 비난·안전 보장·법률 판단·주행 중 행동 지시를 금지하고 숫자는 입력에 있는 것만 허용한다. 정차 후의 `한 번 살펴보세요/돌아보세요/확인해 보세요`는 사건 수 단정과 구분해 허용한다. `운전 중/주행 중` 문구와 근거 없는 `두 번 급제동` 등은 계속 차단한다. 모든 의미 오류를 자동 검출한다고 보장하지 않는다. 빈 content/reasoning-only이면 Go 대체 모델을1회 시도하고 여전히 비었거나 검증 실패면503이다. 추론 내용은 표시하지 않는다.

patterns는 임의 모델 주장을 받지 않는다. 실제 과거 집계가 최소2일 있고, 오늘의 해당 확정 후보 수가 그 날짜들의 일평균보다 많을 때 다음 문구 중 공급자가 선택한 것만 허용한다(보통 0~3개). 이 비교는 주행량 보정이나 인과 분석이 아니다.

- `기록된 최근 날짜보다 깜빡이 미사용 후보가 많아요.`
- `기록된 최근 날짜보다 늦은 깜빡이 후보가 많아요.`
- `기록된 최근 날짜보다 급제동 후보가 많아요.`

기본 공급자는 `opencode-go`이며 `/chat/completions`에 messages와 max_tokens(기본1200)를 보낸다. Go+DeepSeek 계열에만 `reasoning_effort:"low"`를 추가한다(Claude의2026-10-23 실측 근거). 빈 content 대체 모델은 RECAP_LLM_FALLBACK_MODEL, 기본 `minimax-m3.1-flash`다. 동일 모델/빈 설정은 재호출하지 않는다. 다른 모델에는 DeepSeek 옵션이나 미확인 thinking 옵션을 보내지 않는다. 고유 `User-Agent: drive-recap-proto/0.6`와 동일 세션 헤더는 대체 호출에도 유지한다. Go는 개발·시연용이다. 모델·실측 한계·환경 변수는 [서버 README](../ai-proxy/README.md)를 따른다. [공식 Go 문서](https://opencode.ai/v2/docs/console/go/).

## 저장·로그

- 기존 `filesDir/recap-v1/`: 오늘·어제2일의 최소 구간 요약과 피드백. 상세 형식v3는 기존v2에 `demoNavigation` Boolean을 추가하며 v1/v2 읽기를 유지한다. 모의 카드를 실제 입력 카드로 보이게 하지 않기 위한 출처다. SDK TBT 원문·거리·연결 세대·위치·방송 runId/sequence·이벤트 목록은 저장하지 않는다. AI HTTP 스키마는v2 그대로이며 모의 원본 방송을 전달하지 않는다. 모의 카드의 축약 kind 등은 기존 허용 특징으로 요약될 수 있으므로 시연 전후 기록을 삭제하고 실기록/주간 패턴과 혼합하지 않는다.
- 기존 `filesDir/recap-counts-v1/`: 오늘부터6일 전까지7일의 실제 날짜와 건수만. v1 네 정수와 v2 일곱 정수를 모두 읽는다. UNSURE와 AI 추정을 확정 건수로 계산하지 않는다. 상세 요약 저장 성공 뒤 집계를 저장한다. 기존 어제 요약은 집계로 가져온다.
- 신규 `filesDir/recap-ai-summary-v1/today.bin`: 오늘의 생성형 text/patterns/model과 날짜·카드 목록 SHA-256·요약 맥락 SHA-256만 AtomicFile에 저장한다. 원시 특징값/시계열/세션 UUID는 없다. 카드는 전체 목록의 근거·종류·시각·ID를 해시하며 피드백/주행 타이머/표시 순서는 제외한다. 최근 건수·시간대가 달라져도 맥락 해시로 무효화한다. 해시는 기기 내부 전용이며 서버로 보내지 않는다.
- 오늘과 해시가 모두 일치할 때만 캐시를 사용한다. 카드 변경/삭제/AI 끄기/재주행/날짜 변경 뒤 늦은 응답은 저장하지 않는다. 프로세스 재시작 시 일치하는 디스크 캐시는 복원한다. OS가 완료 전에 프로세스를 종료하면 작업 완료를 보장하지 않으며 다음 정차/복기 진입 시 다시 준비한다.
- 접근 시 및 기존6시간 정리 Job에서 기간 밖 자료를 삭제한다. OS 지연으로 정확한 자정 삭제를 보장하지 않는다. **기록 삭제**는 상세·집계·피드백·문장 캐시를 삭제한다. AI 토글 설정은 유지한다.
- Jev 결정·앱 세션 UUID·HTTP 본문은 파일에 저장하지 않는다. 생성형 문장만 위 범위로 캐시한다. 앱 설정에는 enabled Boolean만 저장한다.
- 서버는 시작 시 공급자/모델·키 존재 Boolean, 필요시 승인된 Aside 파일 경로만 기록한다. 요청 로그는 정적인 이벤트명만 남긴다. 키·특징값·모델 문장·업스트림 오류 본문을 로그에 남기지 않는다.
- 서버는 API 키를 파일에 쓰지 않는다. TYPESAFE_API_KEY는 환경변수 전용. Go는 OPENCODE_GO_API_KEY 우선, 없을 때만 사용자가 승인한 Aside의 opencode-go/api_key 항목을 실행 시 읽는다. OpenAI 선택 시 OPENAI_API_KEY를 쓴다.

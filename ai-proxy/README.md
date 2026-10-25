# 오늘 운전 복기 로컬 AI 중계

Node 20 이상, 외부 패키지와 npm 설치 없이 실행한다. Jev는 애매한 조향 종류만 분류하고, 생성형은 정차 후 읽는 한국어 문장을 만든다. 계약은 [AI_CONTRACT v2](../docs/AI_CONTRACT.md), release 설치·주소 설정은 [프로젝트 README](../README.md)를 따른다. 1.1.0은 내비 융합 회전 kind3종과 회전 집계3개를 추가하고 기존 v1 네 집계 입력도 허용한다. SDK 거리/위치/경로는 입력으로 받지 않는다.

## 실행

프로젝트 루트의 PowerShell에서 실행한다. 키를 명령 인수·소스·파일에 붙여 넣거나 환경변수 값을 출력하지 않는다.

```powershell
$env:RECAP_LLM_PROVIDER='opencode-go'
$env:RECAP_LLM_MODEL='deepseek-v4.1-flash'
node .\ai-proxy\server.mjs
```

TYPESAFE_API_KEY는 서버 프로세스 환경에 별도로 제공한다. Go 키는 사용자 환경의 OPENCODE_GO_API_KEY를 우선 사용한다. 없으면 런타임에 `~/.aside/u/0/credentials.json`의 `opencode-go` 항목(`type=api_key`, `key`)만 읽는다. 실제 자격 파일과 키는 저장소에 넣지 않는다. 읽기 실패는 키 없음으로 처리한다. 키 존재 여부만 시작 로그에 나온다. 키 없음 또는 업스트림401이면 해당 공급자 요청은503이며 앱은 폴백한다. 401 이후 해당 키의 호출은 프로세스 재시작 전까지 중지한다.

별도 PowerShell:

```powershell
Invoke-RestMethod http://127.0.0.1:8787/health
```

이 health 성공은 실제 Jev/Go 호출 성공을 뜻하지 않는다. `Ctrl+C`로 중지한다. 브라우저 콘솔에서 이 서버를 호출하지 않는다(Origin 거부). Console 리플레이는 차량 제어용8088 서버에서 실행한다.

## 설정

| 환경 변수 | 기본/동작 |
|---|---|
| RECAP_LLM_PROVIDER | `opencode-go`; `openai`, `none` 선택 가능. none은 생성형만 끄며 Jev는 별개 |
| RECAP_LLM_BASE_URL | Go: `https://opencode.ai/inference/go/openai/v1`; OpenAI: `https://api.openai.com/v1`. HTTPS, 인증정보/쿼리/fragment 없는 주소만 허용 |
| RECAP_LLM_MODEL | Go: `deepseek-v4.1-flash`. OpenAI에서 미지정이면 시작 시 `/models`의 사용 가능한 gpt 계열 텍스트 mini/nano 중 하나 선택. 최저 비용 선택을 보장하지 않음 |
| RECAP_LLM_FALLBACK_MODEL | `minimax-m3.1-flash`.Go의 빈 content 때만1회 시도. 빈 문자열이면 비활성화, 첫 모델과 같으면 재호출 안 함 |
| RECAP_LLM_MAX_TOKENS | `1200`; 허용800..4096. Go에는 max_tokens, OpenAI에는 max_completion_tokens |
| OPENCODE_GO_API_KEY | Go Bearer 키. 환경변수가 없을 때 승인된 Aside 항목 읽기 |
| OPENAI_API_KEY | OpenAI 모드에서만 사용 |
| TYPESAFE_API_KEY | Jev 환경변수 키. 파일 폴백 없음 |

공급자/모델 변경은 서버를 재시작해 적용한다. 빈 content 대체 호출 이외의 자동 재시도는 없다. 기본 바인딩은 `127.0.0.1:8787`이다. 앱은 에뮬레이터 호스트 별칭 `10.0.2.2:8787`을 사용한다. 실제 로컬 네트워크 노출·방화벽 변경·배포 설정은 이번 범위가 아니다.

## 업스트림 근거와 미검증

- [Jev System One](https://docs.typesafe.ai/api/system-one)과 [Choice](https://docs.typesafe.ai/primitives/choice): model=jev-preview, state+questions.kind, criteria 객체, answers.kind.choice/probabilities. 선택지 분포는 정확도 지표가 아니다. 실제 반환 model을 응답에 넣는다.
- [Go 공식 문서](https://opencode.ai/v2/docs/console/go/): DeepSeek와 GLM의 OpenAI 호환 chat completions 및 고유 User-Agent/안정적인 x-opencode-session. 이 구현은 `drive-recap-proto/0.6`과 앱 프로세스 UUID를 쓴다.
- Go는 코딩 에이전트용 구독이므로 **개발·시연에만** 사용한다. 출시 공급자는 별도 검토한다.
- Claude의2026-10-23 실측: DeepSeek800토큰 기본 요청은 추론만 반환했고, reasoning_effort=low는3.9초/정상 content, MiniMax4.4초, GLM6.5초였다. 이 결과를 근거로 Go+DeepSeek 계열에만 low를 보낸다. 이는 Codex가 재호출해 측정한 결과가 아니다. GLM thinking={type:disabled}는 Claude 실측400이어서 사용하지 않는다.
- content가 비고 reasoning_content만 있으면 MiniMax로1회 대체 호출한다. 추론 텍스트는 답변으로 쓰지 않는다. 타임아웃/401/5xx/안전 검증 실패에는 대체 호출하지 않는다. 대체 모델도 비면503이다. `qwen3.8-flash`는 현재 문서상 Anthropic messages 경로이므로 이 chat 어댑터에 자동 대입하지 않는다.
- 선택형 OpenAI는 [공식 Chat Completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create) 형식을 따른다. 기본 공급자가 아니다.

서버 생성형은 **첫 모델+대체 모델 합계15초**, 앱 요약은 총20초다(분류3초 유지). 첫 모델이 대부분의 예산을 쓰면 대체 모델에 남은 시간이 짧아 폴백할 수 있다. 앱은 P 정차 시 미리 요청하고 오늘 카드 해시로 캐시한다. 복기 화면에서 템플릿/준비 중을 먼저 표시한 뒤 도착하면 교체한다. release에서 서버 주소 저장/재실행·실제 지연/캐시는 빌드별로 AVD 확인이 필요하다.

## 키 없이 시험

```powershell
node --test ai-proxy/server.test.mjs scripts/test-recap-replay.mjs
```

서버27개와 리플레이26개의 모의 시험이다. createProxy에 가짜 키/모의 fetch를 주입하며 실제 자격 파일·사용자 환경 키·외부 API를 사용하지 않는다. low 옵션/1200토큰/빈 응답 대체1회/공유 시간 예산/offset 규약/정차 코칭 예시/금지 지시 차단과 키 누출 시험을 포함한다. 정상 문장3개는 직접 작성한 합성 예시이며 실측 응답 통과 증거가 아니다. 서버 자체에는 요청·응답·키 저장 기능이 없다.

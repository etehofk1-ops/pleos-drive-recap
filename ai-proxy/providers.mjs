import { LABELS, distribution, recentPatterns, safeText } from "./contract.mjs";

export class UpstreamError extends Error {}
const unavailable = () => { throw new UpstreamError("unavailable"); };
export function createProviders(config, fetchImpl = fetch) {
  const secrets = [config.key, config.typesafeKey].filter(Boolean);
  const safeModel = model => typeof model === "string" && /^[\w.:-]{1,100}$/.test(model) &&
    !/sk[-_]/i.test(model) && !secrets.some(key => model.includes(key));
  async function jsonRequest(url, body, key, timeoutMs, signal, extra = {}, role = "recap") {
    const controller = new AbortController();
    const cancel = () => controller.abort();
    const timer = setTimeout(cancel, timeoutMs);
    signal?.addEventListener("abort", cancel, { once: true });
    if (signal?.aborted) cancel();
    try {
      const response = await fetchImpl(url, { method: body === null ? "GET" : "POST", redirect: "error",
        signal: controller.signal, headers: { "Authorization": `Bearer ${key}`, "Content-Type": "application/json", ...extra },
        ...(body === null ? {} : { body: JSON.stringify(body) }) });
      if (!response.ok) {
        if (response.status === 401) { if (role === "jev") config.typesafeKey = ""; else config.key = ""; }
        unavailable();
      }
      const reader = response.body.getReader(); let bytes = 0; const chunks = [];
      while (true) {
        const { value, done } = await reader.read(); if (done) break;
        bytes += value.length; if (bytes > 128 * 1024) { await reader.cancel(); unavailable(); }
        chunks.push(value);
      }
      return JSON.parse(Buffer.concat(chunks).toString("utf8"));
    } catch { unavailable(); }
    finally { clearTimeout(timer); signal?.removeEventListener("abort", cancel); }
  }
  return {
    async initialize() {
      // Go's default is explicit. Optional OpenAI mode discovers an available text mini/nano.
      if (config.provider !== "openai" || config.model || !config.key) return;
      try {
        const models = await jsonRequest(`${config.baseUrl}/models`, null, config.key, 8000);
        config.model = (models.data || []).map(x => x.id).filter(x => typeof x === "string" && /^gpt-/.test(x) &&
          /mini|nano/.test(x) && !/audio|image|search|realtime|transcribe/.test(x) && safeModel(x)).sort()[0] || "";
      } catch { config.model = ""; }
    },
    async classify(input, signal) {
      if (!config.typesafeKey) unavailable();
      const started = performance.now();
      // Official Choice contract uses a criteria MAP, not a string array.
      // https://docs.typesafe.ai/primitives/choice
      const output = await jsonRequest("https://api.typesafe.ai/v1/systemone", {
        model: "jev-preview", state: input, questions: { kind: { type: "choice",
          instructions: "요약된 조향 구간의 종류 하나를 고르세요. 원시 각도의 좌우 부호/도 단위는 확인되지 않았습니다. 작은 왕복은 차선 변경, 저속 큰 장시간 꺾음은 교차로 회전, 긴 단방향은 굽은 길의 단서지만 확정 근거는 아닙니다. 근거가 부족하면 판단 보류를 고르세요. 깜빡이 미사용/운전자 잘못/안전/법규를 판정하지 마세요.",
          criteria: { "차선 변경": "짧은 작은~중간 왕복 조향에 맞는 후보", "교차로 회전": "저속 큰 꺾음의 회전 후보",
            "굽은 길": "오래 유지된 단방향 조향 후보", "판단 보류": "요약만으로 구별하기 어렵거나 정보가 불충분" } } }
      }, config.typesafeKey, config.classifyTimeoutMs, signal, {}, "jev");
      try {
        const answer = output.answers?.kind; const probabilities = distribution(answer?.probabilities);
        if (answer?.type !== "choice" || !LABELS.includes(answer.choice) || probabilities[answer.choice] !== Math.max(...Object.values(probabilities)) || !safeModel(output.model)) unavailable();
        return { label: answer.choice, probabilities, model: output.model, latencyMs: Math.round(performance.now() - started) };
      } catch { unavailable(); }
    },
    async summarize(input, session, signal) {
      if (config.provider === "none" || !config.key || !safeModel(config.model)) unavailable();
      const patterns = recentPatterns(input);
      // One shared deadline, including the optional empty-content fallback. Never retry 401/5xx/timeouts.
      const deadline = performance.now() + config.summarizeTimeoutMs;
      async function generate(model) {
        const remaining = deadline - performance.now();
        if (remaining <= 0 || signal?.aborted) unavailable();
        return jsonRequest(`${config.baseUrl}/chat/completions`, {
        model, messages: [
          { role: "system", content: "정차 후 읽는 한국어 운전 복기 문장을 JSON {text,patterns}로 쓰세요. text는 비난 없는 코칭 톤의 2~3문장입니다. 숫자와 수량 표현(한글 수사 포함 횟수·거리·시간)은 쓰지 마세요. 건수와 측정값은 앱이 고정 문구로 따로 표시합니다. 안전 단정·사고 예방 보장·법률 판단·운전 중 행동 지시·다음 운전 행동 지시는 금지합니다. 정차한 지금 기록을 살펴보는 제안만 하세요. UNSURE를 사실로 단정하지 마세요. 패턴은 allowedPatterns에서만 고르고 데이터가 부족하면 []입니다. 추론과 설명을 출력하지 마세요." },
          { role: "user", content: JSON.stringify({ ...input, allowedPatterns: patterns }) }
        ], ...(config.provider === "openai" ? { max_completion_tokens: config.maxTokens } : { max_tokens: config.maxTokens }),
        // Claude measured this option on Go/DeepSeek (2026-10-23); never send it to other models.
        ...(config.provider === "opencode-go" && /^deepseek(?:[-.]|$)/i.test(model) ? { reasoning_effort: "low" } : {})
      }, config.key, remaining, signal, config.provider === "opencode-go" ? {
        "User-Agent": "drive-recap-proto/0.6", "x-opencode-session": session
      } : {});
      }
      let output = await generate(config.model);
      const initialContent = output.choices?.[0]?.message?.content;
      if ((initialContent == null || (typeof initialContent === "string" && !initialContent.trim())) &&
          config.provider === "opencode-go" && safeModel(config.fallbackModel) && config.fallbackModel !== config.model) {
        output = await generate(config.fallbackModel);
      }
      try {
        const content = output.choices?.[0]?.message?.content;
        if (typeof content !== "string" || !content.trim() || content.length > 4000) unavailable();
        // reasoning_content is deliberately ignored; it is never a user-facing answer.
        const clean = content.trim().replace(/^```(?:json)?\s*([\s\S]*?)\s*```$/, "$1");
        let parsed;
        try { parsed = JSON.parse(clean); } catch { parsed = { text: clean, patterns: [] }; }
        if (!safeText(parsed.text, input, secrets) || !Array.isArray(parsed.patterns) || !safeModel(output.model)) unavailable();
        return { text: parsed.text, patterns: patterns.filter(p => parsed.patterns.includes(p)), model: output.model };
      } catch { unavailable(); }
    }
  };
}

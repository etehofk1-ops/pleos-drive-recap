import test from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { loadConfig, startupStatus } from "./config.mjs";
import { createProxy } from "./server.mjs";
import { LABELS, recentPatterns, safeText } from "./contract.mjs";

const sample = () => ({ durationMs: 2450, peakAbsRaw: 20, signReversals: 1, speedKphMin: 25, speedKphMax: 35,
  indicator: { state: "none", offsetMs: null }, naviHint: "unknown" });
const summary = () => ({ today: [{ kind: "LANE_NO_SIGNAL", hourBucket: 18, speedBand: "mid", indicator: "none" }], recentDays: [] });
const text = "오늘 기록에서 깜빡이 사용과 감속 구간을 살펴봤어요. 정차한 지금 마음에 남은 장면을 차분히 확인해 보세요.";
const config = () => ({ provider: "opencode-go", key: "fixture-go-" + randomUUID(), typesafeKey: "fixture-jev-" + randomUUID(),
  baseUrl: "https://opencode.ai/inference/go/openai/v1", model: "deepseek-v4.1-flash", fallbackModel: "minimax-m3.1-flash", maxTokens: 1200,
  classifyTimeoutMs: 50, summarizeTimeoutMs: 50 });
const classification = () => ({ model: "jev-preview", answers: { kind: { type: "choice", choice: LABELS[0], confidence: 0.7,
  probabilities: Object.fromEntries(LABELS.map((label, i) => [label, i === 0 ? 0.85 : 0.05])) } } });
async function fixture(t, upstream, cfg = config()) {
  const logs = [], calls = [];
  const proxy = createProxy(cfg, { fetchImpl: async (url, options) => { calls.push({ url, options }); return upstream(url, options); }, log: e => logs.push(e) });
  await new Promise(resolve => proxy.server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise(resolve => { proxy.server.closeAllConnections(); proxy.server.close(resolve); }));
  const address = `http://127.0.0.1:${proxy.server.address().port}`;
  const session = randomUUID();
  const post = (path, body, extra = {}) => fetch(address + path, { method: "POST", headers: { "content-type": "application/json", "x-recap-session": session, ...extra }, body: JSON.stringify(body) });
  return { post, session, calls, logs, cfg, address };
}
test("Go defaults and environment key precedence never read the credential file", async () => {
  const key = randomUUID();
  const cfg = await loadConfig({ OPENCODE_GO_API_KEY: key }, async () => assert.fail("must not read file"));
  assert.equal(cfg.provider, "opencode-go"); assert.equal(cfg.model, "deepseek-v4.1-flash"); assert.equal(cfg.key, key);
  assert.equal(cfg.summarizeTimeoutMs, 15000); assert.equal(cfg.maxTokens, 1200);
  assert.equal(cfg.fallbackModel, "minimax-m3.1-flash"); assert.equal(cfg.credentialsPath, null);
  assert.ok(!JSON.stringify(startupStatus(cfg)).includes(key));
});
test("authorized Aside entry is read in memory only; wrong type or missing file leaves key absent", async () => {
  const key = randomUUID(); let seen;
  const cfg = await loadConfig({}, async path => { seen = path; return JSON.stringify({ "opencode-go": { type: "api_key", key } }); });
  assert.ok(seen.endsWith("credentials.json")); assert.equal(cfg.key, key);
  assert.ok(!JSON.stringify(startupStatus(cfg)).includes(key));
  assert.equal((await loadConfig({}, async () => { throw new Error(key); })).key, "");
  assert.equal((await loadConfig({}, async () => JSON.stringify({ "opencode-go": { type: "oauth", key } }))).key, "");
});
test("none and openai do not access Aside; insecure custom base is rejected", async () => {
  for (const provider of ["none", "openai"]) await loadConfig({ RECAP_LLM_PROVIDER: provider }, async () => assert.fail("unexpected read"));
  await assert.rejects(loadConfig({ RECAP_LLM_BASE_URL: "http://example.com", OPENCODE_GO_API_KEY: "fixture" }));
});
test("Jev sends the official choice-map payload and normalized response", async t => {
  const f = await fixture(t, async (_, req) => {
    const body = JSON.parse(req.body); assert.equal(body.model, "jev-preview");
    assert.equal(body.questions.kind.type, "choice"); assert.equal(Array.isArray(body.questions.kind.criteria), false);
    assert.deepEqual(Object.keys(body.questions.kind.criteria), LABELS); assert.deepEqual(body.state, sample());
    return Response.json(classification());
  });
  const res = await f.post("/classify", sample()); assert.equal(res.status, 200);
  const result = await res.json(); assert.equal(result.label, LABELS[0]); assert.ok(result.latencyMs >= 0);
  assert.equal(f.calls.length, 1);
});
test("Go sends unique User-Agent, stable session and 1200 token budget with DeepSeek low reasoning", async t => {
  const f = await fixture(t, async (_, req) => {
    assert.equal(req.headers["User-Agent"], "drive-recap-proto/0.6"); assert.equal(req.headers["x-opencode-session"], f.session);
    const body = JSON.parse(req.body); assert.equal(body.max_tokens, 1200); assert.equal(body.model, "deepseek-v4.1-flash");
    assert.equal(body.tools, undefined); assert.equal(body.reasoning_effort, "low");
    return Response.json({ model: body.model, choices: [{ message: { content: JSON.stringify({ text, patterns: [] }), reasoning_content: "NEVER RETURN THIS" } }] });
  });
  for (let i = 0; i < 2; i++) { const res = await f.post("/summarize", summary()); assert.equal(res.status, 200); assert.deepEqual(await res.json(), { text, patterns: [], model: f.cfg.model }); }
  assert.equal(f.calls.length, 2);
});
test("empty content with reasoning only returns 503, never reasoning", async t => {
  const f = await fixture(t, async () => Response.json({ model: "deepseek-v4.1-flash", choices: [{ message: { content: "", reasoning_content: text } }] }));
  const res = await f.post("/summarize", summary()); assert.equal(res.status, 503); assert.deepEqual(await res.json(), { error: "unavailable" });
  assert.equal(f.calls.length, 2); // Primary and one fallback both empty; never loop.
});
test("missing key or none provider returns 503 with no upstream call", async t => {
  for (const cfg of [{ ...config(), key: "", typesafeKey: "" }, { ...config(), provider: "none" }]) {
    const f = await fixture(t, () => assert.fail("unexpected upstream"), cfg);
    assert.equal((await f.post("/summarize", summary())).status, 503);
    if (!cfg.typesafeKey) assert.equal((await f.post("/classify", sample())).status, 503);
  }
});
test("401 disables bad key until restart and does not retry", async t => {
  const f = await fixture(t, async () => new Response("private error", { status: 401 }));
  assert.equal((await f.post("/summarize", summary())).status, 503);
  assert.equal((await f.post("/summarize", summary())).status, 503); assert.equal(f.calls.length, 1);
});
test("5xx is 503 without upstream body or retry", async t => {
  const f = await fixture(t, async () => new Response(f.cfg.key, { status: 500 }));
  const res = await f.post("/summarize", summary()); assert.equal(res.status, 503);
  assert.ok(!(await res.text()).includes(f.cfg.key)); assert.equal(f.calls.length, 1);
});
test("upstream timeout is bounded and aborted with no retry", async t => {
  let aborted = false;
  const f = await fixture(t, (_, options) => new Promise((_, reject) => options.signal.addEventListener("abort", () => { aborted = true; reject(new Error("secret timeout")); })));
  const res = await f.post("/summarize", summary()); assert.equal(res.status, 503); assert.equal(aborted, true); assert.equal(f.calls.length, 1);
});
test("client disconnect cancels pending generation", async t => {
  let observed; const done = new Promise(resolve => { observed = resolve; });
  const f = await fixture(t, (_, req) => new Promise((_, reject) => req.signal.addEventListener("abort", () => { observed(); reject(new Error("cancelled")); })), { ...config(), summarizeTimeoutMs: 8000 });
  const controller = new AbortController();
  const request = fetch(f.address + "/summarize", { method: "POST", headers: { "content-type": "application/json", "x-recap-session": f.session }, body: JSON.stringify(summary()), signal: controller.signal });
  while (!f.calls.length) await new Promise(resolve => setTimeout(resolve, 5));
  controller.abort(); await assert.rejects(request); await done;
});
test("closed schemas reject VIN, location, raw arrays, actual dates and bad enums before upstream", async t => {
  const f = await fixture(t, () => assert.fail("must validate first"));
  for (const input of [{ ...sample(), VIN: "forbidden" }, { ...sample(), rawEvents: [] }, { ...sample(), latitude: 0 }, { ...sample(), indicator: { state: "unknown", offsetMs: null } }])
    assert.equal((await f.post("/classify", input)).status, 400);
  assert.equal((await f.post("/summarize", { ...summary(), recentDays: [{ date: "2026-10-23", counts: { missing: 0, late: 0, good: 0, hardBrake: 0 } }] })).status, 400);
});
test("missing session and cross-origin browser requests never invoke upstream", async t => {
  const f = await fixture(t, () => assert.fail("must reject"));
  assert.equal((await f.post("/summarize", summary(), { "x-recap-session": "" })).status, 400);
  assert.equal((await f.post("/summarize", summary(), { Origin: "http://evil.invalid" })).status, 403);
});
test("key leaks in model/content/errors never reach response or event logs", async t => {
  for (const where of ["text", "model", "error"]) {
    const f = await fixture(t, async () => {
      if (where === "error") throw new Error(f.cfg.typesafeKey + f.cfg.key);
      return Response.json({ model: where === "model" ? f.cfg.key : "deepseek-v4.1-flash", choices: [{ message: { content: JSON.stringify({ text: where === "text" ? text + f.cfg.key : text, patterns: [] }) } }] });
    });
    const res = await f.post("/summarize", summary()); const output = await res.text();
    assert.equal(res.status, 503);
    for (const key of [f.cfg.key, f.cfg.typesafeKey]) { assert.ok(!output.includes(key)); assert.ok(!JSON.stringify(f.logs).includes(key)); }
  }
});
test("invented numbers, safety/legal claims and driving commands are rejected", async t => {
  for (const bad of ["오늘 99회 급제동했어요. 정차 후 확인해 보세요.", "이 운전은 안전합니다. 정차 후 확인해 보세요.", "오늘 위법 운전이었어요. 정차 후 확인해 보세요.", "주행 중 차선을 바꾸세요. 지금 핸들을 돌리세요."]) {
    const f = await fixture(t, async () => Response.json({ model: "deepseek-v4.1-flash", choices: [{ message: { content: JSON.stringify({ text: bad, patterns: [] }) } }] }));
    assert.equal((await f.post("/summarize", summary())).status, 503);
  }
});
test("recent patterns require two observed days and cannot be invented by generation", async t => {
  assert.deepEqual(recentPatterns(summary()), []);
  const input = summary(); input.recentDays = [1, 2].map(n => ({ date: `D-${n}`, counts: { missing: 0, late: 0, good: 0, hardBrake: 0 } }));
  const patterns = recentPatterns(input); assert.equal(patterns.length, 1);
  const f = await fixture(t, async () => Response.json({ model: "deepseek-v4.1-flash", choices: [{ message: { content: JSON.stringify({ text, patterns: [...patterns, "invented trend"] }) } }] }));
  const res = await f.post("/summarize", input); assert.equal(res.status, 200); assert.deepEqual((await res.json()).patterns, patterns);
});
test("invalid Jev distribution or answer shape is unavailable", async t => {
  for (const output of [{ ...classification(), model: "invalid key model" }, { model: "jev-preview", answers: { kind: { type: "choice", choice: "차선 변경", probabilities: {} } } }]) {
    const f = await fixture(t, async () => Response.json(output)); assert.equal((await f.post("/classify", sample())).status, 503);
  }
});
test("optional OpenAI mode selects an available text mini and uses chat independently", async t => {
  const cfg = { ...config(), provider: "openai", baseUrl: "https://api.openai.com/v1", model: "" };
  const proxy = createProxy(cfg, { fetchImpl: async (url, req) => {
    if (req.method === "GET") { assert.ok(url.endsWith("/models")); return Response.json({ data: [{ id: "image-mini" }, { id: "gpt-fixture-mini" }] }); }
    assert.equal(JSON.parse(req.body).max_completion_tokens, 1200); assert.equal(req.headers["x-opencode-session"], undefined);
    return Response.json({ model: cfg.model, choices: [{ message: { content: JSON.stringify({ text, patterns: [] }) } }] });
  } });
  await proxy.initialize(); assert.equal(cfg.model, "gpt-fixture-mini");
  await new Promise(resolve => proxy.server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise(resolve => { proxy.server.closeAllConnections(); proxy.server.close(resolve); }));
  const res = await fetch(`http://127.0.0.1:${proxy.server.address().port}/summarize`, { method: "POST", headers: { "Content-Type": "application/json", "x-recap-session": randomUUID() }, body: JSON.stringify(summary()) });
  assert.equal(res.status, 200);
});
test("Jev 401 disables only the Jev key even when key strings happen to match", async t => {
  const cfg = config(); cfg.typesafeKey = cfg.key;
  const f = await fixture(t, async url => url.includes("typesafe") ? new Response("", { status: 401 }) :
    Response.json({ model: cfg.model, choices: [{ message: { content: JSON.stringify({ text, patterns: [] }) } }] }), cfg);
  assert.equal((await f.post("/classify", sample())).status, 503);
  assert.equal((await f.post("/summarize", summary())).status, 200);
  assert.equal(cfg.typesafeKey, ""); assert.ok(cfg.key);
});

test("empty Go content falls back once with the same session and no DeepSeek option on MiniMax", async t => {
  const f = await fixture(t, async (_, req) => {
    const body = JSON.parse(req.body);
    assert.equal(req.headers["x-opencode-session"], f.session);
    if (body.model === "deepseek-v4.1-flash") {
      assert.equal(body.reasoning_effort, "low");
      return Response.json({ model: body.model, choices: [{ finish_reason: "length", message: { content: "", reasoning_content: "private reasoning" } }] });
    }
    assert.equal(body.model, "minimax-m3.1-flash"); assert.equal(body.reasoning_effort, undefined); assert.equal(body.thinking, undefined);
    return Response.json({ model: body.model, choices: [{ message: { content: JSON.stringify({ text, patterns: [] }) } }] });
  });
  const res = await f.post("/summarize", summary()); assert.equal(res.status, 200);
  assert.equal((await res.json()).model, "minimax-m3.1-flash"); assert.equal(f.calls.length, 2);
});

test("nonempty invalid prose, 5xx, and configured identical/disabled fallback never retry", async t => {
  for (const mode of ["invalid", "5xx", "same", "disabled"]) {
    const cfg = config();
    if (mode === "same") cfg.fallbackModel = cfg.model;
    if (mode === "disabled") cfg.fallbackModel = "";
    const f = await fixture(t, async () => mode === "5xx" ? new Response("private", { status: 500 }) :
      Response.json({ model: cfg.model, choices: [{ message: { content: mode === "invalid" ? "invalid prose" : "" } }] }), cfg);
    assert.equal((await f.post("/summarize", summary())).status, 503); assert.equal(f.calls.length, 1);
  }
});

test("other Go model and optional OpenAI never receive DeepSeek reasoning parameters", async t => {
  for (const [provider, model] of [["opencode-go", "glm-5.3-flash"], ["openai", "gpt-fixture-mini"]]) {
    const cfg = { ...config(), provider, model };
    const f = await fixture(t, async (_, req) => {
      const body = JSON.parse(req.body); assert.equal(body.reasoning_effort, undefined); assert.equal(body.thinking, undefined);
      return Response.json({ model, choices: [{ message: { content: JSON.stringify({ text, patterns: [] }) } }] });
    }, cfg);
    assert.equal((await f.post("/summarize", summary())).status, 200);
  }
});

test("fallback shares total timeout budget rather than getting a second full deadline", async t => {
  let fallbackElapsed = 0;
  const cfg = { ...config(), summarizeTimeoutMs: 500 };
  const f = await fixture(t, async (_, req) => {
    if (JSON.parse(req.body).model === cfg.model) {
      await new Promise(resolve => setTimeout(resolve, 300));
      return Response.json({ model: cfg.model, choices: [{ message: { content: "" } }] });
    }
    const at = performance.now();
    return new Promise((_, reject) => req.signal.addEventListener("abort", () => {
      fallbackElapsed = performance.now() - at; reject(new Error("timeout"));
    }));
  }, cfg);
  assert.equal((await f.post("/summarize", summary())).status, 503);
  assert.equal(f.calls.length, 2); assert.ok(fallbackElapsed < 400);
});

test("before and none use null offsets; negative before offset is explicitly rejected", async t => {
  const f = await fixture(t, async () => Response.json(classification()));
  for (const indicator of [{ state: "before", offsetMs: null }, { state: "none", offsetMs: null }, { state: "late", offsetMs: 662 }]) {
    assert.equal((await f.post("/classify", { ...sample(), indicator })).status, 200);
  }
  for (const indicator of [{ state: "before", offsetMs: -1500 }, { state: "late", offsetMs: -1 }, { state: "late", offsetMs: 3000 }]) {
    assert.equal((await f.post("/classify", { ...sample(), indicator })).status, 400);
  }
  assert.equal(f.calls.length, 3);
});

test("three synthetic stationary coaching examples pass without being labelled measured model output", () => {
  // Claude's measured response texts were not supplied. These are authored examples, not live responses.
  for (const good of [
    "오늘 기록에는 깜빡이 사용을 돌아볼 장면이 담겼어요. 정차한 지금 해당 구간을 한 번 살펴보세요.",
    "오늘 모인 후보를 차분히 돌아볼 수 있어요. 차를 세운 지금 깜빡이 사용과 감속 근거를 함께 확인해 보세요.",
    "기록에서 짚어볼 순간을 모아 봤어요. 정차한 뒤 표시된 수치를 천천히 살펴보면 좋겠어요."
  ]) assert.equal(safeText(good, summary()), true, good);
});

test("stationary wording exception never permits on-road commands or fabricated counts", () => {
  for (const bad of ["운전 중 한 번 살펴보세요. 지금 차선을 바꾸세요.", "주행 중 깜빡이를 켜세요. 핸들을 확인하세요.",
    "오늘 두 번 급제동했어요. 정차한 지금 살펴보세요.", "오늘 한 번 차선을 바꿨어요. 정차 후 확인해 보세요.",
    "오늘 한 번 돌아서 회전했어요. 정차 후 확인해 보세요."]) {
    assert.equal(safeText(bad, summary()), false, bad);
  }
});

test("fallback model is configurable and validated without logging keys", async () => {
  const cfg = await loadConfig({ OPENCODE_GO_API_KEY: "fixture", RECAP_LLM_FALLBACK_MODEL: "glm-5.3-flash" });
  assert.equal(cfg.fallbackModel, "glm-5.3-flash");
  assert.equal((await loadConfig({ OPENCODE_GO_API_KEY: "fixture", RECAP_LLM_FALLBACK_MODEL: "" })).fallbackModel, "");
  await assert.rejects(loadConfig({ OPENCODE_GO_API_KEY: "fixture", RECAP_LLM_FALLBACK_MODEL: "sk-secret" }));
});

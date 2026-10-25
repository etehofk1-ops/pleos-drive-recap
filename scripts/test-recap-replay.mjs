import { readFileSync } from "node:fs";
import vm from "node:vm";
import test from "node:test";
import assert from "node:assert/strict";

const read = name => readFileSync(new URL(name, import.meta.url), "utf8").replace(/^\uFEFF/, "");
const source = read("./recap-replay.js");
const scenarios = ["a", "b", "c", "d-demo"].map(name => JSON.parse(read("./recap-scenario-" + name + ".json")));
// Claude's measured D uses coasting (brake=0) for 60..1 and brake=10 at rest.
// Identify the same 61-command ramp by its distinctive 59 kph step, not an obsolete pedal value.
const gentleRamp = writes => {
  const controls = writes.filter(x => x.path === "/vehicle/controls");
  const first = controls.findIndex(x => x.query.speed === "59" && x.query.brake === "0");
  assert.ok(first > 0);
  return controls.slice(first - 1, first + 60);
};

// Offline HTTP fixture. This is not live server/SDK/vehicle validation.
function surface(options = {}) {
  let clock = 0, scheduled = false, serial = 0, reads = 0;
  const timers = new Map(), writes = [], blocked = [], messages = [], warnings = [];
  const state = { speed: 0, steering: 0, brake: 0, gear: "Park", signal: "Off" };
  const pump = () => {
    if (scheduled || !timers.size) return;
    scheduled = true;
    setImmediate(() => {
      scheduled = false;
      const entry = [...timers].sort((a, b) => a[1].due - b[1].due)[0];
      if (!entry) return;
      timers.delete(entry[0]); clock = Math.max(clock, entry[1].due); entry[1].callback(); pump();
    });
  };
  const catalog = { features: [{ id: "turn-signal-state", write_endpoint: options.wrongCatalog ? "/unknown" : "/vehicle/turn-signal",
    control: { field: "mode", options: ["Off", "BlinkLeft", "BlinkRight"].map(value => ({ value })) } }] };
  let context;
  const transport = async (path, init = {}) => {
    const url = new URL(path, "http://127.0.0.1:8088/");
    const method = init.method || "GET";
    if (method === "GET") {
      if (url.pathname === "/vehicle/catalog") return Response.json(catalog);
      assert.equal(url.pathname, "/vehicle/state");
      const speed = options.readSpeed ? options.readSpeed(++reads, state.speed) : state.speed;
      return Response.json({ controls: { speed_kph: speed }, gear: { actual: { value: state.gear } } });
    }
    writes.push({ path: url.pathname, query: Object.fromEntries(url.searchParams), method, at: clock });
    clock += options.writeLatencyMs || 0;
    if (options.fail?.(url, writes)) return new Response("fixture failure", { status: 500 });
    assert.equal(method, "POST");
    if (url.pathname === "/vehicle/controls") {
      if (url.searchParams.has("speed") && !Number.isInteger(Number(url.searchParams.get("speed"))))
        return new Response("integer speed required", { status: 400 });
      for (const key of ["speed", "steering", "brake"]) if (url.searchParams.has(key)) state[key] = Number(url.searchParams.get(key));
      if (options.interference) blocked.push((await context.fetch("/vehicle/controls?speed=0", { method: "PUT" })).status);
    } else if (url.pathname === "/vehicle/gear") state.gear = url.searchParams.get("gear");
    else if (url.pathname === "/vehicle/turn-signal") state.signal = url.searchParams.get("mode");
    else assert.fail("Unknown endpoint " + url.pathname);
    return Response.json({ ok: true });
  };
  const location = { hostname: options.host || "127.0.0.1", port: "8088", href: "http://127.0.0.1:8088/", origin: "http://127.0.0.1:8088" };
  context = {
    location, window: {}, URL, URLSearchParams, Response, AbortController, fetch: transport,
    console: { table() {}, info(...args) { messages.push(args); }, warn(...args) { warnings.push(args); }, error() {} }, performance: { now: () => clock },
    setTimeout(callback, delay) { const id = ++serial; timers.set(id, { due: clock + Math.max(0, delay), callback }); pump(); return id; },
    clearTimeout(id) { timers.delete(id); }
  };
  vm.runInNewContext(source, context, { filename: "recap-replay.js" });
  return { api: context.window.driveRecapReplay, writes, state, blocked, messages, warnings, context, transport };
}

test("preflight maps kph directly to HTTP speed; no cruise or DOM dependency", () => {
  const f = surface(), plan = f.api.preflight(scenarios[0]);
  assert.equal(plan.requiresMissingSpeedDemo, false);
  assert.ok(plan.steps.some(s => s.actions.some(path => path.startsWith("/vehicle/controls?") && new URL(path, "http://127.0.0.1:8088").searchParams.get("speed") === "60")));
  assert.ok(plan.steps.some(s => s.actions.includes("/vehicle/turn-signal?mode=BlinkLeft")));
});
test("dry-run does not write or replace fetch", async () => {
  const f = surface(); await f.api.run(scenarios[0], { dryRun: true });
  assert.equal(f.writes.length, 0); assert.equal(f.context.fetch, f.transport);
});
test("scenario A posts all controls and restores neutral, zero, Off and Park", async () => {
  const f = surface(); const result = await f.api.run(scenarios[0]);
  assert.equal(result.completed, true); assert.equal(f.api.running, false);
  assert.deepEqual(f.state, { speed: 0, steering: 0, brake: 0, gear: "Park", signal: "Off" });
  assert.ok(f.writes.some(x => x.query.brake === "90"));
  assert.ok(f.writes.every(x => x.method === "POST"));
  assert.equal(f.context.fetch, f.transport);
});
test("scenario B holds the curve for at least eight seconds", async () => {
  const f = surface(); await f.api.run(scenarios[1]);
  const start = f.writes.find(x => x.query.steering === "18");
  const end = f.writes.find(x => x.query.steering === "0" && x.at > start.at);
  assert.ok(end.at - start.at >= 8_000);
});
test("arbitrary control, invalid value, speed gate and unordered time fail before writes", () => {
  const f = surface();
  for (const scenario of [
    { steps: [{ atMs: 0, controls: { steering: 1000 } }] },
    { steps: [{ atMs: 0, controls: { vin: "forbidden" } }] },
    { steps: [{ atMs: 0, controls: { speedKph: NaN } }] },
    { steps: [{ atMs: 0, waitForSpeedKph: { min: 10, max: 0, timeoutMs: 1000 } }] },
    { steps: [{ atMs: 2 }, { atMs: 1 }] }, { steps: [{ atMs: -1 }] }
  ]) assert.throws(() => f.api.preflight(scenario));
  assert.equal(f.writes.length, 0);
});
test("changed turn-signal catalog is rejected without mutation", async () => {
  const f = surface({ wrongCatalog: true });
  await assert.rejects(f.api.run(scenarios[0]), /카탈로그/);
  assert.equal(f.writes.length, 0); assert.equal(f.context.fetch, f.transport);
});
test("speed settling shifts timeline and preserves steering segment spacing", async () => {
  const f = surface({ readSpeed: (n, speed) => n <= 3 ? 0 : speed });
  await f.api.run(scenarios[0]);
  const drive = f.writes.find(x => x.query.gear === "Drive");
  const steer = f.writes.find(x => x.query.steering === "12");
  assert.ok(steer.at - drive.at >= 2_400);
});
test("missing server speed aborts instead of inventing movement", async () => {
  const f = surface({ readSpeed: () => null });
  await assert.rejects(f.api.run(scenarios[0]), /미수신/);
  assert.equal(f.state.gear, "Park"); assert.equal(f.context.fetch, f.transport);
});
test("unreachable speed times out and resets", async () => {
  const f = surface({ readSpeed: () => 0 });
  await assert.rejects(f.api.run({ steps: [{ atMs: 0, waitForSpeedKph: { min: 50, max: 55, timeoutMs: 400 }, controls: { gear: "D", speedKph: 54 } }] }), /도달/);
  assert.equal(f.state.gear, "Park"); assert.equal(f.context.fetch, f.transport);
});
test("Console physics writes are isolated and fetch is restored", async () => {
  const f = surface({ interference: true }); await f.api.run(scenarios[0]);
  assert.ok(f.blocked.length > 0); assert.ok(f.blocked.every(code => code === 423));
  assert.equal(f.context.fetch, f.transport);
});
test("verified Park state resynchronizes page physics before releasing the write guard", async () => {
  const f = surface(), order = [];
  f.context.syncControls = controls => { assert.equal(controls.speed_kph, 0); assert.notEqual(f.context.fetch, f.transport); order.push("controls"); };
  f.context.renderGear = gear => { assert.equal(gear.actual.value, "Park"); order.push("gear"); };
  f.context.renderAll = () => order.push("render");
  await f.api.run(scenarios[0]);
  assert.deepEqual(order, ["controls", "gear", "render"]);
  assert.equal(f.context.fetch, f.transport);
});
test("write failure resets and restores fetch", async () => {
  let failed = false;
  const f = surface({ fail: url => !failed && url.searchParams.get("steering") === "12" ? (failed = true) : false });
  await assert.rejects(f.api.run(scenarios[0]), /HTTP 500/);
  assert.equal(f.state.gear, "Park"); assert.equal(f.context.fetch, f.transport);
});
test("stop interrupts pending timeline and waits for reset", async () => {
  const f = surface(); const active = f.api.run(scenarios[0]);
  // Allow catalog verification and isolation, then interrupt its first wait.
  await new Promise(resolve => setImmediate(resolve));
  await f.api.stop(); const result = await active;
  assert.equal(result.completed, false); assert.equal(f.state.gear, "Park"); assert.equal(f.api.running, false);
  assert.equal(f.context.fetch, f.transport);
});
test("unrelated host is rejected", () => assert.throws(() => surface({ host: "example.com" })));

test("every controls write carries held speed, steering and brake", async () => {
  const f = surface(); await f.api.run(scenarios[0]);
  const writes = f.writes.filter(x => x.path === "/vehicle/controls");
  assert.ok(writes.length > 0);
  assert.ok(writes.every(x => ["speed", "steering", "brake"].every(key => Object.hasOwn(x.query, key))));
  assert.ok(scenarios[0].steps.every(step => Number.isFinite(step.controls.speedKph)));
});
test("measured steering cadence and pre/late signal timing are preserved", async () => {
  const f = surface(); await f.api.run(scenarios[0]);
  const left = f.writes.filter(x => x.query.mode === "BlinkLeft");
  const first = f.writes.find(x => x.query.steering === "8");
  const second = f.writes.find(x => x.query.steering === "15");
  const late = f.writes.find(x => x.query.steering === "7");
  assert.equal(first.at - left[0].at, 2000);
  assert.equal(second.at - first.at, 350);
  assert.equal(left[1].at - late.at, 600);
});
test("staged hard brake holds 90 with 450ms steps and nominal 9.3mps2 until stopped", async () => {
  const f = surface(); await f.api.run(scenarios[0]);
  const presses = f.writes.filter(x => x.query.brake === "90");
  assert.deepEqual(presses.slice(0, 5).map(x => x.query.speed), ["60", "45", "30", "15", "0"]);
  for (let i = 1; i < 5; i++) {
    assert.equal(presses[i].at - presses[i - 1].at, 450);
    const decel = (Number(presses[i - 1].query.speed) - Number(presses[i].query.speed)) / 3.6 / 0.45;
    assert.ok(decel >= 8 && decel <= 10);
  }
  const release = f.writes.find(x => x.query.brake === "0" && x.at > presses[0].at);
  assert.ok(release.at - presses[0].at >= 1800);
  assert.ok(presses.slice(5).length >= 2); // Stop gate reasserts the held pedal.
  assert.ok(presses.slice(5).every(x => x.query.speed === "0"));
  assert.ok(f.writes.filter(x => x.path === "/vehicle/controls" && x.at >= presses[0].at && x.at < release.at)
    .every(x => x.query.brake === "90"));
});
test("scenario C uses two boundary-speed maneuvers and restores Park", async () => {
  const f = surface(); const result = await f.api.run(scenarios[2]);
  assert.equal(result.completed, true); assert.equal(f.state.gear, "Park");
  assert.ok(f.writes.some(x => x.query.speed === "25" && x.query.steering === "18"));
  assert.ok(f.writes.some(x => x.query.speed === "35" && x.query.steering === "-32"));
});

test("demo D covers 74 seconds and every step carries a Korean single-line caption", () => {
  const d = scenarios[3], plan = surface().api.preflight(d);
  assert.equal(d.steps[0].atMs, 0); assert.equal(d.steps.at(-1).atMs, 74000);
  assert.equal(d.steps[0].controls.gear, "P"); assert.equal(d.steps.at(-1).controls.gear, "P");
  assert.ok(d.steps.every(s => typeof s.caption === "string" && /[가-힣]/.test(s.caption) && !/[\r\n]/.test(s.caption)));
  assert.deepEqual(Array.from(plan.steps, s => s.caption), d.steps.map(s => s.caption));
});
test("demo captions are logged once per step and never enter the HTTP contract", async () => {
  const f = surface(), d = scenarios[3]; await f.api.run(d);
  const captions = f.messages.filter(args => args[0] === "recap caption");
  assert.deepEqual(captions.map(args => args.slice(1)), d.steps.map(s => [s.atMs, s.caption]));
  assert.ok(f.writes.every(x => !Object.hasOwn(x.query, "caption")));
  assert.equal(f.state.gear, "Park"); assert.equal(f.context.fetch, f.transport);
});
test("demo D separates gentle signal-stop from one staged hard-brake episode", async () => {
  const f = surface(); await f.api.run(scenarios[3]);
  const gentle = gentleRamp(f.writes);
  assert.equal(gentle.length, 61); assert.equal(gentle[0].query.speed, "60"); assert.equal(gentle.at(-1).query.speed, "0");
  assert.ok(gentle.slice(0, 60).every(x => x.query.brake === "0")); assert.equal(gentle.at(-1).query.brake, "10");
  for (let i = 1; i < gentle.length; i++) {
    assert.equal(gentle[i].at - gentle[i - 1].at, 250);
    assert.equal(Number(gentle[i - 1].query.speed) - Number(gentle[i].query.speed), 1);
    const decel = (Number(gentle[i - 1].query.speed) - Number(gentle[i].query.speed)) / 3.6 / 0.25;
    assert.ok(decel > 0 && decel <= 2);
  }
  const hard = f.writes.filter(x => x.query.brake === "90");
  assert.deepEqual(hard.slice(0, 16).map(x => Number(x.query.speed)), Array.from({ length: 16 }, (_, i) => 60 - i * 3));
  for (let i = 1; i < 16; i++) {
    assert.equal(hard[i].at - hard[i - 1].at, 100);
    assert.ok(3 / 3.6 / ((hard[i].at - hard[i - 1].at) / 1000) >= 8);
  }
  const tail = hard.slice(15, 31);
  assert.deepEqual(tail.map(x => Number(x.query.speed)), Array.from({ length: 16 }, (_, i) => 15 - i));
  for (let i = 1; i < tail.length; i++) assert.equal(tail[i].at - tail[i - 1].at, 250);
  for (let i = 5; i <= 15; i += 5) assert.ok(tail[i].at - tail[i - 5].at >= 1000);
  const pedalTransitions = f.writes.filter(x => x.path === "/vehicle/controls").map(x => x.query.brake)
    .filter((value, i, all) => !i || value !== all[i - 1]);
  assert.equal(pedalTransitions.filter(value => value === "90").length, 1);
});
test("bad captions fail preflight without writes", () => {
  const f = surface();
  for (const caption of [17, "", " ", "첫 줄\n다음 줄", "가".repeat(121)])
    assert.throws(() => f.api.preflight({ steps: [{ atMs: 0, controls: {}, caption }] }), /자막/);
  assert.equal(f.writes.length, 0);
});

test("fractional speed is normalized in preflight including held controls without mutating the input", () => {
  const f = surface(), scenario = { steps: [{ atMs: 0, controls: { speedKph: 59.25 } },
    { atMs: 250, controls: { steering: 0.5 } }, { atMs: 500, controls: { speedKph: 59.5 } }] };
  const original = JSON.stringify(scenario), plan = f.api.preflight(scenario);
  assert.deepEqual(Array.from(plan.steps, s => s.controls.speedKph), [59, 59, 60]);
  assert.deepEqual(JSON.parse(JSON.stringify(plan.speedAdjustments)), [
    { atMs: 0, fromKph: 59.25, toKph: 59 }, { atMs: 500, fromKph: 59.5, toKph: 60 }]);
  assert.ok(plan.steps.every(s => s.actions.some(path => /speed=(59|60)(&|$)/.test(path))));
  assert.equal(JSON.stringify(scenario), original); assert.equal(f.writes.length, 0);
});
test("runtime warns about rounded speed and never sends a fraction to the integer-only server", async () => {
  const f = surface(); await f.api.run({ steps: [{ atMs: 0, controls: { gear: "D", speedKph: 59.25 } }] });
  assert.ok(f.warnings.some(args => args[0].includes("정수 속도")));
  assert.equal(f.writes.find(x => x.path === "/vehicle/controls").query.speed, "59");
  assert.ok(f.writes.filter(x => x.path === "/vehicle/controls").every(x => /^\d+$/.test(x.query.speed)));
});
test("rounding never legitimizes nonfinite or originally out-of-range speeds", () => {
  const f = surface();
  for (const speedKph of [-0.1, 240.1, NaN, Infinity])
    assert.throws(() => f.api.preflight({ steps: [{ atMs: 0, controls: { speedKph } }] }), /범위/);
  assert.equal(f.writes.length, 0);
});
test("demo ramp commands stay integer and spaced even when HTTP writes take 80ms", async () => {
  const f = surface({ writeLatencyMs: 80 }); await f.api.run(scenarios[3]);
  assert.ok(scenarios[3].steps.every(s => Number.isInteger(s.controls.speedKph)));
  assert.ok(scenarios.every(scenario => f.api.preflight(scenario).steps.every(s => Number.isInteger(s.controls.speedKph))));
  const gentle = gentleRamp(f.writes);
  for (let i = 1; i < gentle.length; i++) assert.ok(gentle[i].at - gentle[i - 1].at >= 250);
  const hard = f.writes.filter(x => x.query.brake === "90").slice(0, 16);
  for (let i = 1; i < hard.length; i++) assert.ok(hard[i].at - hard[i - 1].at >= 100);
  assert.equal(f.state.gear, "Park"); assert.equal(f.context.fetch, f.transport);
});

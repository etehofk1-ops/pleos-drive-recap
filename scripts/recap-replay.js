/* Paste in http://127.0.0.1:8088/ DevTools. App: missing-speed demo OFF, recording started in P.
 * HTTP: Claude's measurements + GET /vehicle/catalog (2026-10-23).
 * Console speed is kph; AAOS PERF_VEHICLE_SPEED is m/s.
 * await driveRecapReplay.runFile(); await driveRecapReplay.stop();
 */
(() => {
  "use strict";
  if (location.hostname !== "127.0.0.1" || location.port !== "8088") throw new Error("127.0.0.1:8088 Console에서 실행하세요.");
  if (window.driveRecapReplay?.running) throw new Error("기존 리플레이를 먼저 중지하세요.");
  const originalFetch = globalThis.fetch;
  const transport = originalFetch.bind(globalThis);
  const gearValues = { P: "Park", D: "Drive", R: "Reverse", N: "Neutral" };
  const signalValues = { Off: "Off", "Blink left": "BlinkLeft", "Blink right": "BlinkRight" };
  const owned = new Set(["/vehicle/controls", "/vehicle/gear", "/vehicle/turn-signal"]);
  let running = false, aborted = false, completion, finishRun, wakeTimeline, guard;
  const timelineWait = ms => new Promise(resolve => {
    const timer = setTimeout(() => { wakeTimeline = null; resolve(); }, Math.max(0, ms));
    wakeTimeline = () => { clearTimeout(timer); wakeTimeline = null; resolve(); };
  });
  const request = async (path, method = "GET") => {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 5_000);
    try {
      const response = await transport(path, { method, cache: "no-store", signal: controller.signal });
      if (!response.ok) throw new Error(method + " " + path + ": HTTP " + response.status);
      return response;
    } finally { clearTimeout(timer); }
  };
  const inventory = async () => (await (await request("/vehicle/catalog")).json()).features;
  const verifyCatalog = async () => {
    const features = await inventory();
    const signal = features?.find(f => f.id === "turn-signal-state");
    if (signal?.write_endpoint !== "/vehicle/turn-signal" || signal.control?.field !== "mode" ||
        !Object.values(signalValues).every(v => signal.control.options?.some(o => o.value === v)))
      throw new Error("카탈로그의 turn-signal mode 계약이 달라졌어요. 쓰기 전에 확인하세요.");
  };
  const actionsFor = controls => {
    if (!controls || typeof controls !== "object" || Array.isArray(controls)) throw new Error("controls 형식 오류");
    const allowed = new Set(["gear", "steering", "brake", "speedKph", "turnSignal"]);
    for (const key of Object.keys(controls)) if (!allowed.has(key)) throw new Error("허용하지 않은 신호: " + key);
    const actions = [];
    if (controls.gear !== undefined) {
      if (!Object.hasOwn(gearValues, controls.gear)) throw new Error("기어 값 오류");
      actions.push("/vehicle/gear?" + new URLSearchParams({ gear: gearValues[controls.gear] }));
    }
    const query = new URLSearchParams();
    for (const [key, field, min, max] of [["speedKph", "speed", 0, 240], ["steering", "steering", -470, 500], ["brake", "brake", 0, 100]]) {
      if (controls[key] === undefined) continue;
      const value = controls[key];
      if (!Number.isFinite(value) || value < min || value > max) throw new Error(key + ": 범위 밖 값");
      query.set(field, String(key === "speedKph" ? Math.round(value) : value));
    }
    if (query.size) actions.push("/vehicle/controls?" + query);
    if (controls.turnSignal !== undefined) {
      if (!Object.hasOwn(signalValues, controls.turnSignal)) throw new Error("깜빡이 값 오류");
      actions.push("/vehicle/turn-signal?" + new URLSearchParams({ mode: signalValues[controls.turnSignal] }));
    }
    return actions;
  };
  const preflight = scenario => {
    if (scenario?.requiresDemoNavi || scenario?.steps?.some(step => step.navi !== undefined))
      throw new Error("모의 내비 시나리오는 demo-navi-feed.ps1로 재생하세요. Console HTTP와 adb 입력을 함께 보냅니다.");
    if (!scenario || !Array.isArray(scenario.steps) || !scenario.steps.length) throw new Error("시나리오 형식 오류");
    let last = -1, held = { speedKph: 0, steering: 0, brake: 0 };
    const speedAdjustments = [];
    const steps = scenario.steps.map(step => {
      if (!Number.isFinite(step.atMs) || step.atMs < 0 || step.atMs < last) throw new Error("시간은 0 이상 오름차순이어야 해요.");
      last = step.atMs;
      const gate = step.waitForSpeedKph;
      if (gate && (!Number.isFinite(gate.min) || !Number.isFinite(gate.max) || gate.min < 0 || gate.max > 240 || gate.min > gate.max ||
          !Number.isFinite(gate.timeoutMs) || gate.timeoutMs < 200 || gate.timeoutMs > 60_000)) throw new Error("속도 대기 조건 오류");
      if (gate?.holdMs !== undefined && (!Number.isFinite(gate.holdMs) || gate.holdMs < 0 || gate.holdMs > gate.timeoutMs)) throw new Error("최소 유지 시간 오류");
      if (step.caption !== undefined && (typeof step.caption !== "string" || !step.caption.trim() ||
          step.caption.length > 120 || /[\r\n]/.test(step.caption))) throw new Error("자막은 120자 이내 한 줄이어야 해요.");
      actionsFor(step.controls || {}); // Validate before merging, including unknown fields.
      const controls = { ...held, ...(step.controls || {}) };
      if (!Number.isInteger(controls.speedKph)) {
        const integer = Math.round(controls.speedKph);
        speedAdjustments.push({ atMs: step.atMs, fromKph: controls.speedKph, toKph: integer });
        controls.speedKph = integer;
      }
      held = { speedKph: controls.speedKph, steering: controls.steering, brake: controls.brake };
      return { atMs: step.atMs, actions: actionsFor(controls), controls, gate, caption: step.caption };
    });
    return { name: scenario.name || "recap", steps, speedAdjustments, requiresMissingSpeedDemo: false };
  };
  // Console physicsTick also PUTs controls every ~140ms. Isolate these three write paths
  // during replay; restore fetch in finally. Our captured transport bypasses the guard.
  const isolatePageWrites = () => {
    guard = (resource, options = {}) => {
      const url = new URL(typeof resource === "string" ? resource : resource.url, location.href);
      const method = (options.method || resource.method || "GET").toUpperCase();
      if (url.origin === location.origin && owned.has(url.pathname) && !["GET", "HEAD"].includes(method))
        return Promise.resolve(new Response('{"error":"recap replay owns these controls"}', { status: 423 }));
      return originalFetch.call(globalThis, resource, options);
    };
    globalThis.fetch = guard;
  };
  const waitSpeed = async (gate, controls) => {
    let stable = 0, prior = null, maxDecel = 0;
    const began = performance.now(), deadline = began + gate.timeoutMs;
    while (!aborted && performance.now() <= deadline) {
      const state = await (await request("/vehicle/state")).json();
      const value = state.controls?.speed_kph;
      const now = performance.now();
      if (!Number.isFinite(value)) throw new Error("서버 controls.speed_kph 미수신. 속도를 추측하지 않아요.");
      if (prior && now > prior.at) maxDecel = Math.max(maxDecel, (prior.speed - value) / 3.6 / ((now - prior.at) / 1000));
      prior = { speed: value, at: now };
      stable = value >= gate.min && value <= gate.max ? stable + 1 : 0;
      if (stable >= 2 && now - began >= (gate.holdMs || 0)) { console.info("서버 속도 확인 kph", value, "관측 최대 감속 m/s²", maxDecel); return; }
      // Console may publish only a short pedal pulse. Reassert braking until both stopped
      // and held long enough; never release after just the HTTP acknowledgement.
      if (controls.brake > 0) {
        const hold = actionsFor({ speedKph: controls.speedKph, steering: controls.steering, brake: controls.brake });
        for (const path of hold) await request(path, "POST");
      }
      await timelineWait(200);
    }
    if (!aborted) throw new Error("서버 속도가 " + gate.min + "~" + gate.max + "km/h에 도달하지 않았어요.");
  };
  const reset = async () => {
    const failures = [];
    for (const path of actionsFor({ steering: 0, speedKph: 0, brake: 0, turnSignal: "Off" }).concat("/vehicle/gear?gear=Park")) {
      try { await request(path, "POST"); } catch (error) { failures.push(error.message); }
    }
    if (failures.length) throw new Error("복원 실패: Console에서 속도0·중립·Off·브레이크0·P 확인. " + failures.join("; "));
    // Confirm server state and synchronize the page's local physics model BEFORE restoring fetch.
    // These optional UI-only functions were checked in the Console HTML. No SDK writes here.
    const deadline = performance.now() + 15_000;
    while (performance.now() <= deadline) {
      const state = await (await request("/vehicle/state")).json();
      if (state.gear?.actual?.value === "Park" && state.controls?.speed_kph === 0) {
        if (typeof syncControls === "function" && typeof renderGear === "function" && typeof renderAll === "function") {
          syncControls(state.controls); renderGear(state.gear); renderAll();
        }
        return;
      }
      await new Promise(resolve => setTimeout(resolve, 200));
    }
    throw new Error("서버 P·속도0 복원 미확인. Console에서 직접 확인하세요.");
  };
  const run = async (scenario, options = {}) => {
    if (running) throw new Error("이미 실행 중이에요.");
    const plan = preflight(scenario);
    if (plan.speedAdjustments.length) {
      console.warn("Console은 정수 속도만 허용해요. 반올림한 속도로 실행합니다.");
      console.table(plan.speedAdjustments);
    }
    if (options.dryRun) { console.table(plan.steps); return plan; }
    running = true; aborted = false;
    completion = new Promise(resolve => { finishRun = resolve; });
    let ownsWrites = false, runError = null;
    try {
      await verifyCatalog();
      if (aborted) return { completed: false, name: plan.name };
      isolatePageWrites(); ownsWrites = true;
      // Allow the page's already-issued request to finish before initial P/reset.
      await timelineWait(500);
      const start = performance.now();
      let shift = 0;
      for (const step of plan.steps) {
        await timelineWait(start + shift + step.atMs - performance.now());
        if (aborted) break;
        const late = performance.now() - start - shift - step.atMs;
        if (late > 500) throw new Error("타임라인 " + Math.round(late) + "ms 지연으로 중단했어요.");
        if (step.caption) console.info("recap caption", step.atMs, step.caption);
        for (const path of step.actions) { if (aborted) break; await request(path, "POST"); }
        const writesFinished = performance.now();
        // Do not catch up delayed writes in a burst: that can turn a gentle ramp into hard braking.
        // Preserve the next nominal gap after the last completed write, including small timer jitter.
        shift += Math.max(0, writesFinished - start - shift - step.atMs);
        if (step.gate && !aborted) {
          const waiting = performance.now(); await waitSpeed(step.gate, step.controls); shift += performance.now() - waiting;
        }
        console.info("recap replay", plan.name, "atMs", step.atMs, "actualMs", Math.round(performance.now() - start), "wallMs", Date.now());
      }
      return { completed: !aborted, name: plan.name, actualSpeedControl: true };
    } catch (error) { runError = error; throw error;
    } finally {
      try {
        if (ownsWrites) try { await reset(); }
        catch (error) { if (runError) runError.message += "; " + error.message; else throw error; }
      }
      finally {
        if (globalThis.fetch === guard) globalThis.fetch = originalFetch;
        running = false; finishRun();
      }
    }
  };
  const runFile = () => new Promise((resolve, reject) => {
    const picker = document.createElement("input"); picker.type = "file"; picker.accept = ".json,application/json";
    picker.onchange = async () => {
      try { const file = picker.files[0]; if (!file) throw new Error("선택 취소"); resolve(await run(JSON.parse(await file.text()))); }
      catch (error) { reject(error); } finally { picker.remove(); }
    };
    picker.oncancel = () => { picker.remove(); reject(new Error("선택 취소")); };
    document.body.appendChild(picker); picker.click();
  });
  window.driveRecapReplay = Object.freeze({ inventory, preflight, run, runFile,
    stop: async () => { if (running) { aborted = true; wakeTimeline?.(); await completion; } },
    get running() { return running; } });
  console.info("앱 P에서 속도 미수신 시연 OFF·기록 시작 → await driveRecapReplay.runFile(). 실행 중 Console 직접 조작 금지.");
})();

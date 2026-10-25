// AI_CONTRACT.md v2 (v1 count compatibility): closed schemas, no body/error/upstream logging.
export const LABELS = ["차선 변경", "교차로 회전", "굽은 길", "판단 보류"];
export const KINDS = ["STEERING", "LANE_CHANGE", "LANE_SIGNALLED", "LANE_NO_SIGNAL", "LANE_LATE_SIGNAL", "TURN", "CURVE", "HARD_BRAKE", "BRAKE_UNVERIFIED", "UNSURE", "TURN_NO_SIGNAL", "TURN_LATE_SIGNAL", "TURN_SIGNALLED", "BLINKER_LEFT_ON"];
export class ContractError extends Error {}
const fail = () => { throw new ContractError("invalid_request"); };
const obj = (v, keys) => {
  if (!v || typeof v !== "object" || Array.isArray(v) || Object.keys(v).sort().join("|") !== [...keys].sort().join("|")) fail();
};
const number = (v, min, max, integer = false) => {
  if (!Number.isFinite(v) || v < min || v > max || (integer && !Number.isInteger(v))) fail();
};
const oneOf = (v, values) => { if (!values.includes(v)) fail(); };
export function classifyInput(v) {
  obj(v, ["durationMs", "peakAbsRaw", "signReversals", "speedKphMin", "speedKphMax", "indicator", "naviHint"]);
  number(v.durationMs, 600, 60000, true); number(v.peakAbsRaw, 0, 10000); number(v.signReversals, 0, 1000, true);
  for (const key of ["speedKphMin", "speedKphMax"]) if (v[key] !== null) number(v[key], 0, 400);
  if ((v.speedKphMin === null) !== (v.speedKphMax === null) || (v.speedKphMin !== null && v.speedKphMin > v.speedKphMax)) fail();
  obj(v.indicator, ["state", "offsetMs"]); oneOf(v.indicator.state, ["none", "before", "late"]);
  if (v.indicator.state === "late") number(v.indicator.offsetMs, 0, v.durationMs, true);
  else if (v.indicator.offsetMs !== null) fail();
  oneOf(v.naviHint, ["none", "turn_ahead", "unknown"]);
  return v;
}
export function summarizeInput(v) {
  obj(v, ["today", "recentDays"]);
  if (!Array.isArray(v.today) || v.today.length > 500 || !Array.isArray(v.recentDays) || v.recentDays.length > 6) fail();
  for (const item of v.today) {
    obj(item, ["kind", "hourBucket", "speedBand", "indicator"]); oneOf(item.kind, KINDS);
    number(item.hourBucket, 0, 23, true); oneOf(item.speedBand, ["low", "mid", "high", "unknown"]);
    oneOf(item.indicator, ["none", "before", "late", "unknown"]);
  }
  const dates = new Set();
  for (const day of v.recentDays) {
    obj(day, ["date", "counts"]); oneOf(day.date, ["D-1", "D-2", "D-3", "D-4", "D-5", "D-6"]);
    if (dates.has(day.date)) fail(); dates.add(day.date);
    const keys = ["missing", "late", "good", "hardBrake"];
    // Closed v1 or v2, never an arbitrary mix of count keys. Old apps remain compatible.
    obj(day.counts, Object.keys(day.counts || {}).length === 7 ? [...keys, "turnMissing", "turnLate", "turnGood"] : keys);
    for (const n of Object.values(day.counts)) number(n, 0, 100000, true);
  }
  return v;
}
export function distribution(v) {
  obj(v, LABELS);
  for (const p of Object.values(v)) number(p, 0, 1);
  if (Math.abs(Object.values(v).reduce((a, b) => a + b, 0) - 1) > 0.02) fail();
  return v;
}
export function recentPatterns(input) {
  if (input.recentDays.length < 2) return [];
  const messages = { missing: "기록된 최근 날짜보다 깜빡이 미사용 후보가 많아요.", late: "기록된 최근 날짜보다 늦은 깜빡이 후보가 많아요.", hardBrake: "기록된 최근 날짜보다 급제동 후보가 많아요." };
  const kind = { missing: "LANE_NO_SIGNAL", late: "LANE_LATE_SIGNAL", hardBrake: "HARD_BRAKE" };
  return Object.keys(messages).filter(key => input.today.filter(x => x.kind === kind[key]).length * input.recentDays.length >
    input.recentDays.reduce((sum, day) => sum + day.counts[key], 0)).map(key => messages[key]);
}
export function safeText(text, input, secrets = []) {
  if (typeof text !== "string" || text.length < 8 || text.length > 700 || !/[가-힣]/u.test(text)) return false;
  if (secrets.some(k => k && text.includes(k)) || /Bearer|sk[-_]|api[_ -]?key/i.test(text)) return false;
  if (/안전(?:해|하|합|을 보장)|사고(?:가 없|를 예방)|위법|불법|법규|법적|벌금|과태료|운전\s*중|주행\s*중|운전할 때|주행할 때|다음 운전|주행하세요|차선을 바꾸세요/.test(text)) return false;
  const sentences = text.split(/[.!?]+/u).map(s => s.trim()).filter(Boolean);
  if (sentences.length < 2 || sentences.length > 3) return false;
  const numbers = new Set(JSON.stringify(input).match(/\d+/g) || []);
  if ((text.match(/\d+/g) || []).some(n => !numbers.has(n))) return false;
  // "한 번 살펴보세요" is a stationary review invitation, not an invented event count.
  const claims = text.replace(/한\s*번\s*(?=(?:더\s*)?(?:차분히\s*)?(?:살펴(?:보|봐)|돌아(?:보|봐)|확인해\s*(?:보|봐)))/gu, "");
  if (/(?:한|두|세|네|다섯|여섯|일곱|여덟|아홉|열)\s*(?:번|회|건|일간|시간|초)/u.test(claims)) return false;
  return true;
}

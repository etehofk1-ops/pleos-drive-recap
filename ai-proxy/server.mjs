import { createServer } from "node:http";
import { pathToFileURL } from "node:url";
import { classifyInput, summarizeInput, ContractError } from "./contract.mjs";
import { loadConfig, startupStatus } from "./config.mjs";
import { createProviders } from "./providers.mjs";

export function createProxy(config, { fetchImpl = fetch, log = () => {} } = {}) {
  const providers = createProviders(config, fetchImpl);
  const server = createServer(async (req, res) => {
    const reply = (status, value) => {
      if (!res.destroyed && !res.writableEnded) {
        res.writeHead(status, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" });
        res.end(JSON.stringify(value));
      }
    };
    // No CORS: browsers on other origins must not turn this loopback key relay into an API.
    if (req.headers.origin) { reply(403, { error: "forbidden" }); return; }
    if (req.method === "GET" && req.url === "/health") { reply(200, { ok: true, provider: config.provider }); return; }
    if (req.method !== "POST" || !["/classify", "/summarize"].includes(req.url)) { reply(404, { error: "not_found" }); return; }
    if (!/^application\/json(?:;|$)/i.test(req.headers["content-type"] || "")) { reply(415, { error: "invalid_request" }); return; }
    const session = req.headers["x-recap-session"];
    if (typeof session !== "string" || !/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(session)) { reply(400, { error: "invalid_request" }); return; }
    const controller = new AbortController();
    res.on("close", () => { if (!res.writableEnded) controller.abort(); });
    let input;
    try {
      let bytes = 0; const chunks = [];
      for await (const chunk of req) { bytes += chunk.length; if (bytes > 64 * 1024) throw new ContractError(); chunks.push(chunk); }
      try { input = JSON.parse(Buffer.concat(chunks).toString("utf8")); } catch { throw new ContractError(); }
      if (req.url === "/classify") classifyInput(input); else summarizeInput(input);
      const result = req.url === "/classify" ? await providers.classify(input, controller.signal) : await providers.summarize(input, session, controller.signal);
      reply(200, result); log({ event: req.url === "/classify" ? "proxy.classify.ok" : "proxy.summarize.ok" });
    } catch (error) {
      const invalid = error instanceof ContractError;
      reply(invalid ? 400 : 503, { error: invalid ? "invalid_request" : "unavailable" });
      log({ event: invalid ? "proxy.invalid_request" : "proxy.unavailable" });
    }
  });
  server.requestTimeout = 10000; server.headersTimeout = 10000;
  return { server, initialize: () => providers.initialize() };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    if (Number(process.versions.node.split(".")[0]) < 20) throw new Error("node_20_required");
    const config = await loadConfig();
    const proxy = createProxy(config, { log: event => console.log(JSON.stringify(event)) });
    await proxy.initialize();
    console.log(JSON.stringify(startupStatus(config)));
    proxy.server.on("error", () => { console.error("proxy.listen_failed"); process.exitCode = 1; });
    proxy.server.listen(8787, "127.0.0.1");
    for (const signal of ["SIGINT", "SIGTERM"]) process.once(signal, () => proxy.server.close());
  } catch { console.error("proxy.start_failed"); process.exitCode = 1; }
}

import { readFile } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";

export async function loadConfig(env = process.env, read = readFile) {
  const provider = env.RECAP_LLM_PROVIDER || "opencode-go";
  if (!["opencode-go", "openai", "none"].includes(provider)) throw new Error("invalid_provider");
  let goKey = env.OPENCODE_GO_API_KEY?.trim() || "";
  let credentialsPath = null;
  if (provider === "opencode-go" && !goKey) {
    credentialsPath = join(homedir(), ".aside", "u", "0", "credentials.json");
    try {
      // The user explicitly authorized this one entry. Never enumerate/log other credentials.
      const entry = JSON.parse(await read(credentialsPath, "utf8"))["opencode-go"];
      if (entry?.type === "api_key" && typeof entry.key === "string") goKey = entry.key.trim();
    } catch { /* Missing/invalid key => unavailable, never log filesystem/parser exception bodies. */ }
  }
  const key = provider === "opencode-go" ? goKey : provider === "openai" ? env.OPENAI_API_KEY?.trim() || "" : "";
  const baseUrl = env.RECAP_LLM_BASE_URL || (provider === "openai" ? "https://api.openai.com/v1" : "https://opencode.ai/inference/go/openai/v1");
  const url = new URL(baseUrl);
  if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash) throw new Error("invalid_base_url");
  const maxTokens = Number(env.RECAP_LLM_MAX_TOKENS || 1200);
  if (!Number.isInteger(maxTokens) || maxTokens < 800 || maxTokens > 4096) throw new Error("invalid_token_limit");
  const model = env.RECAP_LLM_MODEL || (provider === "opencode-go" ? "deepseek-v4.1-flash" : "");
  const fallbackModel = env.RECAP_LLM_FALLBACK_MODEL ?? "minimax-m3.1-flash";
  for (const candidate of [model, fallbackModel]) {
    if (candidate && (!/^[\w.:-]{1,100}$/.test(candidate) || /sk[-_]/i.test(candidate) ||
        [key, env.TYPESAFE_API_KEY].some(k => k && candidate.includes(k)))) throw new Error("invalid_model");
  }
  return { provider, key, typesafeKey: env.TYPESAFE_API_KEY?.trim() || "", baseUrl: baseUrl.replace(/\/$/, ""), model,
    fallbackModel, maxTokens, credentialsPath, classifyTimeoutMs: 2800, summarizeTimeoutMs: 15000 };
}

export const startupStatus = config => ({ event: "proxy.start", provider: config.provider, model: config.model || "not_selected",
  jevKeyPresent: Boolean(config.typesafeKey), recapKeyPresent: Boolean(config.key),
  ...(config.credentialsPath ? { credentialsPath: config.credentialsPath } : {}) });

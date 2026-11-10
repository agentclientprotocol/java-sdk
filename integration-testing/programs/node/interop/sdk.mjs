// Shared by the TypeScript interop agent and client: loads the TypeScript SDK from the checkout in
// TS_SDK (built with `npm run build`), and holds the fixtures of the step catalogue
// (integration-testing/steps.json, "fixtures"), hard-coded as the contract asks.
//
// Modules are resolved through the checkout's own package.json "exports", as a package consumer
// would import them, so a release that moves its build output (2.0 moved dist/acp.js to
// dist/v1/stable/acp.js) needs no change here. The protocol module is "./unstable" where the SDK
// has one (2.0 and later: the full v1 surface, which the session.fork step needs), else "." (before
// 2.0, "." was the full v1 surface).
import { createRequire } from "node:module";
import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";
import path from "node:path";

const sdk = process.env.TS_SDK;
if (!sdk) {
  console.error("TS_SDK is not set (the built typescript-sdk checkout)");
  process.exit(2);
}
const exportsMap = JSON.parse(readFileSync(path.join(sdk, "package.json"), "utf8")).exports ?? {};
/** The file a package subpath ("./experimental/server") exports, or null if it has none. */
export function exportedFile(subpath) {
  const target = exportsMap[subpath];
  const file = typeof target === "string" ? target : (target?.import ?? target?.default);
  return file ? path.join(sdk, file) : null;
}
const load = (...subpaths) => {
  for (const s of subpaths) {
    const f = exportedFile(s);
    if (f) return import(pathToFileURL(f).href);
  }
  throw new Error(`the SDK at ${sdk} exports none of ${subpaths.join(", ")}`);
};

export const acp = await load("./unstable", ".");
export const httpClient = await load("./experimental/http-client");
export const wsClient = await load("./experimental/ws-client");
export const server = await load("./experimental/server");
export const nodeAdapter = await load("./experimental/node");
/** The `ws` package from the SDK's own node_modules (a dev dependency, installed by npm ci). */
export const ws = createRequire(path.join(sdk, "package.json"))("ws");

export const fixtures = {
  client: {
    protocolVersion: 1,
    clientCapabilities: {
      fs: { readTextFile: true, writeTextFile: true },
      terminal: true,
      elicitation: { form: {}, url: {} },
      auth: { terminal: true },
      session: { configOptions: { boolean: {} } },
    },
    clientInfo: { name: "interop-typescript-client", version: "1" },
  },
  agent: {
    protocolVersion: 1,
    agentCapabilities: {
      loadSession: true,
      promptCapabilities: { image: false, audio: false, embeddedContext: false },
      mcpCapabilities: { http: false, sse: false },
      sessionCapabilities: { list: {}, resume: {}, close: {}, delete: {} },
      auth: { logout: {} },
    },
    authMethods: [{ id: "interop-auth", name: "Interop auth", description: "Accepts any authenticate call" }],
    terminalAuthMethod: {
      type: "terminal",
      id: "interop-terminal-auth",
      name: "Interop terminal auth",
      args: ["--login"],
    },
    agentInfo: { name: "interop-typescript-agent", version: "1" },
  },
  modes: {
    currentModeId: "interop-mode-a",
    availableModes: [
      { id: "interop-mode-a", name: "Mode A" },
      { id: "interop-mode-b", name: "Mode B" },
    ],
  },
  /** fixtures.configOptions; `verbose` only for a client that advertised configOptions.boolean. */
  configOptions(model = "model-a", verbose = false, withBoolean = true) {
    const out = [
      {
        id: "model",
        name: "Model",
        type: "select",
        currentValue: model,
        options: [
          { value: "model-a", name: "Model A" },
          { value: "model-b", name: "Model B" },
        ],
      },
    ];
    if (withBoolean) {
      out.push({ id: "verbose", name: "Verbose", type: "boolean", currentValue: verbose });
    }
    return out;
  },
  permission: {
    toolCall: { toolCallId: "perm-1", title: "interop permission", kind: "edit", status: "pending" },
    options: [
      { optionId: "allow", name: "Allow", kind: "allow_once" },
      { optionId: "reject", name: "Reject", kind: "reject_once" },
    ],
  },
  fsReadContent: "line1\nline2\nline3\n",
  emit: {
    user_message_chunk: { content: { type: "text", text: "user-chunk" } },
    agent_thought_chunk: { content: { type: "text", text: "thinking" } },
    tool_call: { toolCallId: "call-1", title: "interop tool", kind: "read", status: "pending" },
    tool_call_update: {
      toolCallId: "call-1",
      status: "completed",
      content: [{ type: "content", content: { type: "text", text: "tool output" } }],
    },
    plan: {
      entries: [
        { content: "step one", priority: "high", status: "pending" },
        { content: "step two", priority: "low", status: "completed" },
      ],
    },
    available_commands_update: {
      availableCommands: [{ name: "interop", description: "interop command", input: { hint: "args" } }],
    },
    current_mode_update: { currentModeId: "interop-mode-b" },
    session_info_update: { title: "interop title" },
    usage_update: { used: 100, size: 1000, cost: { amount: 0.01, currency: "USD" } },
    unknown: { sessionUpdate: "interop_future_update", payload: { x: 1 } },
  },
  unknownEnums: {
    toolCall: { sessionUpdate: "tool_call", toolCallId: "call-enum", title: "interop enum tool", kind: "interop_future_kind", status: "interop_future_status" },
    plan: { sessionUpdate: "plan", entries: [{ content: "future entry", priority: "interop_future_priority", status: "interop_future_status" }] },
    stopReason: "interop_future_stop",
    audience: ["user", "interop_future_role"],
  },
  /** fixtures.groupedConfigOption, at the given currentValue. */
  groupedConfigOption(effort = "effort-low") {
    return {
      id: "effort",
      name: "Effort",
      type: "select",
      currentValue: effort,
      options: [
        { group: "fast", name: "Fast", options: [{ value: "effort-low", name: "Low" }] },
        { group: "deep", name: "Deep", options: [{ value: "effort-medium", name: "Medium" }, { value: "effort-high", name: "High" }] },
      ],
    };
  },
  ext: {
    method: "_interop/ping",
    notification: "_interop/note",
    params: { n: 1 },
    result: { pong: 1 },
  },
  meta: { interop: "m1" },
  elicitation: {
    form: {
      message: "interop form",
      requestedSchema: { type: "object", properties: { name: { type: "string" } }, required: ["name"] },
    },
    formAnswer: { action: "accept", content: { name: "interop" } },
    url: { elicitationId: "elic-1", url: "https://example.invalid/interop", message: "interop url" },
  },
};

/** A pass-through parser for extension methods (the SDK requires one for custom methods). */
export const anyParams = (params) => params;

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** One line, at most `max` characters. */
export function abbreviate(value, max = 300) {
  const s = String(typeof value === "string" ? value : safeJson(value)).replace(/[\r\n]+/g, " ");
  return s.length > max ? `${s.slice(0, max)}... (${s.length} chars)` : s;
}

export function safeJson(value) {
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

/** A thrown value as one line: JSON-RPC errors keep their code. */
export function describeError(e) {
  if (e && typeof e === "object" && "code" in e && typeof e.code === "number") {
    return `error ${e.code} ${e.message ?? ""}${e.data !== undefined ? " " + abbreviate(e.data, 200) : ""}`;
  }
  return String(e?.message ?? e);
}

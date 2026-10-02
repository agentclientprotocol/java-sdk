// The TypeScript interop agent of the step catalogue (integration-testing/steps.json), on the
// TypeScript SDK's app API (acp.agent()).
//
//   agent.mjs --transport stdio                 newline-delimited JSON-RPC on stdin/stdout
//   agent.mjs --transport http|ws --port <p>    AcpServer on 127.0.0.1:<p>/acp; prints "READY <port>"
//
// Over HTTP and WebSocket one server serves both transports (Streamable HTTP POST/GET/DELETE and
// GET + Upgrade on /acp), whichever flag was given. The agent keeps no script: it is driven by the
// prompt text (the catalogue's "directives"). Everything except the protocol and the READY line
// goes to stderr: diagnostics, one [http] line per request, and the agent-side assertions,
// "STEP agent.<id> PASS|FAIL (<ms> ms) -> <detail>".
import { createServer } from "node:http";
import { randomUUID } from "node:crypto";
import { Readable, Writable } from "node:stream";
import { acp, server as acpServerModule, nodeAdapter, ws, fixtures, anyParams, sleep, abbreviate, describeError } from "./sdk.mjs";

const RequestError = acp.RequestError;
const M = acp.methods;

// ---------------------------------------------------------------- arguments

function usage(problem) {
  console.error(`agent: ${problem}`);
  console.error("usage: agent --transport stdio | --transport http|ws --port <port>");
  process.exit(2);
}

let transport;
let port;
{
  const args = process.argv.slice(2);
  for (let i = 0; i < args.length; i++) {
    const a = args[i];
    const value = () => (i + 1 < args.length ? args[++i] : usage(`${a} needs a value`));
    if (a === "--transport") transport = value();
    else if (a === "--port") port = value();
    else usage(`unknown argument ${a}`);
  }
  if (!["stdio", "http", "ws"].includes(transport)) usage("--transport stdio|http|ws is required");
  if (transport !== "stdio" && (port === undefined || !/^\d+$/.test(port))) usage(`--port is required for ${transport}`);
}

const log = (...parts) => console.error("[agent]", ...parts);

/** An agent-side assertion, on stderr (stdout is the protocol on stdio). */
function agentStep(id, ok, detail, t0 = Date.now()) {
  console.error(`STEP agent.${id} ${ok ? "PASS" : "FAIL"} (${Date.now() - t0} ms) -> ${abbreviate(detail, 400)}`);
}

// ---------------------------------------------------------------- sessions (shared by all connections)

/**
 * sessionId -> { cwd, history: [{ prompt, chunks }], closed, turn, mode, model, verbose }.
 * Shared across connections so a session can be loaded on a new connection (http.reconnect).
 */
const sessions = new Map();

function newSessionState(cwd) {
  return { cwd, history: [], closed: false, turn: null, mode: fixtures.modes.currentModeId, model: "model-a", verbose: false, grouped: false, effort: "effort-low" };
}

function knownSession(sessionId) {
  const s = sessions.get(sessionId);
  if (!s) throw new RequestError(-32602, `unknown session ${sessionId}`);
  if (s.closed) throw new RequestError(-32602, `session ${sessionId} is closed`);
  return s;
}

/** A running prompt turn: cancellable by session/cancel and session/close. */
class Turn {
  constructor(kind) {
    this.kind = kind;
    this.cancelled = false;
    this.cancelledBy = undefined;
    this.listeners = [];
  }

  cancel(by) {
    if (this.cancelled) return;
    this.cancelled = true;
    this.cancelledBy = by;
    for (const l of this.listeners.splice(0)) l(by);
  }

  /** Resolves when the turn is cancelled. */
  whenCancelled() {
    return new Promise((resolve) => (this.cancelled ? resolve(this.cancelledBy) : this.listeners.push(resolve)));
  }
}

// ---------------------------------------------------------------- the agent app, one per connection

function createAgentApp() {
  /** What this connection's client sent in initialize (after the SDK's schema parsing). */
  let clientCaps;
  let lastExtNotification;

  const sessionState = (s) => ({
    modes: { ...fixtures.modes, currentModeId: s.mode },
    configOptions: [
      ...fixtures.configOptions(s.model, s.verbose, booleanConfig()),
      ...(s.grouped ? [fixtures.groupedConfigOption(s.effort)] : []),
    ],
  });
  const booleanConfig = () => clientCaps?.session?.configOptions?.boolean != null;

  return acp
    .agent({ name: "interop-typescript-agent" })
    .onRequest(M.agent.initialize, (ctx) => {
      clientCaps = ctx.params.clientCapabilities;
      log("initialize", abbreviate(ctx.params));
      const authMethods = [...fixtures.agent.authMethods];
      if (clientCaps?.auth?.terminal === true) authMethods.push(fixtures.agent.terminalAuthMethod);
      return {
        protocolVersion: fixtures.agent.protocolVersion,
        agentCapabilities: fixtures.agent.agentCapabilities,
        authMethods,
        agentInfo: fixtures.agent.agentInfo,
      };
    })
    .onRequest(M.agent.authenticate, (ctx) => {
      agentStep("auth.authenticate", ctx.params.methodId === "interop-auth", `methodId ${ctx.params.methodId}`);
      return {};
    })
    .onRequest(M.agent.logout, () => ({}))
    .onRequest(M.agent.session.new, (ctx) => {
      const sessionId = randomUUID();
      const s = newSessionState(ctx.params.cwd);
      sessions.set(sessionId, s);
      log("session/new", sessionId, ctx.params.cwd);
      return { sessionId, ...sessionState(s) };
    })
    .onRequest(M.agent.session.load, async (ctx) => {
      const sid = ctx.params.sessionId;
      const s = knownSession(sid);
      log("session/load", sid, `${s.history.length} turn(s) to replay`);
      for (const turn of s.history) {
        await update(ctx.client, sid, { sessionUpdate: "user_message_chunk", content: { type: "text", text: turn.prompt } });
        for (const text of turn.chunks) {
          await update(ctx.client, sid, { sessionUpdate: "agent_message_chunk", content: { type: "text", text } });
        }
      }
      return sessionState(s);
    })
    .onRequest(M.agent.session.resume, (ctx) => {
      const s = knownSession(ctx.params.sessionId);
      log("session/resume", ctx.params.sessionId);
      return sessionState(s);
    })
    .onRequest(M.agent.session.fork, (ctx) => {
      const s = knownSession(ctx.params.sessionId);
      const sessionId = randomUUID();
      const fork = newSessionState(ctx.params.cwd);
      fork.history = s.history.map((t) => ({ prompt: t.prompt, chunks: [...t.chunks] }));
      fork.mode = s.mode;
      fork.model = s.model;
      fork.verbose = s.verbose;
      sessions.set(sessionId, fork);
      log("session/fork", ctx.params.sessionId, "->", sessionId);
      return { sessionId, ...sessionState(fork) };
    })
    .onRequest(M.agent.session.list, (ctx) => {
      const cwd = ctx.params.cwd;
      const list = [...sessions.entries()]
        .filter(([, s]) => !s.closed && (cwd == null || s.cwd === cwd))
        .map(([sessionId, s]) => ({ sessionId, cwd: s.cwd }));
      return { sessions: list };
    })
    .onRequest(M.agent.session.close, (ctx) => {
      const t0 = Date.now();
      const sid = ctx.params.sessionId;
      const s = sessions.get(sid);
      if (!s || s.closed) {
        agentStep("session.close", false, `close of an unknown session ${sid}`, t0);
        throw new RequestError(-32602, `unknown session ${sid}`);
      }
      s.closed = true;
      const turn = s.turn;
      if (turn) turn.cancel("close");
      agentStep("session.close", turn != null, turn ? `session ${sid} closed; its ${turn.kind} turn was cancelled` : `session ${sid} had no running turn`, t0);
      return {};
    })
    .onRequest(M.agent.session.delete, (ctx) => {
      const s = sessions.get(ctx.params.sessionId);
      s?.turn?.cancel("delete");
      sessions.delete(ctx.params.sessionId);
      return {};
    })
    .onRequest(M.agent.session.setMode, (ctx) => {
      const s = knownSession(ctx.params.sessionId);
      const known = fixtures.modes.availableModes.some((m) => m.id === ctx.params.modeId);
      agentStep("mode.set", known && ctx.params.modeId === "interop-mode-b", `modeId ${ctx.params.modeId}`);
      if (!known) throw new RequestError(-32602, `unknown mode ${ctx.params.modeId}`);
      s.mode = ctx.params.modeId;
      return {};
    })
    .onRequest(M.agent.session.setConfigOption, (ctx) => {
      const s = knownSession(ctx.params.sessionId);
      const { configId, value } = ctx.params;
      if (configId === "model" && ["model-a", "model-b"].includes(value)) s.model = value;
      else if (configId === "verbose" && typeof value === "boolean" && booleanConfig()) s.verbose = value;
      else if (configId === "effort" && s.grouped && fixtures.groupedConfigOption().options.some((g) => g.options.some((o) => o.value === value))) {
        s.effort = value;
        agentStep("config.grouped", value === "effort-high", `effort set to ${value}`);
      }
      else throw new RequestError(-32602, `invalid config option ${configId}=${JSON.stringify(value)}`);
      return { configOptions: sessionState(s).configOptions };
    })
    .onRequest(M.agent.session.prompt, (ctx) => prompt(ctx, clientCaps, () => lastExtNotification, sessionState))
    .onNotification(M.agent.session.cancel, (ctx) => {
      const s = sessions.get(ctx.params.sessionId);
      log("session/cancel", ctx.params.sessionId, s?.turn ? `(running ${s.turn.kind})` : "(no running turn)");
      const turn = s?.turn;
      if (!turn) return;
      if (turn.kind === "slow" && !turn.cancelled) agentStep("cancel.prompt", true, `session/cancel for the running session ${ctx.params.sessionId}`);
      turn.cancel("session/cancel");
    })
    .onRequest(fixtures.ext.method, anyParams, (ctx) => {
      log("extension request", fixtures.ext.method, abbreviate(ctx.params));
      return fixtures.ext.result;
    })
    .onNotification(fixtures.ext.notification, anyParams, (ctx) => {
      log("extension notification", fixtures.ext.notification, abbreviate(ctx.params));
      lastExtNotification = fixtures.ext.notification;
    });
}

function update(client, sessionId, upd, meta) {
  const params = { sessionId, update: upd };
  if (meta !== undefined) params._meta = meta;
  return client.notify(M.client.session.update, params);
}

// ---------------------------------------------------------------- prompt turns and directives

async function prompt(ctx, clientCaps, lastExt, sessionState) {
  const sid = ctx.params.sessionId;
  const s = knownSession(sid);
  const first = ctx.params.prompt.find((b) => b.type === "text");
  const text = first?.text ?? "";
  log("session/prompt", sid, abbreviate(text, 120));

  const record = { prompt: text, chunks: [] };
  s.history.push(record);
  const chunk = async (t, meta) => {
    record.chunks.push(t);
    const upd = { sessionUpdate: "agent_message_chunk", content: { type: "text", text: t } };
    if (meta !== undefined) upd._meta = meta;
    await update(ctx.client, sid, upd);
  };

  if (!text.startsWith("#")) {
    await chunk("echo: ");
    await chunk(text);
    return { stopReason: "end_turn" };
  }

  const space = text.indexOf(" ");
  const name = (space < 0 ? text : text.slice(0, space)).slice(1);
  const rest = space < 0 ? "" : text.slice(space + 1);
  const args = rest === "" ? [] : rest.split(" ");
  const kw = (key) => args.find((a) => a.startsWith(`${key}=`))?.slice(key.length + 1);

  const turn = new Turn(name);
  s.turn = turn;
  try {
    switch (name) {
      case "permission":
        return await permission(ctx, sid, args.join(" "), turn, chunk);
      case "fs":
        return await fs(ctx, sid, clientCaps, args, rest, kw, chunk);
      case "emit":
        return await emit(ctx, sid, args[0], kw("name"), clientCaps, chunk);
      case "stop":
        await chunk("stop");
        return { stopReason: args[0] };
      case "slow":
        return await slow(ctx, sid, Number(kw("grace") ?? 0), turn, chunk);
      case "hang":
        log("#hang: never answering");
        return await new Promise(() => {});
      case "terminal":
        return await terminal(ctx, sid, clientCaps, args, chunk);
      case "elicit":
        return await elicit(ctx, sid, clientCaps, args[0], chunk);
      case "ext":
        return await ext(ctx, args, lastExt, chunk);
      case "meta": {
        const meta = ctx.params._meta;
        await chunk("meta", meta ?? undefined);
        return meta == null ? { stopReason: "end_turn" } : { stopReason: "end_turn", _meta: meta };
      }
      case "echo-caps": {
        const caps = clientCaps;
        agentStep("init.client-capabilities", caps != null, `clientCapabilities ${abbreviate(caps)}`);
        await chunk(JSON.stringify(caps ?? null));
        return { stopReason: "end_turn" };
      }
      case "enum":
        return await unknownEnum(ctx, sid, args[0], first, chunk);
      case "config":
        if (args[0] !== "grouped") throw new RequestError(-32602, `unknown directive: #config ${args[0]}`);
        s.grouped = true;
        await update(ctx.client, sid, { sessionUpdate: "config_option_update", configOptions: sessionState(s).configOptions });
        return { stopReason: "end_turn" };
      case "len":
        await chunk(`len=${rest.length}`);
        return { stopReason: "end_turn" };
      case "big":
        await chunk("x".repeat(Number(args[0])));
        return { stopReason: "end_turn" };
      default:
        throw new RequestError(-32602, `unknown directive: #${name}`);
    }
  } finally {
    if (s.turn === turn) s.turn = null;
  }
}

// #permission allow: perm.selected; #permission allow meta: meta.permission (_meta on the request);
// #permission hold: perm.cancelled.
async function permission(ctx, sid, mode, turn, chunk) {
  if (mode !== "allow" && mode !== "allow meta" && mode !== "hold") throw new RequestError(-32602, `unknown directive: #permission ${mode}`);
  const t0 = Date.now();
  const request = { sessionId: sid, toolCall: fixtures.permission.toolCall, options: fixtures.permission.options };
  if (mode === "allow meta") request._meta = fixtures.meta;
  const r = await ctx.client.request(M.client.session.requestPermission, request);
  const outcome = r?.outcome;
  log("permission outcome", abbreviate(r));
  if (mode === "allow") {
    agentStep("perm.selected", outcome?.outcome === "selected" && outcome.optionId === "allow", `outcome ${abbreviate(outcome)}`, t0);
  } else if (mode === "allow meta") {
    agentStep("meta.permission", r?._meta?.interop === "m1", `response _meta ${abbreviate(r?._meta)}`, t0);
  } else {
    agentStep("perm.cancelled", outcome?.outcome === "cancelled", `outcome ${abbreviate(outcome)}`, t0);
  }
  await chunk(outcome?.outcome === "selected" ? `permission: selected ${outcome.optionId}` : "permission: cancelled");
  return { stopReason: turn.cancelled || outcome?.outcome === "cancelled" ? "cancelled" : "end_turn" };
}

async function fs(ctx, sid, clientCaps, args, rest, kw, chunk) {
  const op = args[0];
  if (op === "write") {
    const path = args[1];
    const content = rest.slice("write ".length + path.length + 1);
    const t0 = Date.now();
    if (clientCaps?.fs?.writeTextFile !== true) {
      agentStep("fs.write", false, "the client did not advertise fs.writeTextFile", t0);
      await chunk("fs write error capability");
      return { stopReason: "end_turn" };
    }
    try {
      const r = await ctx.client.request(M.client.fs.writeTextFile, { sessionId: sid, path, content });
      agentStep("fs.write", true, `fs/write_text_file answered ${abbreviate(r)}`, t0);
      await chunk("fs write ok");
    } catch (e) {
      agentStep("fs.write", false, `fs/write_text_file failed: ${describeError(e)}`, t0);
      await chunk(`fs write error ${e?.code ?? describeError(e)}`);
    }
    return { stopReason: "end_turn" };
  }
  if (op === "read" || op === "read-range" || op === "read-missing") {
    const path = args[1];
    const line = kw("line");
    const limit = kw("limit");
    const id = `fs.${op}`;
    const t0 = Date.now();
    if (clientCaps?.fs?.readTextFile !== true) {
      agentStep(id, false, "the client did not advertise fs.readTextFile", t0);
      await chunk("fs read error capability");
      return { stopReason: "end_turn" };
    }
    const params = { sessionId: sid, path };
    if (line !== undefined) params.line = Number(line);
    if (limit !== undefined) params.limit = Number(limit);
    try {
      const r = await ctx.client.request(M.client.fs.readTextFile, params);
      const content = r?.content;
      if (id === "fs.read") agentStep(id, content === fixtures.fsReadContent, `content ${JSON.stringify(content)}`, t0);
      else if (id === "fs.read-range") agentStep(id, typeof content === "string" && content.trim() === "line2", `content ${JSON.stringify(content)}`, t0);
      else agentStep(id, false, `fs/read_text_file of a missing file answered ${abbreviate(r)}`, t0);
      await chunk(typeof content === "string" ? content : `fs read error no content: ${abbreviate(r)}`);
    } catch (e) {
      agentStep(id, id === "fs.read-missing" && typeof e?.code === "number", `fs/read_text_file failed: ${describeError(e)}`, t0);
      await chunk(`fs read error ${e?.code ?? describeError(e)}`);
    }
    return { stopReason: "end_turn" };
  }
  if (op === "read-slow") {
    const path = args[1];
    const t0 = Date.now();
    if (clientCaps?.fs?.readTextFile !== true) {
      agentStep("cancel-request.agent", false, "the client did not advertise fs.readTextFile", t0);
      await chunk("fs read error capability");
      return { stopReason: "end_turn" };
    }
    const cancel = new AbortController();
    const pending = ctx.client.request(M.client.fs.readTextFile, { sessionId: sid, path }, { cancellationSignal: cancel.signal });
    pending.catch(() => {});
    await sleep(200);
    cancel.abort();
    const tCancel = Date.now();
    log("$/cancel_request sent for the fs/read_text_file of", path);
    await chunk("cancel-request sent");
    let outcome;
    try {
      const r = await Promise.race([pending, sleep(5000).then(() => ({ timeout: true }))]);
      outcome = r?.timeout ? "TIMEOUT: no answer within 5 s of $/cancel_request" : `answered with a result ${abbreviate(r)}`;
      agentStep("cancel-request.agent", false, outcome, t0);
    } catch (e) {
      const ms = Date.now() - tCancel;
      agentStep("cancel-request.agent", e?.code === -32800 && ms <= 5000, `fs request ended ${describeError(e)} ${ms} ms after the cancel`, t0);
    }
    return { stopReason: "end_turn" };
  }
  throw new RequestError(-32602, `unknown directive: #fs ${op}`);
}

/**
 * #enum tool_call|plan|stop|audience: values the v1 schema does not define. notify() and the
 * prompt response are sent as given (no outgoing validation).
 */
async function unknownEnum(ctx, sid, what, first, chunk) {
  switch (what) {
    case "tool_call":
    case "plan":
      await update(ctx.client, sid, { ...fixtures.unknownEnums[what === "plan" ? "plan" : "toolCall"] });
      await chunk("after-enum");
      return { stopReason: "end_turn" };
    case "stop":
      await chunk("stop");
      return { stopReason: fixtures.unknownEnums.stopReason };
    case "audience": {
      const audience = first?.annotations?.audience;
      const ok = JSON.stringify(audience) === JSON.stringify(fixtures.unknownEnums.audience);
      agentStep("enum.audience", ok, `audience ${JSON.stringify(audience ?? null)}`);
      await chunk(`audience: ${Array.isArray(audience) ? audience.join(",") : "none"}`);
      return { stopReason: "end_turn" };
    }
    default:
      throw new RequestError(-32602, `unknown directive: #enum ${what}`);
  }
}

async function emit(ctx, sid, kind, toolName, clientCaps, chunk) {
  const send = (u) => update(ctx.client, sid, u);
  switch (kind) {
    case "user_message_chunk":
    case "agent_thought_chunk":
    case "plan":
    case "available_commands_update":
    case "current_mode_update":
    case "session_info_update":
    case "usage_update":
      await send({ sessionUpdate: kind, ...fixtures.emit[kind] });
      break;
    case "tool_call":
      await send({ sessionUpdate: "tool_call", ...fixtures.emit.tool_call, ...(toolName ? { name: toolName } : {}) });
      break;
    case "tool_call_update":
      await send({ sessionUpdate: "tool_call", ...fixtures.emit.tool_call });
      await send({ sessionUpdate: "tool_call_update", ...fixtures.emit.tool_call_update });
      break;
    case "config_option_update":
      await send({
        sessionUpdate: "config_option_update",
        configOptions: fixtures.configOptions("model-b", false, clientCaps?.session?.configOptions?.boolean != null),
      });
      break;
    case "unknown":
      // The typed API has no such variant; notify() sends the params as given (no outgoing validation).
      await send({ ...fixtures.emit.unknown });
      await chunk("after-unknown");
      break;
    default:
      throw new RequestError(-32602, `unknown directive: #emit ${kind}`);
  }
  return { stopReason: "end_turn" };
}

async function slow(ctx, sid, grace, turn, chunk) {
  const t0 = Date.now();
  let requestCancelled = false;
  const onAbort = () => {
    requestCancelled = true;
    agentStep("cancel-request.client", true, `$/cancel_request observed for the prompt (request ${ctx.requestId})`, t0);
    turn.cancel("$/cancel_request");
  };
  if (ctx.signal.aborted) onAbort();
  else ctx.signal.addEventListener("abort", onAbort, { once: true });
  try {
    const deadline = Date.now() + 10_000;
    let cancelAt;
    while (Date.now() < deadline) {
      if (turn.cancelled && cancelAt === undefined) cancelAt = Date.now();
      if (cancelAt !== undefined && (requestCancelled || turn.cancelledBy === "close" || Date.now() - cancelAt >= grace)) break;
      try {
        await chunk("tick");
      } catch (e) {
        log("#slow: tick failed:", describeError(e));
        break;
      }
      await Promise.race([sleep(100), turn.cancelled ? sleep(100) : turn.whenCancelled()]);
    }
    if (turn.cancelled) {
      log(`#slow: cancelled by ${turn.cancelledBy} after ${Date.now() - t0} ms`);
      return { stopReason: "cancelled" };
    }
    return { stopReason: "end_turn" };
  } finally {
    ctx.signal.removeEventListener("abort", onAbort);
  }
}

async function terminal(ctx, sid, clientCaps, args, chunk) {
  const [op, command, ...cmdArgs] = args;
  const id = op === "kill" ? "term.kill" : "term.run";
  const t0 = Date.now();
  if (op !== "run" && op !== "kill") throw new RequestError(-32602, `unknown directive: #terminal ${op}`);
  if (clientCaps?.terminal !== true) {
    agentStep(id, false, "the client did not advertise terminal", t0);
    await chunk("terminal error capability");
    return { stopReason: "end_turn" };
  }
  const c = ctx.client;
  try {
    const { terminalId } = await c.request(M.client.terminal.create, { sessionId: sid, command, args: cmdArgs });
    if (op === "run") {
      const exit = await c.request(M.client.terminal.waitForExit, { sessionId: sid, terminalId });
      const out = await c.request(M.client.terminal.output, { sessionId: sid, terminalId });
      await c.request(M.client.terminal.release, { sessionId: sid, terminalId });
      const output = (out?.output ?? "").trim();
      const code = exit?.exitCode ?? out?.exitStatus?.exitCode;
      agentStep(id, output.includes("hi") && code === 0, `output ${JSON.stringify(output)} exitCode ${code}`, t0);
      await chunk(`terminal: ${output} exit=${code}`);
    } else {
      await sleep(200);
      await c.request(M.client.terminal.kill, { sessionId: sid, terminalId });
      const tKill = Date.now();
      const exit = await c.request(M.client.terminal.waitForExit, { sessionId: sid, terminalId });
      const ms = Date.now() - tKill;
      await c.request(M.client.terminal.release, { sessionId: sid, terminalId });
      agentStep(id, ms <= 5000, `wait_for_exit returned ${abbreviate(exit)} ${ms} ms after the kill`, t0);
      await chunk("terminal killed");
    }
  } catch (e) {
    agentStep(id, false, `terminal request failed: ${describeError(e)}`, t0);
    await chunk(`terminal error ${e?.code ?? describeError(e)}`);
  }
  return { stopReason: "end_turn" };
}

async function elicit(ctx, sid, clientCaps, mode, chunk) {
  const t0 = Date.now();
  if (mode === "form") {
    if (clientCaps?.elicitation?.form == null) {
      agentStep("elicit.form", false, "the client did not advertise elicitation.form", t0);
      await chunk("elicit error capability");
      return { stopReason: "end_turn" };
    }
    try {
      const r = await ctx.client.request(M.client.elicitation.create, { sessionId: sid, mode: "form", ...fixtures.elicitation.form });
      agentStep("elicit.form", r?.action === "accept" && r?.content?.name === "interop", `response ${abbreviate(r)}`, t0);
      await chunk(`elicit: ${r?.action} ${JSON.stringify(r?.content ?? null)}`);
    } catch (e) {
      agentStep("elicit.form", false, `elicitation/create failed: ${describeError(e)}`, t0);
      await chunk(`elicit error ${e?.code ?? describeError(e)}`);
    }
    return { stopReason: "end_turn" };
  }
  if (mode === "url") {
    if (clientCaps?.elicitation?.url == null) {
      await chunk("elicit error capability");
      return { stopReason: "end_turn" };
    }
    try {
      const r = await ctx.client.request(M.client.elicitation.create, { sessionId: sid, mode: "url", ...fixtures.elicitation.url });
      log("url elicitation answered", abbreviate(r));
      await ctx.client.notify(M.client.elicitation.complete, { elicitationId: fixtures.elicitation.url.elicitationId });
      await chunk("elicit url done");
    } catch (e) {
      await chunk(`elicit error ${e?.code ?? describeError(e)}`);
    }
    return { stopReason: "end_turn" };
  }
  throw new RequestError(-32602, `unknown directive: #elicit ${mode}`);
}

async function ext(ctx, args, lastExt, chunk) {
  const [op, method] = args;
  const t0 = Date.now();
  if (op === "request") {
    try {
      const r = await ctx.client.request(method, fixtures.ext.params);
      agentStep("ext.agent-request", r?.pong === 1, `result ${abbreviate(r)}`, t0);
      await chunk(`ext: ${JSON.stringify(r)}`);
    } catch (e) {
      agentStep("ext.agent-request", false, `${method} failed: ${describeError(e)}`, t0);
      await chunk(`ext error ${e?.code ?? describeError(e)}`);
    }
    return { stopReason: "end_turn" };
  }
  if (op === "notify") {
    await ctx.client.notify(method, fixtures.ext.params);
    await chunk("ext notified");
    return { stopReason: "end_turn" };
  }
  if (op === "last-notification") {
    await chunk(`ext last: ${lastExt() ?? "none"}`);
    return { stopReason: "end_turn" };
  }
  throw new RequestError(-32602, `unknown directive: #ext ${op}`);
}

// ---------------------------------------------------------------- transports

if (transport === "stdio") {
  const stream = acp.ndJsonStream(Writable.toWeb(process.stdout), Readable.toWeb(process.stdin));
  const connection = createAgentApp().connect(stream);
  log("serving on stdio");
  const exit = (why) => {
    log(`exiting: ${why}`);
    process.exit(0);
  };
  process.stdin.on("end", () => exit("stdin reached EOF"));
  process.stdin.on("close", () => exit("stdin closed"));
  void connection.closed.then(() => exit("connection closed"));
} else {
  const acpServer = new acpServerModule.AcpServer({ createAgent: () => createAgentApp() });
  const handler = nodeAdapter.createNodeHttpHandler(acpServer);
  const wss = new ws.WebSocketServer({ noServer: true });
  const upgrade = nodeAdapter.createNodeWebSocketUpgradeHandler(acpServer, wss);
  const httpServer = createServer((req, res) => {
    const t0 = Date.now();
    const h = req.headers;
    const line = `[http] ${req.method} ${req.url} HTTP/${req.httpVersion}`;
    const extra = `ct=${h["content-type"]} accept=${h["accept"]} conn=${h["acp-connection-id"]} sess=${h["acp-session-id"]}`;
    res.on("finish", () => console.error(`${line} -> ${res.statusCode} ${extra} ${Date.now() - t0}ms`));
    if (new URL(req.url, "http://x").pathname !== "/acp") {
      res.writeHead(404);
      res.end();
      return;
    }
    handler(req, res);
  });
  wss.on("headers", (headers, req) => {
    console.error(`[http] ${req.method} ${req.url} HTTP/${req.httpVersion} -> 101 upgrade="${req.headers["upgrade"]}" ${headers.find((x) => /^acp-connection-id/i.test(x)) ?? ""}`);
  });
  httpServer.on("upgrade", (req, socket, head) => {
    if (new URL(req.url, "http://x").pathname !== "/acp") {
      console.error(`[http] ${req.method} ${req.url} HTTP/${req.httpVersion} -> 404 upgrade="${req.headers["upgrade"]}"`);
      socket.end("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n");
      return;
    }
    upgrade(req, socket, head);
  });
  httpServer.on("clientError", (err) => console.error("[clientError]", err.message));
  httpServer.listen(Number(port), "127.0.0.1", () => {
    const bound = httpServer.address().port;
    log(`listening on http://127.0.0.1:${bound}/acp (Streamable HTTP and WebSocket, --transport ${transport})`);
    console.log(`READY ${bound}`);
  });
  process.on("SIGTERM", () => process.exit(0));
}

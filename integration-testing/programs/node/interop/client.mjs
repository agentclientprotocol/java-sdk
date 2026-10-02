// The TypeScript interop client of the step catalogue (integration-testing/steps.json), on the
// TypeScript SDK's app API (acp.client()).
//
//   STEPS=<ids> client.mjs --transport stdio                 spawns bash -c "exec $AGENT_CMD"
//   STEPS=<ids> client.mjs --transport http --url http://127.0.0.1:<p>/acp
//   STEPS=<ids> client.mjs --transport ws   --url ws://127.0.0.1:<p>/acp
//
// Runs the step ids in STEPS in order and prints one "STEP <id> PASS|FAIL (<ms> ms) -> <detail>"
// line per step, then "RESULT pass=.. fail=.. updates_total=.. upd_<kind>=..", and exits 0. On
// stdio the agent's stderr is relayed to stdout: lines starting with "STEP " verbatim, every other
// line prefixed "agent| ". Env STEP_TIMEOUT_MS (default 15000).
import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import { Readable, Writable } from "node:stream";
import fsp from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { acp, httpClient, wsClient, ws, fixtures, anyParams, sleep, abbreviate, describeError } from "./sdk.mjs";

const M = acp.methods;
const RequestError = acp.RequestError;

// ---------------------------------------------------------------- arguments

function usage(problem) {
  console.error(`client: ${problem}`);
  console.error("usage: STEPS=<ids> client --transport stdio (with AGENT_CMD) | --transport http|ws --url <url>");
  process.exit(2);
}

let transport;
let url;
{
  const args = process.argv.slice(2);
  for (let i = 0; i < args.length; i++) {
    const a = args[i];
    const value = () => (i + 1 < args.length ? args[++i] : usage(`${a} needs a value`));
    if (a === "--transport") transport = value();
    else if (a === "--url") url = value();
    else usage(`unknown argument ${a}`);
  }
  if (!["stdio", "http", "ws"].includes(transport)) usage("--transport stdio|http|ws is required");
  if (transport !== "stdio" && !url) usage(`--url is required for ${transport}`);
  if (transport === "stdio" && !process.env.AGENT_CMD) usage("AGENT_CMD is required for stdio");
  if (!process.env.STEPS || !process.env.STEPS.trim()) usage("STEPS is required");
}

const STEP_TIMEOUT = Number(process.env.STEP_TIMEOUT_MS || 15000);
const UPDATE_GRACE = 1000;
const REPLAY_GRACE = 2000;

let dir;
let pass = 0;
let fail = 0;
let updatesTotal = 0;
const updatesByKind = new Map();
/** The main connection, opened by init.initialize. */
let main;

class StepFailure extends Error {}

function check(ok, failure) {
  if (!ok) throw new StepFailure(typeof failure === "function" ? failure() : failure);
}

class Timeout extends Error {}

function withTimeout(promise, ms, what) {
  let timer;
  return Promise.race([
    promise,
    new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Timeout(`TIMEOUT after ${ms} ms: ${what}`)), ms);
    }),
  ]).finally(() => clearTimeout(timer));
}

/** Wait up to `ms` for a condition on what was received (updates may trail a response). */
async function waitFor(condition, failure, ms = UPDATE_GRACE) {
  const deadline = Date.now() + ms;
  for (;;) {
    if (condition()) return;
    if (Date.now() > deadline) throw new StepFailure(typeof failure === "function" ? failure() : failure);
    await sleep(20);
  }
}

/** Relay one stderr line of a stdio agent: STEP lines verbatim, everything else prefixed. */
function relay(line) {
  console.log(line.startsWith("STEP ") ? line : `agent| ${line}`);
}

// ---------------------------------------------------------------- connection

/**
 * Wraps the transport stream: records the requests this side sends (their ids, for
 * cancel-request.unknown) and stray responses, and lets close() wait for the transport's own close
 * (the HTTP DELETE, the WebSocket close).
 */
function wrapStream(inner) {
  const sent = [];
  const strays = [];
  const writer = inner.writable.getWriter();
  const reader = inner.readable.getReader();
  let closing;
  const closeInner = (reason) => {
    if (!closing) closing = reader.cancel(reason).catch((e) => console.log("  transport close failed:", describeError(e)));
    return closing;
  };
  return {
    sent,
    strays,
    closeInner,
    stream: {
      writable: new WritableStream({
        write(message) {
          if (message && typeof message === "object" && "method" in message && "id" in message) sent.push({ id: message.id, method: message.method });
          return writer.write(message);
        },
        close: () => writer.close(),
        abort: (reason) => writer.abort(reason),
      }),
      readable: new ReadableStream({
        async pull(controller) {
          try {
            const { value, done } = await reader.read();
            if (done) {
              controller.close();
              return;
            }
            if (value && typeof value === "object" && !("method" in value) && "id" in value && "error" in value) {
              strays.push(value);
            }
            controller.enqueue(value);
          } catch (e) {
            controller.error(e);
          }
        },
        cancel: (reason) => closeInner(reason),
      }),
    },
  };
}

/** Spawns the agent of a stdio cell: bash -c "exec $AGENT_CMD", stderr relayed line by line. */
function spawnAgent() {
  const child = spawn("bash", ["-c", `exec ${process.env.AGENT_CMD}`], { stdio: ["pipe", "pipe", "pipe"], env: process.env });
  createInterface({ input: child.stderr }).on("line", relay);
  const exited = new Promise((resolve) => child.on("exit", (code, signal) => resolve({ code, signal })));
  child.stdin.on("error", () => {});
  return { child, exited, stream: acp.ndJsonStream(Writable.toWeb(child.stdin), Readable.toWeb(child.stdout)) };
}

/** One client connection, with what it received. */
class Conn {
  constructor() {
    /** sessionId -> [update] in arrival order. */
    this.updates = new Map();
    /** sessionId -> [RequestPermissionRequest params]. */
    this.permissions = new Map();
    /** Sessions whose permission request is answered by cancelling the turn (perm.cancelled). */
    this.holdSessions = new Map();
    this.extNotifications = [];
    this.elicitationCompletes = [];
    this.terminals = new Map();
  }

  static open() {
    const c = new Conn();
    let inner;
    if (transport === "http") inner = httpClient.createHttpStream(url);
    else if (transport === "ws") inner = wsClient.createWebSocketStream(url, { WebSocket: ws.WebSocket });
    else {
      c.proc = spawnAgent();
      inner = c.proc.stream;
    }
    c.wrapped = wrapStream(inner);
    c.connection = c.app().connect(c.wrapped.stream);
    c.agent = c.connection.agent;
    return c;
  }

  app() {
    return acp
      .client({ name: "interop-typescript-client" })
      .onNotification(M.client.session.update, (ctx) => this.onUpdate(ctx.params))
      .onRequest(M.client.session.requestPermission, (ctx) => this.onPermission(ctx))
      .onRequest(M.client.fs.writeTextFile, async (ctx) => {
        await fsp.writeFile(ctx.params.path, ctx.params.content, "utf8");
        console.log(`  fs/write_text_file ${ctx.params.path}`);
        return {};
      })
      .onRequest(M.client.fs.readTextFile, (ctx) => this.onRead(ctx))
      .onRequest(M.client.terminal.create, (ctx) => this.terminalCreate(ctx.params))
      .onRequest(M.client.terminal.output, (ctx) => {
        const t = this.terminal(ctx.params.terminalId);
        return { output: t.output, truncated: false, exitStatus: t.exit ?? undefined };
      })
      .onRequest(M.client.terminal.waitForExit, async (ctx) => this.terminal(ctx.params.terminalId).exited)
      .onRequest(M.client.terminal.kill, (ctx) => {
        this.terminal(ctx.params.terminalId).proc.kill("SIGKILL");
        return {};
      })
      .onRequest(M.client.terminal.release, (ctx) => {
        const t = this.terminal(ctx.params.terminalId);
        if (t.exit === undefined) t.proc.kill("SIGKILL");
        this.terminals.delete(ctx.params.terminalId);
        return {};
      })
      .onRequest(M.client.elicitation.create, (ctx) => {
        console.log(`  elicitation/create ${abbreviate(ctx.params)}`);
        if (ctx.params.mode === "form") return fixtures.elicitation.formAnswer;
        return { action: "accept" };
      })
      .onNotification(M.client.elicitation.complete, (ctx) => {
        console.log(`  elicitation/complete ${abbreviate(ctx.params)}`);
        this.elicitationCompletes.push({ ...ctx.params, at: Date.now() });
      })
      .onRequest(fixtures.ext.method, anyParams, (ctx) => {
        console.log(`  extension request ${fixtures.ext.method} ${abbreviate(ctx.params)}`);
        return fixtures.ext.result;
      })
      .onNotification(fixtures.ext.notification, anyParams, (ctx) => {
        console.log(`  extension notification ${fixtures.ext.notification} ${abbreviate(ctx.params)}`);
        this.extNotifications.push({ method: fixtures.ext.notification, params: ctx.params });
      });
  }

  onUpdate(n) {
    const list = this.updates.get(n.sessionId) ?? [];
    list.push(n.update);
    this.updates.set(n.sessionId, list);
    updatesTotal++;
    const kind = KINDS.has(n.update.sessionUpdate) ? n.update.sessionUpdate : "other";
    updatesByKind.set(kind, (updatesByKind.get(kind) ?? 0) + 1);
    console.log(`  update ${n.sessionId}: ${abbreviate(n.update, 200)}`);
  }

  async onPermission(ctx) {
    const p = ctx.params;
    const list = this.permissions.get(p.sessionId) ?? [];
    list.push(p);
    this.permissions.set(p.sessionId, list);
    console.log(`  permission request ${p.sessionId}: ${abbreviate(p.options)}`);
    const meta = p._meta != null ? { _meta: p._meta } : {};
    const hold = this.holdSessions.get(p.sessionId);
    if (hold) {
      // perm.cancelled: cancel the turn while the request is pending, then answer cancelled.
      hold.cancelAt = Date.now();
      await this.agent.notify(M.agent.session.cancel, { sessionId: p.sessionId });
      return { outcome: { outcome: "cancelled" }, ...meta };
    }
    const chosen = p.options.find((o) => o.kind === "allow_once") ?? p.options[0];
    return { outcome: { outcome: "selected", optionId: chosen.optionId }, ...meta };
  }

  async onRead(ctx) {
    const { path: file, line, limit } = ctx.params;
    console.log(`  fs/read_text_file ${abbreviate(ctx.params)}`);
    if (file.endsWith("slow.txt")) {
      // cancel-request.agent: wait up to 10 s, or until the agent cancels this request.
      const t0 = Date.now();
      await Promise.race([sleep(10_000), new Promise((resolve) => ctx.signal.addEventListener("abort", resolve, { once: true }))]);
      if (ctx.signal.aborted) {
        console.log(`  fs/read_text_file ${file}: cancelled by $/cancel_request after ${Date.now() - t0} ms`);
        throw ctx.signal.reason ?? RequestError.requestCancelled();
      }
      return { content: "slow" };
    }
    let content;
    try {
      content = await fsp.readFile(file, "utf8");
    } catch {
      throw RequestError.resourceNotFound(file);
    }
    if (line != null || limit != null) {
      const lines = content.split(/(?<=\n)/);
      const start = Math.max(0, (line ?? 1) - 1);
      content = lines.slice(start, limit != null ? start + limit : undefined).join("");
    }
    return { content };
  }

  terminalCreate(p) {
    const terminalId = `term-${this.terminals.size + 1}-${Date.now()}`;
    const proc = spawn(p.command, p.args ?? [], { cwd: p.cwd ?? undefined, stdio: ["ignore", "pipe", "pipe"] });
    const t = { proc, output: "", exit: undefined };
    proc.stdout.on("data", (d) => (t.output += d));
    proc.stderr.on("data", (d) => (t.output += d));
    t.exited = new Promise((resolve) => {
      const done = (code, signal) => {
        t.exit = { exitCode: code ?? null, signal: signal ?? null };
        resolve(t.exit);
      };
      proc.on("exit", done);
      proc.on("error", (e) => {
        t.output += String(e?.message ?? e);
        done(127, null);
      });
    });
    this.terminals.set(terminalId, t);
    console.log(`  terminal/create ${terminalId}: ${p.command} ${(p.args ?? []).join(" ")}`);
    return { terminalId };
  }

  terminal(id) {
    const t = this.terminals.get(id);
    if (!t) throw new RequestError(-32602, `unknown terminal ${id}`);
    return t;
  }

  request(method, params, options) {
    return withTimeout(this.agent.request(method, params, options), STEP_TIMEOUT, method);
  }

  notify(method, params) {
    return this.agent.notify(method, params);
  }

  initialize() {
    return this.request(M.agent.initialize, {
      protocolVersion: fixtures.client.protocolVersion,
      clientCapabilities: fixtures.client.clientCapabilities,
      clientInfo: fixtures.client.clientInfo,
    });
  }

  async newSession(cwd = dir) {
    const r = await this.request(M.agent.session.new, { cwd, mcpServers: [] });
    check(typeof r?.sessionId === "string" && r.sessionId.length > 0, () => `session/new returned no sessionId: ${abbreviate(r)}`);
    return r;
  }

  prompt(sessionId, text, extra = {}, options) {
    return this.request(M.agent.session.prompt, { sessionId, prompt: [{ type: "text", text }], ...extra }, options);
  }

  of(sid) {
    return this.updates.get(sid) ?? [];
  }

  /** The text of every agent_message_chunk received for the session, in order. */
  chunks(sid) {
    return this.of(sid)
      .filter((u) => u.sessionUpdate === "agent_message_chunk" && u.content?.type === "text")
      .map((u) => u.content.text);
  }

  async close() {
    this.connection.close();
    await withTimeout(this.wrapped.closeInner(), STEP_TIMEOUT, "transport close");
    if (this.proc) {
      this.proc.child.stdin.end();
      const exit = await Promise.race([this.proc.exited, sleep(5000).then(() => undefined)]);
      if (!exit) {
        this.proc.child.kill("SIGKILL");
        throw new StepFailure("the stdio agent did not exit within 5 s of the end of its stdin");
      }
      return `agent exited ${exit.code ?? exit.signal}`;
    }
    return "closed";
  }
}

const KINDS = new Set([
  "user_message_chunk",
  "agent_message_chunk",
  "agent_thought_chunk",
  "tool_call",
  "tool_call_update",
  "plan",
  "available_commands_update",
  "current_mode_update",
  "config_option_update",
  "session_info_update",
  "usage_update",
]);

function mainConn() {
  check(main != null, "no main connection: init.initialize did not run first");
  return main;
}

const isEmptyResult = (r) => r != null && typeof r === "object" && Object.keys(r).filter((k) => k !== "_meta").length === 0;

// ---------------------------------------------------------------- steps

/** A prompt on a new session of the main connection that must answer end_turn. */
async function promptNew(text, extra) {
  const c = mainConn();
  const { sessionId } = await c.newSession();
  const r = await c.prompt(sessionId, text, extra);
  return { c, sid: sessionId, r };
}

async function endTurn(text) {
  const x = await promptNew(text);
  check(x.r?.stopReason === "end_turn", `stopReason ${x.r?.stopReason}`);
  return x;
}

/** Waits for the first update of the session matching the predicate and returns it. */
async function updateOf(c, sid, predicate, failure, ms = UPDATE_GRACE) {
  let found;
  await waitFor(() => (found = c.of(sid).find(predicate)) !== undefined, () => `${failure}; updates: ${abbreviate(c.of(sid), 400)}`, ms);
  return found;
}

async function emitStep(kind, predicate, what, prompt = `#emit ${kind}`) {
  const { c, sid } = await endTurn(prompt);
  const u = await updateOf(c, sid, (u) => u.sessionUpdate === kind && predicate(u), `no ${kind} with ${what}`);
  return `${kind}: ${abbreviate(u, 200)}`;
}

async function echoCaps() {
  const { c, sid } = await endTurn("#echo-caps");
  let caps;
  await waitFor(
    () =>
      c.chunks(sid).some((t) => {
        try {
          caps = JSON.parse(t);
          return caps !== null && typeof caps === "object";
        } catch {
          return false;
        }
      }),
    () => `no JSON chunk in ${abbreviate(c.chunks(sid))}`,
  );
  return caps;
}

/** Starts a "#slow" prompt and waits for its first tick. */
async function startSlow(c, sid, text = "#slow", options) {
  const t0 = Date.now();
  const pending = c.prompt(sid, text, {}, options);
  let early;
  pending.then(
    (value) => (early = { ok: true, value }),
    (error) => (early = { ok: false, error }),
  );
  await waitFor(
    () => c.chunks(sid).includes("tick") || early !== undefined,
    "no tick within 5 s",
    5000,
  );
  check(c.chunks(sid).includes("tick"), () => `the ${text} prompt ended before its first tick: ${early.ok ? abbreviate(early.value) : describeError(early.error)}`);
  return { pending, t0 };
}

/** Settles a promise: { ok, value } or { ok: false, error }, or undefined after `ms`. */
function settle(promise, ms) {
  return Promise.race([
    promise.then(
      (value) => ({ ok: true, value }),
      (error) => ({ ok: false, error }),
    ),
    sleep(ms).then(() => undefined),
  ]);
}

const steps = {
  "init.initialize": async () => {
    main = Conn.open();
    const r = await main.initialize();
    main.init = r;
    check(r?.protocolVersion === 1, `protocolVersion ${r?.protocolVersion}`);
    return `protocolVersion=1 agentInfo=${abbreviate(r.agentInfo)}`;
  },
  "init.agent-capabilities": async () => {
    const caps = mainConn().init?.agentCapabilities;
    const sc = caps?.sessionCapabilities ?? {};
    check(caps?.loadSession === true, `loadSession ${caps?.loadSession}`);
    const missing = ["list", "resume", "close", "delete"].filter((k) => sc[k] == null);
    check(missing.length === 0, `sessionCapabilities lacks ${missing.join(", ")}: ${abbreviate(sc)}`);
    return `agentCapabilities ${abbreviate(caps, 200)}`;
  },
  "init.client-capabilities": async () => {
    const caps = await echoCaps();
    check(caps.fs?.readTextFile === true && caps.fs?.writeTextFile === true && caps.terminal === true, `echoed ${abbreviate(caps)}`);
    return `echoed ${abbreviate(caps, 200)}`;
  },
  "init.auth-methods": async () => {
    const m = mainConn().init?.authMethods ?? [];
    check(m.some((x) => x.id === "interop-auth"), `authMethods ${abbreviate(m)}`);
    return `authMethods ${m.map((x) => x.id).join(",")}`;
  },
  "init.agent-info": async () => {
    const info = mainConn().init?.agentInfo;
    check(typeof info?.name === "string" && info.name.startsWith("interop-"), `agentInfo ${abbreviate(info)}`);
    return `agentInfo ${info.name}`;
  },
  "init.config-boolean": async () => {
    const caps = await echoCaps();
    check(caps.session?.configOptions?.boolean != null, `echoed ${abbreviate(caps)}`);
    return "echoed session.configOptions.boolean";
  },
  "auth.authenticate": async () => {
    const r = await mainConn().request(M.agent.authenticate, { methodId: "interop-auth" });
    return `authenticate answered ${abbreviate(r)}`;
  },
  "auth.logout": async () => {
    const r = await mainConn().request(M.agent.logout, {});
    return `logout answered ${abbreviate(r)}`;
  },
  "auth.logout-capability": async () => {
    const caps = mainConn().init?.agentCapabilities;
    check(caps?.auth?.logout != null, `agentCapabilities.auth ${abbreviate(caps?.auth)}`);
    return "agentCapabilities.auth.logout present";
  },
  "auth.terminal": async () => {
    const m = mainConn().init?.authMethods ?? [];
    check(m.some((x) => x.type === "terminal" && x.id === "interop-terminal-auth"), `authMethods ${abbreviate(m)}`);
    return "terminal auth method listed";
  },
  "session.new": async () => {
    const r = await mainConn().newSession();
    return `sessionId=${r.sessionId}`;
  },
  "session.load": async () => {
    const c = mainConn();
    const { sessionId: sid } = await c.newSession();
    await c.prompt(sid, "hello load");
    await c.request(M.agent.session.load, { sessionId: sid, cwd: dir, mcpServers: [] });
    await waitFor(() => c.chunks(sid).includes("hello load"), "the first prompt's chunks did not arrive");
    const before = c.chunks(sid).length;
    const r = await c.prompt(sid, "after load");
    check(r?.stopReason === "end_turn", `stopReason ${r?.stopReason}`);
    await waitFor(() => c.chunks(sid).length > before, "no agent_message_chunk after the load");
    return `loaded ${sid}; prompt after load end_turn`;
  },
  "session.load-replay": async () => {
    const c = mainConn();
    const { sessionId: sid } = await c.newSession();
    await c.prompt(sid, "replay me");
    await waitFor(() => c.chunks(sid).join("") === "echo: replay me", () => `the prompt's chunks ${abbreviate(c.chunks(sid))}`);
    const from = c.of(sid).length;
    await c.request(M.agent.session.load, { sessionId: sid, cwd: dir, mcpServers: [] });
    const replay = () => c.of(sid).slice(from);
    await waitFor(
      () =>
        replay().some((u) => u.sessionUpdate === "user_message_chunk" && u.content?.text === "replay me") &&
        replay().some((u) => u.sessionUpdate === "agent_message_chunk" && u.content?.text?.includes("replay me")),
      () => `replay ${abbreviate(replay())}`,
      REPLAY_GRACE,
    );
    return `replayed ${replay().length} update(s)`;
  },
  "session.resume": async () => {
    const c = mainConn();
    const { sessionId: sid } = await c.newSession();
    await c.prompt(sid, "before resume");
    await waitFor(() => c.chunks(sid).join("") === "echo: before resume", () => `the prompt's chunks ${abbreviate(c.chunks(sid))}`);
    const from = c.of(sid).length;
    await c.request(M.agent.session.resume, { sessionId: sid, cwd: dir });
    await sleep(UPDATE_GRACE);
    const during = c.of(sid).slice(from);
    check(during.length === 0, () => `${during.length} update(s) after the resume: ${abbreviate(during)}`);
    const r = await c.prompt(sid, "after resume");
    check(r?.stopReason === "end_turn", `after resume: stopReason ${r?.stopReason}`);
    return "resumed without replay; prompt after resume end_turn";
  },
  "session.list": async () => {
    const c = mainConn();
    const cwd = path.join(dir, "list");
    await fsp.mkdir(cwd, { recursive: true });
    const { sessionId: sid } = await c.newSession(cwd);
    let cursor;
    const seen = [];
    for (let page = 0; page < 10; page++) {
      const r = await c.request(M.agent.session.list, cursor ? { cwd, cursor } : { cwd });
      seen.push(...(r?.sessions ?? []));
      cursor = r?.nextCursor;
      if (!cursor) break;
    }
    const found = seen.find((s) => s.sessionId === sid);
    check(found != null, () => `${sid} not listed: ${abbreviate(seen)}`);
    check(found.cwd === cwd, `listed with cwd ${found.cwd}`);
    return `listed among ${seen.length} session(s)`;
  },
  "session.close": async () => {
    const c = mainConn();
    const { sessionId: sid } = await c.newSession();
    const { pending } = await startSlow(c, sid);
    const r = await c.request(M.agent.session.close, { sessionId: sid });
    const tClose = Date.now();
    check(isEmptyResult(r), `close answered ${abbreviate(r)}`);
    const ended = await settle(pending, 5000);
    check(ended !== undefined, "the #slow prompt did not end within 5 s of the close");
    check(!ended.ok || ended.value?.stopReason === "cancelled", `the #slow prompt answered ${abbreviate(ended.value)}`);
    const how = ended.ok ? `stopReason ${ended.value.stopReason}` : describeError(ended.error);
    const after = await settle(c.prompt(sid, "after close"), STEP_TIMEOUT);
    check(after !== undefined && !after.ok, () => `the prompt after close ${after ? `answered ${abbreviate(after.value)}` : "TIMEOUT"}`);
    return `closed; #slow ended (${how}) ${Date.now() - tClose} ms later; prompt after close failed: ${describeError(after.error)}`;
  },
  "session.delete": async () => {
    // Over HTTP the TypeScript client posts session/delete session-scoped (Acp-Session-Id:
    // no-such-session), and any non-2xx POST errors its whole stream (http-stream.ts
    // postConnectedMessage -> errorReadable). The step runs on its own connection there, so a
    // server that answers 404 fails this step only, not every step after it.
    const own = transport === "http";
    const c = own ? Conn.open() : mainConn();
    try {
      if (own) await c.initialize();
      const { sessionId: sid } = await c.newSession();
      await c.request(M.agent.session.delete, { sessionId: sid });
      await c.request(M.agent.session.delete, { sessionId: "no-such-session" });
      return `both deletes answered${own ? " (own connection)" : ""}`;
    } finally {
      if (own) await c.close().catch(() => {});
    }
  },
  "session.multi": async () => {
    const c = mainConn();
    const a = (await c.newSession()).sessionId;
    const b = (await c.newSession()).sessionId;
    const [ra, rb] = await Promise.all([c.prompt(a, "multi A"), c.prompt(b, "multi B")]);
    check(ra?.stopReason === "end_turn" && rb?.stopReason === "end_turn", `stopReasons ${ra?.stopReason} ${rb?.stopReason}`);
    await waitFor(
      () => c.chunks(a).join("") === "echo: multi A" && c.chunks(b).join("") === "echo: multi B",
      () => `chunks A ${abbreviate(c.chunks(a))} B ${abbreviate(c.chunks(b))}`,
    );
    return "both end_turn; chunks routed to their sessions";
  },
  "session.fork": async () => {
    const c = mainConn();
    const { sessionId: sid } = await c.newSession();
    await c.prompt(sid, "before fork");
    const f = await c.request(M.agent.session.fork, { sessionId: sid, cwd: dir, mcpServers: [] });
    check(typeof f?.sessionId === "string" && f.sessionId !== sid, `fork returned ${abbreviate(f)}`);
    const r = await c.prompt(f.sessionId, "in fork");
    check(r?.stopReason === "end_turn", `in fork: stopReason ${r?.stopReason}`);
    return `forked ${sid} -> ${f.sessionId}`;
  },
  "update.agent_message_chunk": async () => {
    const { c, sid } = await endTurn("hello");
    await waitFor(() => c.chunks(sid).join("") === "echo: hello", () => `chunks ${abbreviate(c.chunks(sid))} do not spell "echo: hello"`);
    return 'end_turn; chunks spell "echo: hello"';
  },
  "update.user_message_chunk": () => emitStep("user_message_chunk", (u) => u.content?.text === "user-chunk", 'text "user-chunk"'),
  "update.agent_thought_chunk": () => emitStep("agent_thought_chunk", (u) => u.content?.text === "thinking", 'text "thinking"'),
  "update.tool_call": () =>
    emitStep(
      "tool_call",
      (u) => u.toolCallId === "call-1" && u.title === "interop tool" && u.kind === "read" && u.status === "pending",
      "toolCallId call-1, title interop tool, kind read, status pending",
    ),
  "update.tool_call_update": async () => {
    const { c, sid } = await endTurn("#emit tool_call_update");
    const ok = () => {
      const us = c.of(sid);
      const i = us.findIndex((u) => u.sessionUpdate === "tool_call" && u.toolCallId === "call-1");
      const j = us.findIndex(
        (u) =>
          u.sessionUpdate === "tool_call_update" &&
          u.toolCallId === "call-1" &&
          u.status === "completed" &&
          (u.content ?? []).some((x) => x.type === "content" && x.content?.text === "tool output"),
      );
      return i >= 0 && j > i;
    };
    await waitFor(ok, () => `updates ${abbreviate(c.of(sid), 400)}`);
    return "tool_call then tool_call_update completed with \"tool output\"";
  },
  "update.tool_call-name": () => emitStep("tool_call", (u) => u.name === "read_file", 'name "read_file"', "#emit tool_call name=read_file"),
  "update.plan": () =>
    emitStep(
      "plan",
      (u) =>
        Array.isArray(u.entries) &&
        u.entries.length === 2 &&
        u.entries.every((e, i) => {
          const f = fixtures.emit.plan.entries[i];
          return e.content === f.content && e.priority === f.priority && e.status === f.status;
        }),
      "the two fixture entries",
    ),
  "update.available_commands_update": () =>
    emitStep(
      "available_commands_update",
      (u) => u.availableCommands?.length === 1 && u.availableCommands[0].name === "interop" && u.availableCommands[0].input?.hint === "args",
      'one command "interop" with input hint "args"',
    ),
  "update.current_mode_update": () => emitStep("current_mode_update", (u) => u.currentModeId === "interop-mode-b", "currentModeId interop-mode-b"),
  "update.config_option_update": () =>
    emitStep("config_option_update", (u) => (u.configOptions ?? []).some((o) => o.id === "model" && o.currentValue === "model-b"), "model at model-b"),
  "update.session_info_update": () => emitStep("session_info_update", (u) => u.title === "interop title", 'title "interop title"'),
  "update.usage_update": () =>
    emitStep(
      "usage_update",
      (u) => u.used === 100 && u.size === 1000 && u.cost?.amount === 0.01 && u.cost?.currency === "USD",
      "used 100, size 1000, cost 0.01 USD",
    ),
  "update.unknown": async () => {
    const { c, sid } = await endTurn("#emit unknown");
    await waitFor(() => c.chunks(sid).includes("after-unknown"), () => `no "after-unknown" chunk in ${abbreviate(c.chunks(sid))}`);
    const surfaced = c.of(sid).some((u) => u.sessionUpdate === "interop_future_update");
    return `"after-unknown" arrived; the unknown update was ${surfaced ? "surfaced" : "dropped"}`;
  },
  "stop.max_tokens": () => stopStep("max_tokens"),
  "stop.refusal": () => stopStep("refusal"),
  "stop.max_turn_requests": () => stopStep("max_turn_requests"),
  "mode.set": async () => {
    const c = mainConn();
    const s = await c.newSession();
    check((s.modes?.availableModes ?? []).some((m) => m.id === "interop-mode-b"), `session/new modes ${abbreviate(s.modes)}`);
    await c.request(M.agent.session.setMode, { sessionId: s.sessionId, modeId: "interop-mode-b" });
    return "set_mode interop-mode-b answered";
  },
  "config.on-new": async () => {
    const s = await mainConn().newSession();
    const model = (s.configOptions ?? []).find((o) => o.id === "model");
    check(model?.type === "select" && model.currentValue === "model-a", `configOptions ${abbreviate(s.configOptions)}`);
    return "model select at model-a";
  },
  "config.select": async () => {
    const c = mainConn();
    const s = await c.newSession();
    const r = await c.request(M.agent.session.setConfigOption, { sessionId: s.sessionId, configId: "model", value: "model-b" });
    const opts = r?.configOptions ?? [];
    check(opts.some((o) => o.id === "model" && o.currentValue === "model-b"), `configOptions ${abbreviate(opts)}`);
    if (Array.isArray(s.configOptions)) {
      const want = s.configOptions.map((o) => o.id).sort().join(",");
      const got = opts.map((o) => o.id).sort().join(",");
      check(want === got, `the response lists ${got}, session/new listed ${want}`);
    }
    return `complete list (${opts.map((o) => o.id).join(",")}) with model at model-b`;
  },
  "config.boolean": async () => {
    const c = mainConn();
    const s = await c.newSession();
    const r = await c.request(M.agent.session.setConfigOption, { sessionId: s.sessionId, configId: "verbose", type: "boolean", value: true });
    check((r?.configOptions ?? []).some((o) => o.id === "verbose" && o.currentValue === true), `configOptions ${abbreviate(r?.configOptions)}`);
    return "verbose at true";
  },
  "perm.selected": async () => {
    const { c, sid } = await endTurn("#permission allow");
    await waitFor(() => c.chunks(sid).includes("permission: selected allow"), () => `no chunk "permission: selected allow" in ${abbreviate(c.chunks(sid))}`);
    const asked = c.permissions.get(sid) ?? [];
    check(asked.length === 1 && asked[0].options?.length > 0, `${asked.length} permission requests, expected 1 with options`);
    return "one permission request; selected allow; end_turn";
  },
  "perm.cancelled": async () => {
    const c = mainConn();
    const { sessionId: sid } = await c.newSession();
    const hold = {};
    c.holdSessions.set(sid, hold);
    const r = await c.prompt(sid, "#permission hold");
    const done = Date.now();
    check(hold.cancelAt !== undefined, "no permission request arrived");
    check(r?.stopReason === "cancelled", `stopReason ${r?.stopReason}`);
    check(done - hold.cancelAt <= 5000, `answered ${done - hold.cancelAt} ms after the cancel`);
    return `cancelled ${done - hold.cancelAt} ms after the cancel`;
  },
  "fs.write": async () => {
    const file = path.join(dir, "fs-write.txt");
    const { c, sid } = await endTurn(`#fs write ${file} interop write`);
    await waitFor(() => c.chunks(sid).includes("fs write ok"), () => `no chunk "fs write ok" in ${abbreviate(c.chunks(sid))}`);
    const content = await fsp.readFile(file, "utf8").catch(() => undefined);
    check(content === "interop write", `file content ${JSON.stringify(content)}`);
    return 'written through the client: "interop write"';
  },
  "fs.read": async () => {
    const file = path.join(dir, "fs-read.txt");
    await fsp.writeFile(file, fixtures.fsReadContent, "utf8");
    const { c, sid } = await promptNew(`#fs read ${file}`);
    await waitFor(() => c.chunks(sid).includes(fixtures.fsReadContent), () => `chunks ${abbreviate(c.chunks(sid))}`);
    return "the agent read the fixture content";
  },
  "fs.read-range": async () => {
    const file = path.join(dir, "fs-read.txt");
    await fsp.writeFile(file, fixtures.fsReadContent, "utf8");
    const { c, sid } = await promptNew(`#fs read ${file} line=2 limit=1`);
    await waitFor(() => c.chunks(sid).some((t) => t.trim() === "line2"), () => `chunks ${abbreviate(c.chunks(sid))}`);
    return 'the agent read "line2"';
  },
  "fs.read-missing": async () => {
    const { c, sid, r } = await promptNew(`#fs read ${path.join(dir, "no-such-file.txt")}`);
    check(r?.stopReason === "end_turn", `stopReason ${r?.stopReason}`);
    await waitFor(() => c.chunks(sid).some((t) => t.startsWith("fs read error")), () => `chunks ${abbreviate(c.chunks(sid))}`);
    return `agent saw ${c.chunks(sid).find((t) => t.startsWith("fs read error"))}`;
  },
  "term.run": async () => {
    const { c, sid } = await promptNew("#terminal run echo hi");
    await waitFor(() => c.chunks(sid).includes("terminal: hi exit=0"), () => `chunks ${abbreviate(c.chunks(sid))}`);
    return '"terminal: hi exit=0"';
  },
  "term.kill": async () => {
    const t0 = Date.now();
    const { c, sid } = await promptNew("#terminal kill sleep 30");
    await waitFor(() => c.chunks(sid).includes("terminal killed"), () => `chunks ${abbreviate(c.chunks(sid))}`);
    const ms = Date.now() - t0;
    check(ms <= 5000, `"terminal killed" after ${ms} ms`);
    return `"terminal killed" after ${ms} ms`;
  },
  "elicit.form": async () => {
    const { c, sid } = await promptNew("#elicit form");
    let content;
    await waitFor(
      () =>
        c.chunks(sid).some((t) => {
          const m = /^elicit: accept (.*)$/.exec(t);
          if (!m) return false;
          try {
            content = JSON.parse(m[1]);
          } catch {
            return false;
          }
          return content?.name === "interop" && Object.keys(content).length === 1;
        }),
      () => `chunks ${abbreviate(c.chunks(sid))}`,
    );
    return `elicit: accept ${JSON.stringify(content)}`;
  },
  "elicit.complete": async () => {
    const c = mainConn();
    const { sessionId: sid } = await c.newSession();
    const from = c.elicitationCompletes.length;
    await c.prompt(sid, "#elicit url");
    await waitFor(
      () => c.elicitationCompletes.slice(from).some((n) => n.elicitationId === "elic-1"),
      () => `no elicitation/complete for elic-1; chunks ${abbreviate(c.chunks(sid))}`,
    );
    return "elicitation/complete elic-1 arrived";
  },
  "cancel.prompt": async () => {
    const c = mainConn();
    const { sessionId: sid } = await c.newSession();
    const { pending } = await startSlow(c, sid);
    await c.notify(M.agent.session.cancel, { sessionId: sid });
    const tCancel = Date.now();
    const ended = await settle(pending, 5000);
    check(ended !== undefined, "TIMEOUT: no answer within 5 s of the cancel");
    check(ended.ok, () => `the prompt failed: ${describeError(ended.error)}`);
    check(ended.value?.stopReason === "cancelled", `stopReason ${ended.value?.stopReason}`);
    return `cancelled ${Date.now() - tCancel} ms after the cancel`;
  },
  "cancel.prompt-while-cancelling": async () => {
    const c = mainConn();
    const { sessionId: sid } = await c.newSession();
    const { pending } = await startSlow(c, sid, "#slow grace=1000");
    await c.notify(M.agent.session.cancel, { sessionId: sid });
    const during = await settle(c.prompt(sid, "during cancel"), STEP_TIMEOUT);
    const first = await settle(pending, 5000);
    check(first !== undefined && first.ok && first.value?.stopReason === "cancelled", () => `the first prompt: ${first ? (first.ok ? abbreviate(first.value) : describeError(first.error)) : "TIMEOUT"}`);
    check(during !== undefined && !during.ok && during.error?.code === -32600, () => `"during cancel": ${during ? (during.ok ? `answered ${abbreviate(during.value)}` : describeError(during.error)) : "TIMEOUT"}`);
    const after = await c.prompt(sid, "after cancel");
    check(after?.stopReason === "end_turn", `"after cancel": stopReason ${after?.stopReason}`);
    return 'cancelled; "during cancel" -32600; "after cancel" end_turn';
  },
  "cancel.grace": async () => {
    const c = mainConn();
    const { sessionId: sid } = await c.newSession();
    const pending = c.prompt(sid, "#hang");
    pending.catch(() => {});
    await sleep(200);
    await c.notify(M.agent.session.cancel, { sessionId: sid });
    const tCancel = Date.now();
    const ended = await settle(pending, 6000);
    check(ended !== undefined, "TIMEOUT: no answer within 6 s of the cancel");
    check(ended.ok && ended.value?.stopReason === "cancelled", () => (ended.ok ? `stopReason ${ended.value?.stopReason}` : describeError(ended.error)));
    return `the SDK answered cancelled ${Date.now() - tCancel} ms after the cancel`;
  },
  "cancel-request.client": async () => {
    const c = mainConn();
    const { sessionId: sid } = await c.newSession();
    const abort = new AbortController();
    const { pending } = await startSlow(c, sid, "#slow", { cancellationSignal: abort.signal });
    abort.abort();
    const tCancel = Date.now();
    const ended = await settle(pending, 5000);
    check(ended !== undefined, "TIMEOUT: the prompt did not end within 5 s of $/cancel_request");
    if (ended.ok) {
      check(ended.value?.stopReason === "cancelled", `stopReason ${ended.value?.stopReason}`);
      return `stopReason cancelled ${Date.now() - tCancel} ms after $/cancel_request`;
    }
    check(ended.error?.code === -32800, () => `the prompt failed: ${describeError(ended.error)}`);
    return `-32800 ${Date.now() - tCancel} ms after $/cancel_request`;
  },
  "cancel-request.agent": async () => {
    const t0 = Date.now();
    const { c, sid, r } = await promptNew(`#fs read-slow ${path.join(dir, "slow.txt")}`);
    const ms = Date.now() - t0;
    check(r?.stopReason === "end_turn", `stopReason ${r?.stopReason}`);
    await waitFor(() => c.chunks(sid).includes("cancel-request sent"), () => `chunks ${abbreviate(c.chunks(sid))}`);
    check(ms <= 5000, `end_turn after ${ms} ms`);
    return `"cancel-request sent" and end_turn after ${ms} ms`;
  },
  "cancel-request.unknown": async () => {
    const c = mainConn();
    const strays = c.wrapped.strays.length;
    await c.newSession();
    const done = [...c.wrapped.sent].reverse().find((s) => s.method === M.agent.session.new);
    check(done !== undefined, "no recorded session/new request id");
    await c.notify(M.protocol.cancelRequest, { requestId: 999999 });
    await c.notify(M.protocol.cancelRequest, { requestId: done.id });
    const { sessionId: sid } = await c.newSession();
    const r = await c.prompt(sid, "after cancel-request");
    check(r?.stopReason === "end_turn", `stopReason ${r?.stopReason}`);
    await sleep(200);
    const errors = c.wrapped.strays.slice(strays);
    check(errors.length === 0, () => `error responses came back: ${abbreviate(errors)}`);
    return `cancelled 999999 and the completed request ${done.id}; no error; the next prompt end_turn`;
  },
  "ext.agent-request": async () => {
    const { c, sid } = await promptNew("#ext request _interop/ping");
    await waitFor(() => c.chunks(sid).includes('ext: {"pong":1}'), () => `chunks ${abbreviate(c.chunks(sid))}`);
    return 'ext: {"pong":1}';
  },
  "ext.agent-notification": async () => {
    const c = mainConn();
    const from = c.extNotifications.length;
    await promptNew("#ext notify _interop/note");
    await waitFor(
      () => c.extNotifications.slice(from).some((n) => n.params?.n === 1),
      () => `notifications ${abbreviate(c.extNotifications.slice(from))}`,
    );
    return "_interop/note arrived with {n: 1}";
  },
  "ext.client-request": async () => {
    const r = await mainConn().request(fixtures.ext.method, fixtures.ext.params);
    check(r?.pong === 1, `result ${abbreviate(r)}`);
    return `result ${JSON.stringify(r)}`;
  },
  "ext.client-notification": async () => {
    const c = mainConn();
    await c.notify(fixtures.ext.notification, fixtures.ext.params);
    const { sid } = await promptNew("#ext last-notification");
    await waitFor(() => c.chunks(sid).includes("ext last: _interop/note"), () => `chunks ${abbreviate(c.chunks(sid))}`);
    return "ext last: _interop/note";
  },
  "meta.prompt": async () => {
    const { c, sid, r } = await promptNew("#meta", { _meta: fixtures.meta });
    const u = await updateOf(c, sid, (u) => u.sessionUpdate === "agent_message_chunk" && u.content?.text === "meta", 'no "meta" chunk');
    check(u._meta?.interop === "m1", `the "meta" chunk's _meta ${abbreviate(u._meta)}`);
    check(r?._meta?.interop === "m1", `the PromptResponse _meta ${abbreviate(r?._meta)}`);
    return "_meta round trip on the update and the response";
  },
  "meta.permission": async () => {
    const { c, sid } = await promptNew("#permission allow meta");
    const asked = c.permissions.get(sid) ?? [];
    check(asked.length === 1, `${asked.length} permission requests`);
    check(asked[0]._meta?.interop === "m1", `the request _meta ${abbreviate(asked[0]._meta)}`);
    return "the permission request carried _meta; echoed in the response";
  },
  "error.method-not-found": async () => {
    const c = mainConn();
    const r = await settle(c.request("interop/no_such_method", {}), STEP_TIMEOUT);
    check(r !== undefined && !r.ok, () => `interop/no_such_method ${r ? `answered ${abbreviate(r.value)}` : "TIMEOUT"}`);
    check(r.error?.code === -32601, `interop/no_such_method failed with ${describeError(r.error)}`);
    await c.newSession();
    return "-32601; session/new after it succeeded";
  },
  "big.prompt-1m": () => bigPrompt(1048576),
  "big.update-1m": () => bigUpdate(1048576),
  "big.prompt-8m": () => bigPrompt(8388608),
  "big.update-8m": () => bigUpdate(8388608),
  "http.reconnect": async () => {
    check(transport === "http", `http.reconnect does not apply to ${transport}`);
    const first = Conn.open();
    let sid;
    try {
      await first.initialize();
      sid = (await first.newSession()).sessionId;
      const r = await first.prompt(sid, "before reconnect");
      check(r?.stopReason === "end_turn", `before reconnect: stopReason ${r?.stopReason}`);
    } finally {
      await first.close().catch(() => {});
    }
    const second = Conn.open();
    try {
      await second.initialize();
      await second.request(M.agent.session.load, { sessionId: sid, cwd: dir, mcpServers: [] });
      const r = await second.prompt(sid, "after reconnect");
      check(r?.stopReason === "end_turn", `after reconnect: stopReason ${r?.stopReason}`);
    } finally {
      await second.close().catch(() => {});
    }
    return `session ${sid} loaded and prompted on a new connection`;
  },
  "stdio.eof-exit": async () => {
    check(transport === "stdio", `stdio.eof-exit does not apply to ${transport}`);
    const p = spawnAgent();
    const connection = acp.client({ name: "interop-typescript-client-eof" }).connect(p.stream);
    try {
      const r = await withTimeout(
        connection.agent.request(M.agent.initialize, {
          protocolVersion: 1,
          clientCapabilities: {},
          clientInfo: fixtures.client.clientInfo,
        }),
        STEP_TIMEOUT,
        "initialize",
      );
      check(r?.protocolVersion === 1, `protocolVersion ${r?.protocolVersion}`);
      p.child.stdin.end();
      const tEof = Date.now();
      const exit = await Promise.race([p.exited, sleep(5000).then(() => undefined)]);
      if (!exit) {
        p.child.kill("SIGKILL");
        throw new StepFailure("no exit on EOF");
      }
      return `exited (${exit.code ?? exit.signal}) ${Date.now() - tEof} ms after EOF`;
    } finally {
      connection.close();
    }
  },
  "conn.close": async () => mainConn().close(),
};

async function stopStep(reason) {
  const { r } = await promptNew(`#stop ${reason}`);
  check(r?.stopReason === reason, `stopReason ${r?.stopReason}`);
  return `stopReason ${reason}`;
}

async function bigPrompt(n) {
  const { c, sid } = await promptNew(`#len ${"x".repeat(n)}`);
  await waitFor(() => c.chunks(sid).includes(`len=${n}`), () => `chunks ${abbreviate(c.chunks(sid))}`);
  return `len=${n}`;
}

async function bigUpdate(n) {
  const { c, sid } = await promptNew(`#big ${n}`);
  await waitFor(() => c.chunks(sid).some((t) => t.length === n && /^x+$/.test(t)), () => `chunk lengths ${c.chunks(sid).map((t) => t.length).join(",")}`);
  return `one chunk of ${n} characters`;
}

// ---------------------------------------------------------------- run

async function runStep(id) {
  const body = steps[id];
  if (!body) {
    fail++;
    console.log(`STEP ${id} FAIL (0 ms) -> unknown-step`);
    return;
  }
  const t0 = Date.now();
  let outcome;
  let detail;
  try {
    detail = await withTimeout(Promise.resolve().then(body), STEP_TIMEOUT + 5000, id);
    outcome = "PASS";
    pass++;
  } catch (e) {
    outcome = "FAIL";
    fail++;
    detail = e instanceof StepFailure || e instanceof Timeout ? e.message : describeError(e);
  }
  console.log(`STEP ${id} ${outcome} (${Date.now() - t0} ms) -> ${abbreviate(String(detail), 600)}`);
}

process.on("unhandledRejection", (e) => console.log("  unhandled rejection:", describeError(e)));

dir = await fsp.mkdtemp(path.join(os.tmpdir(), "acp-interop-"));
for (const id of process.env.STEPS.split(",").map((s) => s.trim()).filter(Boolean)) {
  await runStep(id);
}
const kinds = [...updatesByKind.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([k, v]) => ` upd_${k}=${v}`).join("");
console.log(`RESULT pass=${pass} fail=${fail} updates_total=${updatesTotal}${kinds}`);
if (main?.proc) main.proc.child.kill("SIGKILL");
process.exit(0);

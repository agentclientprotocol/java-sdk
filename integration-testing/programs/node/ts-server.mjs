// Interop agent on the TypeScript SDK's Streamable HTTP server, wired the way the SDK's example
// ships it: the HTTP handler plus the WebSocket upgrade handler on the same port. (That upgrade
// listener is what turned the JDK's "Upgrade: h2c" into a 405 before the Java client pinned
// HTTP/1.1 after its probe.) Env: TS_SDK (built typescript-sdk checkout), PORT, NO_WS=1 to drop
// the upgrade listener. Prints "READY <port>" on stdout once listening; one [http] line per request.
import { createServer } from "node:http";
import { createRequire } from "node:module";
import { pathToFileURL } from "node:url";
import path from "node:path";

const sdk = process.env.TS_SDK;
if (!sdk) { console.error("TS_SDK is not set"); process.exit(2); }
const load = (f) => import(pathToFileURL(path.join(sdk, "dist", f)).href);
const acp = await load("acp.js");
const { createNodeHttpHandler, createNodeWebSocketUpgradeHandler } = await load("node-adapter.js");
const { AcpServer } = await load("server.js");
const { WebSocketServer } = createRequire(path.join(sdk, "package.json"))("ws");

const sessions = new Set();
const chunk = (sessionId, text) => ({ sessionId, update: { sessionUpdate: "agent_message_chunk", content: { type: "text", text } } });
const agent = acp.agent({ name: "ts-interop-agent" })
  .onRequest(acp.methods.agent.initialize, () => {
    console.error("[agent] initialize");
    return { protocolVersion: acp.PROTOCOL_VERSION, agentCapabilities: { loadSession: true } };
  })
  .onRequest(acp.methods.agent.session.new, () => {
    const id = crypto.randomUUID(); sessions.add(id);
    console.error("[agent] session/new", id);
    return { sessionId: id };
  })
  .onRequest(acp.methods.agent.session.load, async (ctx) => {
    console.error("[agent] session/load", ctx.params.sessionId);
    if (!sessions.has(ctx.params.sessionId)) throw new Error("not found");
    await ctx.client.notify(acp.methods.client.session.update, chunk(ctx.params.sessionId, "replayed history"));
    return {};
  })
  .onRequest(acp.methods.agent.session.prompt, async (ctx) => {
    const sid = ctx.params.sessionId;
    const text = ctx.params.prompt.map((b) => b.text ?? "").join(" ");
    console.error("[agent] session/prompt", text);
    if (text.includes("permission")) {
      const r = await ctx.client.request(acp.methods.client.session.requestPermission, {
        sessionId: sid,
        toolCall: { toolCallId: "tc-1", title: "Run thing", kind: "execute", status: "pending" },
        options: [{ optionId: "allow_once", name: "Allow once", kind: "allow_once" },
                  { optionId: "reject_once", name: "Reject once", kind: "reject_once" }] });
      console.error("[agent] permission result", JSON.stringify(r));
      await ctx.client.notify(acp.methods.client.session.update, chunk(sid, "permission: " + JSON.stringify(r.outcome)));
    }
    await ctx.client.notify(acp.methods.client.session.update, chunk(sid, "echo: "));
    await ctx.client.notify(acp.methods.client.session.update, chunk(sid, text));
    return { stopReason: "end_turn" };
  })
  .onNotification(acp.methods.agent.session.cancel, () => {});

const acpServer = new AcpServer({ agent });
const handler = createNodeHttpHandler(acpServer);
const wss = new WebSocketServer({ noServer: true });
const wsUpgrade = createNodeWebSocketUpgradeHandler(acpServer, wss);
const port = Number(process.env.PORT ?? 0);
const server = createServer((req, res) => {
  const t0 = Date.now();
  const h = req.headers;
  const line = `[http] ${req.method} ${req.url} HTTP/${req.httpVersion} ct=${h["content-type"]} accept=${h["accept"]} conn=${h["acp-connection-id"]} sess=${h["acp-session-id"]} upgrade=${h["upgrade"]}`;
  res.on("finish", () => console.error(`${line} -> ${res.statusCode} ct=${res.getHeader("content-type")} ${Date.now() - t0}ms`));
  if (new URL(req.url, "http://x").pathname !== "/acp") { res.writeHead(404); res.end(); return; }
  handler(req, res);
});
if (!process.env.NO_WS) server.on("upgrade", (req, socket, head) => {
  console.error(`[upgrade] ${req.method} ${req.url} HTTP/${req.httpVersion} upgrade=${req.headers["upgrade"]}`);
  wsUpgrade(req, socket, head);
});
server.on("clientError", (err) => console.error("[clientError]", err.message));
server.listen(port, "127.0.0.1", () => {
  console.error(`listening http://127.0.0.1:${server.address().port}/acp`);
  console.log(`READY ${server.address().port}`);
});

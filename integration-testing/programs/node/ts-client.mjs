// Interop client on the TypeScript SDK's Streamable HTTP client. Usage: node ts-client.mjs <url>,
// env TS_SDK (built typescript-sdk checkout). Prints one "STEP <name> PASS|FAIL" line per step and
// a RESULT line with pass/fail counts, updates per step and in total. Per-step counts are
// indicative only: nothing orders an update's dispatch against the response that follows it. The DELETE happens when connectWith
// returns (the SDK closes the stream itself); the server-side log is where scenarios assert on it.
import { pathToFileURL } from "node:url";
import path from "node:path";

const sdk = process.env.TS_SDK;
if (!sdk) { console.error("TS_SDK is not set"); process.exit(2); }
const load = (f) => import(pathToFileURL(path.join(sdk, "dist", f)).href);
const acp = await load("acp.js");
const { MemoryAcpCookieStore, createHttpStream } = await load("http-stream.js");

const url = process.argv[2];
const stream = createHttpStream(url, { cookieStore: new MemoryAcpCookieStore() });
let pass = 0, fail = 0, updates = 0, total = 0;
const perStep = {};
const key = (n) => n.replace(/[^A-Za-z0-9]+/g, "_") + "_updates";
const step = async (name, fn) => {
  updates = 0; const t0 = Date.now();
  try {
    const r = await fn(); pass++;
    console.log(`STEP ${name} PASS (${Date.now() - t0} ms) -> ${JSON.stringify(r)}`); return r;
  } catch (e) {
    fail++;
    console.log(`STEP ${name} FAIL (${Date.now() - t0} ms) -> ${e?.stack ?? JSON.stringify(e)}`); return undefined;
  } finally { perStep[key(name)] = updates; }
};
const withTimeout = (p, ms = 15000) => Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(new Error("TIMEOUT " + ms)), ms))]);
let exitCode = 0;
try {
  await withTimeout(acp.client({ name: "ts-interop-client" })
    .onRequest(acp.methods.client.session.requestPermission, (ctx) => {
      console.log("  permission request", JSON.stringify(ctx.params.options));
      return { outcome: { outcome: "selected", optionId: ctx.params.options[0].optionId } };
    })
    .onNotification(acp.methods.client.session.update, (ctx) => { updates++; total++; console.log("  update:", JSON.stringify(ctx.params.update)); })
    .connectWith(stream, async (ctx) => {
      await step("initialize", () => withTimeout(ctx.request(acp.methods.agent.initialize, { protocolVersion: acp.PROTOCOL_VERSION, clientCapabilities: {} })));
      const s = await step("session/new", () => withTimeout(ctx.request(acp.methods.agent.session.new, { cwd: process.cwd(), mcpServers: [] })));
      const sid = s?.sessionId ?? "missing";
      const p = (t) => withTimeout(ctx.request(acp.methods.agent.session.prompt, { sessionId: sid, prompt: [{ type: "text", text: t }] }));
      await step("prompt1", () => p("hello one"));
      await step("prompt2", () => p("hello two"));
      await step("prompt3-permission", () => p("please ask permission"));
      await step("session/load", () => withTimeout(ctx.request(acp.methods.agent.session.load, { sessionId: sid, cwd: process.cwd(), mcpServers: [] })));
      await step("prompt-after-load", () => p("after load"));
    }), 60000);
  console.log("connectWith returned");
} catch (e) {
  console.log("connectWith failed:", e?.stack ?? e); exitCode = 1;
}
console.log(`RESULT pass=${pass} fail=${fail} updates_total=${total} ` + Object.entries(perStep).map(([k, v]) => `${k}=${v}`).join(" "));
process.exit(exitCode);

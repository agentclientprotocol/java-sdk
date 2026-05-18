import { createServer, IncomingMessage } from "node:http";
import { ACP_PATH, JsonRpcMessage } from "./protocol.js";
import { createScenario } from "./scenarios.js";
import { TranscriptRecorder } from "./transcript.js";

const scenarioName = readArg("--scenario") ?? "happy-path";
const port = Number(readArg("--port") ?? "0");
const recorder = new TranscriptRecorder();
const scenario = createScenario(scenarioName);

const server = createServer();

server.on("request", async (request, response) => {
  const path = request.url ?? "";
  if (path === "/__test/transcript") {
    response.writeHead(200, {
      "content-type": "application/json",
    });
    response.end(recorder.serialize());
    return;
  }

  if (path !== ACP_PATH) {
    response.writeHead(404);
    response.end();
    return;
  }

  const body = await readJsonBody(request);
  scenario.handle(response, request.headers, request.method ?? "", body, recorder);
});

server.listen(port, () => {
  const address = server.address();
  const actualPort = typeof address === "object" && address ? address.port : port;
  process.stdout.write(JSON.stringify({ status: "ready", port: actualPort, scenario: scenario.name }) + "\n");
});

function readArg(name: string): string | undefined {
  const index = process.argv.indexOf(name);
  return index >= 0 ? process.argv[index + 1] : undefined;
}

async function readJsonBody(request: IncomingMessage): Promise<JsonRpcMessage | null> {
  const method = request.method ?? "";
  if (method === "GET" || method === "DELETE") {
    return null;
  }

  let raw = "";
  for await (const chunk of request) {
    raw += chunk.toString("utf8");
  }
  return raw ? (JSON.parse(raw) as JsonRpcMessage) : null;
}

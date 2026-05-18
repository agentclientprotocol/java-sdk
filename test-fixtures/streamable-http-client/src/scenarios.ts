import { CONNECTION_HEADER, StreamableHttpFixtureClient } from "./protocol.js";

export async function runScenario(name: string, endpoint: string, client: StreamableHttpFixtureClient): Promise<void> {
  switch (name) {
    case "happy-path":
      await happyPath(client);
      return;
    case "permission-round-trip":
      await permissionRoundTrip(client);
      return;
    case "session-load":
      await sessionLoad(client);
      return;
    case "two-sessions":
      await twoSessions(client);
      return;
    case "wrong-stream-response":
      await wrongStreamResponse(client);
      return;
    case "validation-failures":
      await validationFailures(endpoint, client);
      return;
    default:
      throw new Error(`Unknown scenario ${name}`);
  }
}

async function happyPath(client: StreamableHttpFixtureClient): Promise<void> {
  await client.initialize();
  const connection = await client.openConnectionStream();
  const newSession = client.request("session/new", { cwd: "/workspace", mcpServers: [] });
  await client.postMessage(newSession);
  const sessionResponse = await connection.next();
  const sessionId = ((sessionResponse.result as Record<string, unknown>).sessionId as string);
  const session = await client.openSessionStream(sessionId);
  await client.postMessage(client.request("session/prompt", {
    sessionId,
    prompt: [{ type: "text", text: "hello" }],
  }), sessionId);
  await session.next();
  await session.next();
  await client.close();
}

async function permissionRoundTrip(client: StreamableHttpFixtureClient): Promise<void> {
  await client.initialize();
  const connection = await client.openConnectionStream();
  await client.postMessage(client.request("session/new", { cwd: "/workspace", mcpServers: [] }));
  const sessionResponse = await connection.next();
  const sessionId = ((sessionResponse.result as Record<string, unknown>).sessionId as string);
  const session = await client.openSessionStream(sessionId);
  await client.postMessage(client.request("session/prompt", {
    sessionId,
    prompt: [{ type: "text", text: "needs permission" }],
  }), sessionId);
  const permissionRequest = await session.next();
  await client.postMessage(client.response(permissionRequest.id, { outcome: { outcome: "selected", optionId: "allow" } }), sessionId);
  await session.next();
  await session.next();
  await client.close();
}

async function sessionLoad(client: StreamableHttpFixtureClient): Promise<void> {
  await client.initialize();
  const connection = await client.openConnectionStream();
  const session = await client.openSessionStream("sess-load");
  await client.postMessage(client.request("session/load", {
    sessionId: "sess-load",
    cwd: "/workspace",
    mcpServers: [],
  }), "sess-load");
  await connection.next();
  session.close();
  await client.close();
}

async function twoSessions(client: StreamableHttpFixtureClient): Promise<void> {
  await client.initialize();
  const connection = await client.openConnectionStream();
  await client.postMessage(client.request("session/new", { cwd: "/workspace/one", mcpServers: [] }));
  const first = await connection.next();
  const firstId = ((first.result as Record<string, unknown>).sessionId as string);
  await client.postMessage(client.request("session/new", { cwd: "/workspace/two", mcpServers: [] }));
  const second = await connection.next();
  const secondId = ((second.result as Record<string, unknown>).sessionId as string);
  const firstStream = await client.openSessionStream(firstId);
  const secondStream = await client.openSessionStream(secondId);
  await client.postMessage(client.request("session/prompt", {
    sessionId: firstId,
    prompt: [{ type: "text", text: "one" }],
  }), firstId);
  await firstStream.next();
  await firstStream.next();
  await client.postMessage(client.request("session/prompt", {
    sessionId: secondId,
    prompt: [{ type: "text", text: "two" }],
  }), secondId);
  await secondStream.next();
  await secondStream.next();
  await client.close();
}

async function wrongStreamResponse(client: StreamableHttpFixtureClient): Promise<void> {
  await client.initialize();
  const connection = await client.openConnectionStream();
  await client.postMessage(client.request("session/new", { cwd: "/workspace", mcpServers: [] }));
  const sessionResponse = await connection.next();
  const sessionId = ((sessionResponse.result as Record<string, unknown>).sessionId as string);
  const session = await client.openSessionStream(sessionId);
  await client.postMessage(client.request("session/prompt", {
    sessionId,
    prompt: [{ type: "text", text: "needs permission" }],
  }), sessionId);
  const permissionRequest = await session.next();
  await client.postMessage(client.response(permissionRequest.id, { outcome: { outcome: "selected", optionId: "allow" } }));
  await client.close();
}

async function validationFailures(endpoint: string, client: StreamableHttpFixtureClient): Promise<void> {
  await client.initialize();
  await client.rawRequest("POST", "connection", {
    accept: "application/json",
    "content-type": "text/plain",
    [CONNECTION_HEADER]: "conn-invalid",
  }, {});
  await client.rawRequest("GET", "connection", {
    accept: "text/event-stream",
  }, null);
  await client.rawRequest("GET", "connection", {
    accept: "application/json",
    [CONNECTION_HEADER]: "missing",
  }, null);
  await client.close();
}

import { ServerResponse } from "node:http";

export const ACP_PATH = "/acp";
export const CONNECTION_HEADER = "acp-connection-id";
export const SESSION_HEADER = "acp-session-id";
export const FIXTURE_COOKIE = "fixture=streamable-http";

export type JsonRpcMessage = Record<string, unknown>;

export function sendJson(response: ServerResponse, status: number, body?: JsonRpcMessage, headers: Record<string, string> = {}): void {
  response.writeHead(status, {
    "content-type": "application/json",
    ...headers,
  });
  if (body) {
    response.end(JSON.stringify(body));
  } else {
    response.end();
  }
}

export function openEventStream(response: ServerResponse): void {
  response.writeHead(200, {
    "content-type": "text/event-stream",
    "cache-control": "no-cache",
  });
  response.flushHeaders();
}

export function writeSse(response: ServerResponse, message: JsonRpcMessage): void {
  response.write(`data: ${JSON.stringify(message)}\n\n`);
}

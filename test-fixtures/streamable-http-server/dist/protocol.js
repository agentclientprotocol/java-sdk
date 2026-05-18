export const ACP_PATH = "/acp";
export const CONNECTION_HEADER = "acp-connection-id";
export const SESSION_HEADER = "acp-session-id";
export const FIXTURE_COOKIE = "fixture=streamable-http";
export function sendJson(response, status, body, headers = {}) {
    response.writeHead(status, {
        "content-type": "application/json",
        ...headers,
    });
    if (body) {
        response.end(JSON.stringify(body));
    }
    else {
        response.end();
    }
}
export function openEventStream(response) {
    response.writeHead(200, {
        "content-type": "text/event-stream",
        "cache-control": "no-cache",
    });
    response.flushHeaders();
}
export function writeSse(response, message) {
    response.write(`data: ${JSON.stringify(message)}\n\n`);
}

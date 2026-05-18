export const CONNECTION_HEADER = "Acp-Connection-Id";
export const SESSION_HEADER = "Acp-Session-Id";
export class StreamableHttpFixtureClient {
    endpoint;
    recorder;
    connectionId = null;
    nextId = 1;
    constructor(endpoint, recorder) {
        this.endpoint = endpoint;
        this.recorder = recorder;
    }
    async initialize() {
        const request = this.request("initialize", {
            protocolVersion: 1,
            clientCapabilities: {},
        });
        const response = await this.post(request, "bootstrap", null, true);
        const connectionId = response.headers.get(CONNECTION_HEADER);
        if (!connectionId) {
            throw new Error("initialize response missing connection id");
        }
        this.connectionId = connectionId;
        return await response.json();
    }
    async postMessage(message, sessionId = null) {
        return this.post(message, sessionId ? "session" : "connection", sessionId, false);
    }
    async openConnectionStream() {
        return SseStream.open(this.endpoint, this.recorder, "connection", this.requireConnectionId(), null);
    }
    async openSessionStream(sessionId) {
        return SseStream.open(this.endpoint, this.recorder, "session", this.requireConnectionId(), sessionId);
    }
    async close() {
        const headers = {
            [CONNECTION_HEADER]: this.requireConnectionId(),
        };
        this.recorder.record({
            kind: "http_request",
            method: "DELETE",
            scope: "connection",
            connectionId: this.connectionId,
            sessionId: null,
            jsonRpc: null,
        });
        const response = await fetch(this.endpoint, {
            method: "DELETE",
            headers,
        });
        this.recorder.record({
            kind: "http_response",
            status: response.status,
            scope: "connection",
            connectionId: this.connectionId,
            sessionId: null,
            jsonRpc: null,
        });
        return response;
    }
    async rawRequest(method, scope, headers, body, sessionId = null) {
        this.recorder.record({
            kind: "http_request",
            method,
            scope,
            connectionId: headers[CONNECTION_HEADER] ?? null,
            sessionId,
            jsonRpc: this.recorder.summarizeJsonRpc(body),
        });
        const response = await fetch(this.endpoint, {
            method,
            headers,
            ...(body ? { body: JSON.stringify(body) } : {}),
        });
        this.recorder.record({
            kind: "http_response",
            status: response.status,
            scope,
            connectionId: response.headers.get(CONNECTION_HEADER) ?? headers[CONNECTION_HEADER] ?? null,
            sessionId: response.headers.get(SESSION_HEADER) ?? sessionId,
            jsonRpc: null,
        });
        return response;
    }
    request(method, params) {
        return {
            jsonrpc: "2.0",
            id: `req-${this.nextId++}`,
            method,
            params,
        };
    }
    response(id, result) {
        return {
            jsonrpc: "2.0",
            id,
            result,
        };
    }
    async post(message, scope, sessionId, expectJson) {
        const headers = {
            "content-type": "application/json",
            accept: "application/json",
        };
        if (scope !== "bootstrap") {
            headers[CONNECTION_HEADER] = this.requireConnectionId();
        }
        if (sessionId) {
            headers[SESSION_HEADER] = sessionId;
        }
        this.recorder.record({
            kind: "http_request",
            method: "POST",
            scope,
            connectionId: this.connectionId,
            sessionId,
            jsonRpc: this.recorder.summarizeJsonRpc(message),
        });
        const response = await fetch(this.endpoint, {
            method: "POST",
            headers,
            body: JSON.stringify(message),
        });
        let jsonRpc = null;
        if (expectJson) {
            jsonRpc = this.recorder.summarizeJsonRpc(await response.clone().json());
        }
        this.recorder.record({
            kind: "http_response",
            status: response.status,
            scope,
            connectionId: response.headers.get(CONNECTION_HEADER) ?? this.connectionId,
            sessionId,
            jsonRpc,
        });
        return response;
    }
    requireConnectionId() {
        if (!this.connectionId) {
            throw new Error("connection id not initialized");
        }
        return this.connectionId;
    }
}
export class SseStream {
    recorder;
    stream;
    sessionId;
    response;
    messages = [];
    waiters = [];
    abortController = new AbortController();
    constructor(recorder, stream, sessionId, response) {
        this.recorder = recorder;
        this.stream = stream;
        this.sessionId = sessionId;
        this.response = response;
    }
    static async open(endpoint, recorder, stream, connectionId, sessionId) {
        recorder.record({
            kind: "http_request",
            method: "GET",
            scope: stream,
            connectionId,
            sessionId,
            jsonRpc: null,
        });
        const response = await fetch(endpoint, {
            method: "GET",
            headers: {
                accept: "text/event-stream",
                [CONNECTION_HEADER]: connectionId,
                ...(sessionId ? { [SESSION_HEADER]: sessionId } : {}),
            },
        });
        recorder.record({
            kind: "http_response",
            status: response.status,
            scope: stream,
            connectionId: response.headers.get(CONNECTION_HEADER),
            sessionId: response.headers.get(SESSION_HEADER),
            jsonRpc: null,
        });
        const result = new SseStream(recorder, stream, sessionId, response);
        void result.readLoop();
        return result;
    }
    async next() {
        const existing = this.messages.shift();
        if (existing) {
            return existing;
        }
        return new Promise((resolve) => this.waiters.push(resolve));
    }
    close() {
        this.abortController.abort();
    }
    async readLoop() {
        if (!this.response.body) {
            return;
        }
        const reader = this.response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        for (;;) {
            const { value, done } = await reader.read();
            if (done) {
                break;
            }
            buffer += decoder.decode(value, { stream: true });
            for (;;) {
                const boundary = buffer.indexOf("\n\n");
                if (boundary < 0) {
                    break;
                }
                const rawEvent = buffer.slice(0, boundary);
                buffer = buffer.slice(boundary + 2);
                const data = rawEvent
                    .split("\n")
                    .filter((line) => line.startsWith("data:"))
                    .map((line) => line.slice(5).trimStart())
                    .join("\n");
                if (!data) {
                    continue;
                }
                const message = JSON.parse(data);
                const summary = this.recorder.summarizeJsonRpc(message);
                if (summary) {
                    this.recorder.record({
                        kind: "sse_event",
                        stream: this.stream,
                        sessionId: this.sessionId,
                        jsonRpc: summary,
                    });
                }
                const waiter = this.waiters.shift();
                if (waiter) {
                    waiter(message);
                }
                else {
                    this.messages.push(message);
                }
            }
        }
    }
}

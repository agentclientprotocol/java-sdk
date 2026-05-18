import { CONNECTION_HEADER, FIXTURE_COOKIE, SESSION_HEADER, openEventStream, sendJson, writeSse, } from "./protocol.js";
export function createScenario(name) {
    switch (name) {
        case "happy-path":
            return new HappyPathScenario();
        case "permission-round-trip":
            return new PermissionRoundTripScenario();
        case "session-load":
            return new SessionLoadScenario();
        case "two-sessions":
            return new TwoSessionsScenario();
        case "wrong-stream-response":
            return new WrongStreamResponseScenario();
        case "validation-failures":
            return new ValidationFailuresScenario();
        default:
            throw new Error(`Unknown scenario: ${name}`);
    }
}
class BaseScenario {
    connectionId = "conn-1";
    connectionStream = null;
    sessionStreams = new Map();
    handle(response, headers, method, body, recorder) {
        const request = this.recordRequest(method, headers, body, recorder);
        if (!this.validateRequest(response, request, body, recorder)) {
            return;
        }
        if (request.method === "POST" && body?.method === "initialize") {
            this.handleInitialize(response, body, recorder);
            return;
        }
        if (request.method === "GET" &&
            request.connectionId === this.connectionId &&
            !request.sessionId) {
            this.connectionStream = response;
            openEventStream(response);
            this.recordResponse(recorder, 200, "connection", null);
            return;
        }
        if (request.method === "GET" &&
            request.connectionId === this.connectionId &&
            request.sessionId) {
            this.sessionStreams.set(request.sessionId, response);
            openEventStream(response);
            this.recordResponse(recorder, 200, "session", request.sessionId);
            return;
        }
        if (request.method === "DELETE" && request.connectionId === this.connectionId) {
            sendJson(response, 202);
            this.recordResponse(recorder, 202, "connection", null);
            this.connectionStream?.end();
            this.sessionStreams.forEach((sessionStream) => sessionStream.end());
            return;
        }
        if (this.handleScenarioRequest(response, request, body, recorder)) {
            return;
        }
        this.reject(response, recorder, request.scope, request.sessionId, 400, "Unexpected fixture request", body?.id);
    }
    sendSessionNewResponse(responseStream, request, body, recorder, sessionId) {
        sendJson(responseStream, 202);
        this.recordResponse(recorder, 202, request.scope, request.sessionId);
        const response = {
            jsonrpc: "2.0",
            id: body.id,
            result: {
                sessionId,
            },
        };
        this.writeConnectionEvent(response, recorder);
    }
    sendPromptFlow(responseStream, request, body, recorder, sessionId) {
        sendJson(responseStream, 202);
        this.recordResponse(recorder, 202, request.scope, request.sessionId);
        const update = {
            jsonrpc: "2.0",
            method: "session/update",
            params: {
                sessionId,
                update: {
                    sessionUpdate: "agent_message_chunk",
                    content: { type: "text", text: "hello" },
                },
            },
        };
        const response = {
            jsonrpc: "2.0",
            id: body.id,
            result: {
                stopReason: "end_turn",
            },
        };
        this.writeSessionEvent(sessionId, update, recorder);
        this.writeSessionEvent(sessionId, response, recorder);
    }
    writeConnectionEvent(message, recorder) {
        if (!this.connectionStream) {
            throw new Error("connection stream must be open");
        }
        writeSse(this.connectionStream, message);
        recorder.record({
            kind: "sse_event",
            stream: "connection",
            sessionId: null,
            jsonRpc: recorder.summarizeJsonRpc(message),
        });
    }
    writeSessionEvent(sessionId, message, recorder) {
        const stream = this.sessionStreams.get(sessionId);
        if (!stream) {
            throw new Error(`session stream ${sessionId} must be open`);
        }
        writeSse(stream, message);
        recorder.record({
            kind: "sse_event",
            stream: "session",
            sessionId,
            jsonRpc: recorder.summarizeJsonRpc(message),
        });
    }
    handleInitialize(response, body, recorder) {
        sendJson(response, 200, {
            jsonrpc: "2.0",
            id: body.id,
            result: {
                protocolVersion: 1,
                agentCapabilities: {
                    loadSession: true,
                },
                authMethods: [],
            },
        }, {
            "Acp-Connection-Id": this.connectionId,
            "set-cookie": FIXTURE_COOKIE,
        });
        this.recordResponse(recorder, 200, "bootstrap", null);
    }
    validateRequest(response, request, body, recorder) {
        if (request.scope === "bootstrap") {
            if (request.method !== "POST" || body?.method !== "initialize") {
                this.reject(response, recorder, request.scope, request.sessionId, 400, "Expected initialize bootstrap", body?.id);
                return false;
            }
            return true;
        }
        if (request.connectionId !== this.connectionId) {
            this.reject(response, recorder, request.scope, request.sessionId, 400, "Missing or invalid connection id", body?.id);
            return false;
        }
        if (!request.cookie?.includes(FIXTURE_COOKIE)) {
            this.reject(response, recorder, request.scope, request.sessionId, 401, "Missing fixture cookie", body?.id);
            return false;
        }
        if (request.method === "GET" && !request.accept?.includes("text/event-stream")) {
            this.reject(response, recorder, request.scope, request.sessionId, 406, "Expected text/event-stream", body?.id);
            return false;
        }
        if (request.method === "POST") {
            if (!request.accept?.includes("application/json")) {
                this.reject(response, recorder, request.scope, request.sessionId, 406, "Expected application/json accept", body?.id);
                return false;
            }
            if (!request.contentType?.includes("application/json")) {
                this.reject(response, recorder, request.scope, request.sessionId, 415, "Expected application/json body", body?.id);
                return false;
            }
        }
        return true;
    }
    recordRequest(method, headers, body, recorder) {
        const connectionId = header(headers, CONNECTION_HEADER);
        const sessionId = header(headers, SESSION_HEADER);
        const cookie = header(headers, "cookie");
        const accept = header(headers, "accept");
        const contentType = header(headers, "content-type");
        const scope = sessionId ? "session" : connectionId ? "connection" : "bootstrap";
        recorder.record({
            kind: "http_request",
            method,
            scope,
            connectionId,
            sessionId,
            cookie,
            jsonRpc: recorder.summarizeJsonRpc(body),
        });
        return { method, scope, connectionId, sessionId, cookie, accept, contentType };
    }
    recordResponse(recorder, status, scope, sessionId) {
        recorder.record({
            kind: "http_response",
            status,
            scope,
            connectionId: scope === "bootstrap" ? this.connectionId : this.connectionId,
            sessionId,
        });
    }
    reject(response, recorder, scope, sessionId, status, message, id) {
        sendJson(response, status, {
            jsonrpc: "2.0",
            id: typeof id === "string" || typeof id === "number" ? id : null,
            error: {
                code: -32600,
                message,
            },
        });
        this.recordResponse(recorder, status, scope, sessionId);
    }
}
class HappyPathScenario extends BaseScenario {
    name = "happy-path";
    sessionId = "sess-1";
    handleScenarioRequest(response, request, body, recorder) {
        if (request.method === "POST" && request.scope === "connection" && body?.method === "session/new") {
            this.sendSessionNewResponse(response, request, body, recorder, this.sessionId);
            return true;
        }
        if (request.method === "POST" &&
            request.scope === "session" &&
            request.sessionId === this.sessionId &&
            body?.method === "session/prompt") {
            this.sendPromptFlow(response, request, body, recorder, this.sessionId);
            return true;
        }
        return false;
    }
}
class PermissionRoundTripScenario extends BaseScenario {
    name = "permission-round-trip";
    sessionId = "sess-permission";
    pendingPromptId;
    handleScenarioRequest(response, request, body, recorder) {
        if (request.method === "POST" && request.scope === "connection" && body?.method === "session/new") {
            this.sendSessionNewResponse(response, request, body, recorder, this.sessionId);
            return true;
        }
        if (request.method === "POST" &&
            request.scope === "session" &&
            request.sessionId === this.sessionId &&
            body?.method === "session/prompt") {
            sendJson(response, 202);
            this.recordScenarioResponse(recorder, 202, request);
            this.pendingPromptId = body.id;
            this.writeSessionEvent(this.sessionId, {
                jsonrpc: "2.0",
                id: "perm-1",
                method: "session/request_permission",
                params: {
                    sessionId: this.sessionId,
                    toolCall: {
                        toolCallId: "tool-1",
                        title: "Write File",
                        kind: "edit",
                        status: "pending",
                    },
                    options: [
                        { optionId: "allow", name: "Allow", kind: "allow_once" },
                        { optionId: "deny", name: "Deny", kind: "reject_once" },
                    ],
                },
            }, recorder);
            return true;
        }
        if (request.method === "POST" &&
            request.scope === "session" &&
            request.sessionId === this.sessionId &&
            body?.id === "perm-1" &&
            "result" in body) {
            sendJson(response, 202);
            this.recordScenarioResponse(recorder, 202, request);
            this.writeSessionEvent(this.sessionId, {
                jsonrpc: "2.0",
                id: this.pendingPromptId,
                result: {
                    stopReason: "end_turn",
                },
            }, recorder);
            return true;
        }
        return false;
    }
    recordScenarioResponse(recorder, status, request) {
        recorder.record({
            kind: "http_response",
            status,
            scope: request.scope,
            connectionId: this.connectionId,
            sessionId: request.sessionId,
        });
    }
}
class SessionLoadScenario extends BaseScenario {
    name = "session-load";
    sessionId = "sess-load";
    handleScenarioRequest(response, request, body, recorder) {
        if (request.method === "POST" &&
            request.scope === "session" &&
            request.sessionId === this.sessionId &&
            body?.method === "session/load") {
            sendJson(response, 202);
            this.recordScenarioResponse(recorder, 202, request);
            this.writeConnectionEvent({
                jsonrpc: "2.0",
                id: body.id,
                result: {},
            }, recorder);
            return true;
        }
        return false;
    }
    recordScenarioResponse(recorder, status, request) {
        recorder.record({
            kind: "http_response",
            status,
            scope: request.scope,
            connectionId: this.connectionId,
            sessionId: request.sessionId,
        });
    }
}
class TwoSessionsScenario extends BaseScenario {
    name = "two-sessions";
    nextSessionNumber = 1;
    handleScenarioRequest(response, request, body, recorder) {
        if (request.method === "POST" && request.scope === "connection" && body?.method === "session/new") {
            const sessionId = `sess-${this.nextSessionNumber++}`;
            this.sendSessionNewResponse(response, request, body, recorder, sessionId);
            return true;
        }
        if (request.method === "POST" &&
            request.scope === "session" &&
            request.sessionId &&
            body?.method === "session/prompt") {
            this.sendPromptFlow(response, request, body, recorder, request.sessionId);
            return true;
        }
        return false;
    }
}
class WrongStreamResponseScenario extends BaseScenario {
    name = "wrong-stream-response";
    sessionId = "sess-wrong";
    handleScenarioRequest(response, request, body, recorder) {
        if (request.method === "POST" && request.scope === "connection" && body?.method === "session/new") {
            this.sendSessionNewResponse(response, request, body, recorder, this.sessionId);
            return true;
        }
        if (request.method === "POST" &&
            request.scope === "session" &&
            request.sessionId === this.sessionId &&
            body?.method === "session/prompt") {
            sendJson(response, 202);
            this.recordScenarioResponse(recorder, 202, request);
            this.writeConnectionEvent({
                jsonrpc: "2.0",
                id: body.id,
                result: {
                    stopReason: "end_turn",
                },
            }, recorder);
            return true;
        }
        return false;
    }
    recordScenarioResponse(recorder, status, request) {
        recorder.record({
            kind: "http_response",
            status,
            scope: request.scope,
            connectionId: this.connectionId,
            sessionId: request.sessionId,
        });
    }
}
class ValidationFailuresScenario extends BaseScenario {
    name = "validation-failures";
    handleScenarioRequest(_response, _request, _body, _recorder) {
        return false;
    }
}
function header(headers, name) {
    const value = headers[name];
    if (Array.isArray(value)) {
        return value[0] ?? null;
    }
    return typeof value === "string" ? value : null;
}

import { IncomingHttpHeaders, ServerResponse } from "node:http";
import {
  CONNECTION_HEADER,
  FIXTURE_COOKIE,
  JsonRpcMessage,
  SESSION_HEADER,
  openEventStream,
  sendJson,
  writeSse,
} from "./protocol.js";
import { TranscriptRecorder } from "./transcript.js";

export interface FixtureScenario {
  readonly name: string;
  handle(
    response: ServerResponse,
    headers: IncomingHttpHeaders,
    method: string,
    body: JsonRpcMessage | null,
    recorder: TranscriptRecorder,
  ): void;
}

export function createScenario(name: string): FixtureScenario {
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

abstract class BaseScenario implements FixtureScenario {
  abstract readonly name: string;

  protected readonly connectionId = "conn-1";
  protected connectionStream: ServerResponse | null = null;
  protected readonly sessionStreams = new Map<string, ServerResponse>();

  handle(
    response: ServerResponse,
    headers: IncomingHttpHeaders,
    method: string,
    body: JsonRpcMessage | null,
    recorder: TranscriptRecorder,
  ): void {
    const request = this.recordRequest(method, headers, body, recorder);
    if (!this.validateRequest(response, request, body, recorder)) {
      return;
    }

    if (request.method === "POST" && body?.method === "initialize") {
      this.handleInitialize(response, body, recorder);
      return;
    }

    if (
      request.method === "GET" &&
      request.connectionId === this.connectionId &&
      !request.sessionId
    ) {
      this.connectionStream = response;
      openEventStream(response);
      this.recordResponse(recorder, 200, "connection", null);
      return;
    }

    if (
      request.method === "GET" &&
      request.connectionId === this.connectionId &&
      request.sessionId
    ) {
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

  protected abstract handleScenarioRequest(
    response: ServerResponse,
    request: RecordedRequest,
    body: JsonRpcMessage | null,
    recorder: TranscriptRecorder,
  ): boolean;

  protected sendSessionNewResponse(
    responseStream: ServerResponse,
    request: RecordedRequest,
    body: JsonRpcMessage,
    recorder: TranscriptRecorder,
    sessionId: string,
  ): void {
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

  protected sendPromptFlow(
    responseStream: ServerResponse,
    request: RecordedRequest,
    body: JsonRpcMessage,
    recorder: TranscriptRecorder,
    sessionId: string,
  ): void {
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

  protected writeConnectionEvent(message: JsonRpcMessage, recorder: TranscriptRecorder): void {
    if (!this.connectionStream) {
      throw new Error("connection stream must be open");
    }
    writeSse(this.connectionStream, message);
    recorder.record({
      kind: "sse_event",
      stream: "connection",
      sessionId: null,
      jsonRpc: recorder.summarizeJsonRpc(message)!,
    });
  }

  protected writeSessionEvent(sessionId: string, message: JsonRpcMessage, recorder: TranscriptRecorder): void {
    const stream = this.sessionStreams.get(sessionId);
    if (!stream) {
      throw new Error(`session stream ${sessionId} must be open`);
    }
    writeSse(stream, message);
    recorder.record({
      kind: "sse_event",
      stream: "session",
      sessionId,
      jsonRpc: recorder.summarizeJsonRpc(message)!,
    });
  }

  private handleInitialize(response: ServerResponse, body: JsonRpcMessage, recorder: TranscriptRecorder): void {
    sendJson(
      response,
      200,
      {
        jsonrpc: "2.0",
        id: body.id,
        result: {
          protocolVersion: 1,
          agentCapabilities: {
            loadSession: true,
          },
          authMethods: [],
        },
      },
      {
        "Acp-Connection-Id": this.connectionId,
        "set-cookie": FIXTURE_COOKIE,
      },
    );
    this.recordResponse(recorder, 200, "bootstrap", null);
  }

  private validateRequest(
    response: ServerResponse,
    request: RecordedRequest,
    body: JsonRpcMessage | null,
    recorder: TranscriptRecorder,
  ): boolean {
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

  private recordRequest(
    method: string,
    headers: IncomingHttpHeaders,
    body: JsonRpcMessage | null,
    recorder: TranscriptRecorder,
  ): RecordedRequest {
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

  private recordResponse(
    recorder: TranscriptRecorder,
    status: number,
    scope: RequestScope,
    sessionId: string | null,
  ): void {
    recorder.record({
      kind: "http_response",
      status,
      scope,
      connectionId: scope === "bootstrap" ? this.connectionId : this.connectionId,
      sessionId,
    });
  }

  private reject(
    response: ServerResponse,
    recorder: TranscriptRecorder,
    scope: RequestScope,
    sessionId: string | null,
    status: number,
    message: string,
    id: unknown,
  ): void {
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
  readonly name = "happy-path";
  private readonly sessionId = "sess-1";

  protected handleScenarioRequest(
    response: ServerResponse,
    request: RecordedRequest,
    body: JsonRpcMessage | null,
    recorder: TranscriptRecorder,
  ): boolean {
    if (request.method === "POST" && request.scope === "connection" && body?.method === "session/new") {
      this.sendSessionNewResponse(response, request, body, recorder, this.sessionId);
      return true;
    }
    if (
      request.method === "POST" &&
      request.scope === "session" &&
      request.sessionId === this.sessionId &&
      body?.method === "session/prompt"
    ) {
      this.sendPromptFlow(response, request, body, recorder, this.sessionId);
      return true;
    }
    return false;
  }
}

class PermissionRoundTripScenario extends BaseScenario {
  readonly name = "permission-round-trip";
  private readonly sessionId = "sess-permission";
  private pendingPromptId: unknown;

  protected handleScenarioRequest(
    response: ServerResponse,
    request: RecordedRequest,
    body: JsonRpcMessage | null,
    recorder: TranscriptRecorder,
  ): boolean {
    if (request.method === "POST" && request.scope === "connection" && body?.method === "session/new") {
      this.sendSessionNewResponse(response, request, body, recorder, this.sessionId);
      return true;
    }
    if (
      request.method === "POST" &&
      request.scope === "session" &&
      request.sessionId === this.sessionId &&
      body?.method === "session/prompt"
    ) {
      sendJson(response, 202);
      this.recordScenarioResponse(recorder, 202, request);
      this.pendingPromptId = body.id;
      this.writeSessionEvent(
        this.sessionId,
        {
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
        },
        recorder,
      );
      return true;
    }
    if (
      request.method === "POST" &&
      request.scope === "session" &&
      request.sessionId === this.sessionId &&
      body?.id === "perm-1" &&
      "result" in body
    ) {
      sendJson(response, 202);
      this.recordScenarioResponse(recorder, 202, request);
      this.writeSessionEvent(
        this.sessionId,
        {
          jsonrpc: "2.0",
          id: this.pendingPromptId,
          result: {
            stopReason: "end_turn",
          },
        },
        recorder,
      );
      return true;
    }
    return false;
  }

  private recordScenarioResponse(recorder: TranscriptRecorder, status: number, request: RecordedRequest): void {
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
  readonly name = "session-load";
  private readonly sessionId = "sess-load";

  protected handleScenarioRequest(
    response: ServerResponse,
    request: RecordedRequest,
    body: JsonRpcMessage | null,
    recorder: TranscriptRecorder,
  ): boolean {
    if (
      request.method === "POST" &&
      request.scope === "session" &&
      request.sessionId === this.sessionId &&
      body?.method === "session/load"
    ) {
      sendJson(response, 202);
      this.recordScenarioResponse(recorder, 202, request);
      this.writeConnectionEvent(
        {
          jsonrpc: "2.0",
          id: body.id,
          result: {},
        },
        recorder,
      );
      return true;
    }
    return false;
  }

  private recordScenarioResponse(recorder: TranscriptRecorder, status: number, request: RecordedRequest): void {
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
  readonly name = "two-sessions";
  private nextSessionNumber = 1;

  protected handleScenarioRequest(
    response: ServerResponse,
    request: RecordedRequest,
    body: JsonRpcMessage | null,
    recorder: TranscriptRecorder,
  ): boolean {
    if (request.method === "POST" && request.scope === "connection" && body?.method === "session/new") {
      const sessionId = `sess-${this.nextSessionNumber++}`;
      this.sendSessionNewResponse(response, request, body, recorder, sessionId);
      return true;
    }
    if (
      request.method === "POST" &&
      request.scope === "session" &&
      request.sessionId &&
      body?.method === "session/prompt"
    ) {
      this.sendPromptFlow(response, request, body, recorder, request.sessionId);
      return true;
    }
    return false;
  }
}

class WrongStreamResponseScenario extends BaseScenario {
  readonly name = "wrong-stream-response";
  private readonly sessionId = "sess-wrong";

  protected handleScenarioRequest(
    response: ServerResponse,
    request: RecordedRequest,
    body: JsonRpcMessage | null,
    recorder: TranscriptRecorder,
  ): boolean {
    if (request.method === "POST" && request.scope === "connection" && body?.method === "session/new") {
      this.sendSessionNewResponse(response, request, body, recorder, this.sessionId);
      return true;
    }
    if (
      request.method === "POST" &&
      request.scope === "session" &&
      request.sessionId === this.sessionId &&
      body?.method === "session/prompt"
    ) {
      sendJson(response, 202);
      this.recordScenarioResponse(recorder, 202, request);
      this.writeConnectionEvent(
        {
          jsonrpc: "2.0",
          id: body.id,
          result: {
            stopReason: "end_turn",
          },
        },
        recorder,
      );
      return true;
    }
    return false;
  }

  private recordScenarioResponse(recorder: TranscriptRecorder, status: number, request: RecordedRequest): void {
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
  readonly name = "validation-failures";

  protected handleScenarioRequest(
    _response: ServerResponse,
    _request: RecordedRequest,
    _body: JsonRpcMessage | null,
    _recorder: TranscriptRecorder,
  ): boolean {
    return false;
  }
}

type RequestScope = "bootstrap" | "connection" | "session";

type RecordedRequest = {
  method: string;
  scope: RequestScope;
  connectionId: string | null;
  sessionId: string | null;
  cookie: string | null;
  accept: string | null;
  contentType: string | null;
};

function header(headers: IncomingHttpHeaders, name: string): string | null {
  const value = headers[name];
  if (Array.isArray(value)) {
    return value[0] ?? null;
  }
  return typeof value === "string" ? value : null;
}

export type JsonRpcSummary =
  | {
      type: "request";
      id: string | null;
      method: string;
    }
  | {
      type: "notification";
      method: string;
    }
  | {
      type: "response";
      id: string | null;
      hasError: boolean;
    };

export type TranscriptEvent =
  | {
      kind: "http_request";
      method: string;
      scope: "bootstrap" | "connection" | "session";
      connectionId: string | null;
      sessionId: string | null;
      jsonRpc: JsonRpcSummary | null;
    }
  | {
      kind: "http_response";
      status: number;
      scope: "bootstrap" | "connection" | "session";
      connectionId: string | null;
      sessionId: string | null;
      jsonRpc: JsonRpcSummary | null;
    }
  | {
      kind: "sse_event";
      stream: "connection" | "session";
      sessionId: string | null;
      jsonRpc: JsonRpcSummary;
    };

export class TranscriptRecorder {
  private readonly events: TranscriptEvent[] = [];
  private readonly idAliases = new Map<string | number, string>();
  private readonly connectionAliases = new Map<string, string>();

  record(event: TranscriptEvent): void {
    if ("connectionId" in event) {
      this.events.push({
        ...event,
        connectionId: this.normalizeConnectionId(event.connectionId),
      });
      return;
    }
    this.events.push(event);
  }

  summarizeJsonRpc(message: unknown): JsonRpcSummary | null {
    if (!message || typeof message !== "object") {
      return null;
    }

    const candidate = message as Record<string, unknown>;
    if (typeof candidate.method === "string" && "id" in candidate) {
      return {
        type: "request",
        id: this.normalizeId(candidate.id),
        method: candidate.method,
      };
    }
    if (typeof candidate.method === "string") {
      return {
        type: "notification",
        method: candidate.method,
      };
    }
    if ("result" in candidate || "error" in candidate) {
      return {
        type: "response",
        id: this.normalizeId(candidate.id),
        hasError: candidate.error != null,
      };
    }
    return null;
  }

  serialize(): string {
    return JSON.stringify(this.events, null, 2);
  }

  private normalizeId(id: unknown): string | null {
    if (typeof id !== "string" && typeof id !== "number") {
      return null;
    }
    const existing = this.idAliases.get(id);
    if (existing) {
      return existing;
    }
    const next = `id-${this.idAliases.size + 1}`;
    this.idAliases.set(id, next);
    return next;
  }

  private normalizeConnectionId(connectionId: string | null): string | null {
    if (!connectionId) {
      return null;
    }
    const existing = this.connectionAliases.get(connectionId);
    if (existing) {
      return existing;
    }
    const next = `conn-${this.connectionAliases.size + 1}`;
    this.connectionAliases.set(connectionId, next);
    return next;
  }
}

export class TranscriptRecorder {
    events = [];
    idAliases = new Map();
    record(event) {
        this.events.push(event);
    }
    summarizeJsonRpc(message) {
        if (!message || typeof message !== "object") {
            return null;
        }
        const candidate = message;
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
    toJSON() {
        return [...this.events];
    }
    serialize() {
        return JSON.stringify(this.events, null, 2);
    }
    normalizeId(id) {
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
}

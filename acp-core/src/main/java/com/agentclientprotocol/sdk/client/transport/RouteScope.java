/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import org.jspecify.annotations.Nullable;

/**
 * The HTTP scope a Streamable HTTP message travels in: before a connection exists
 * (bootstrap), on the connection, or on one of its sessions. The scope decides the
 * {@code Acp-Connection-Id} and {@code Acp-Session-Id} headers of a POST and which SSE
 * stream carries a reply.
 */
record RouteScope(Kind kind, @Nullable String sessionId) {

	enum Kind {

		BOOTSTRAP,

		CONNECTION,

		SESSION

	}

	static RouteScope bootstrap() {
		return new RouteScope(Kind.BOOTSTRAP, null);
	}

	static RouteScope connection() {
		return new RouteScope(Kind.CONNECTION, null);
	}

	static RouteScope session(String sessionId) {
		return new RouteScope(Kind.SESSION, sessionId);
	}

	boolean isBootstrap() {
		return kind == Kind.BOOTSTRAP;
	}

	boolean isSession() {
		return kind == Kind.SESSION;
	}

	/**
	 * The session id this scope routes to. Only session scopes carry one, and every caller
	 * has already established that the scope is one.
	 */
	String boundSessionId() {
		if (sessionId == null) {
			throw new IllegalStateException("A " + kind + " route scope has no session id");
		}
		return sessionId;
	}

}

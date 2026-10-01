/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import org.jspecify.annotations.Nullable;

/**
 * Routing rules of the Streamable HTTP transport: which stream (connection or session)
 * each ACP method travels on, and how session ids are read from params and headers.
 *
 * <p>
 * Stateless apart from the JSON mapper used to look into untyped params. Holds no
 * connection state and has no servlet or Jetty dependency; per-connection bookkeeping
 * lives in {@link StreamableHttpConnection}.
 * </p>
 *
 * @author Kaiser Dandangi
 */
final class StreamableHttpRouting {

	static final String HEADER_CONNECTION_ID = "Acp-Connection-Id";

	static final String HEADER_SESSION_ID = "Acp-Session-Id";

	static final String CONTENT_TYPE_EVENT_STREAM = "text/event-stream";

	/** How long a new connection may take to answer {@code initialize}. */
	static final Duration INITIALIZE_TIMEOUT = Duration.ofSeconds(30);

	private final AcpJsonMapper jsonMapper;

	StreamableHttpRouting(AcpJsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	enum ScopeKind {

		CONNECTION,

		SESSION

	}

	enum RequestKind {

		INITIALIZE,

		SESSION_NEW,

		SESSION_FORK,

		SESSION_LOAD,

		GENERIC

	}

	enum SessionState {

		PENDING_LOAD,

		KNOWN

	}

	record RouteScope(ScopeKind kind, @Nullable String sessionId) {

		static RouteScope connection() {
			return new RouteScope(ScopeKind.CONNECTION, null);
		}

		static RouteScope session(String sessionId) {
			return new RouteScope(ScopeKind.SESSION, sessionId);
		}

		boolean isSession() {
			return kind == ScopeKind.SESSION;
		}

		/**
		 * The session id this scope routes to. Only session scopes carry one, and every
		 * caller has already established that the scope is one.
		 */
		String boundSessionId() {
			if (sessionId == null) {
				throw new IllegalStateException("A " + kind + " route scope has no session id");
			}
			return sessionId;
		}

	}

	record ClientRequestRoute(RequestKind kind, RouteScope requestScope, RouteScope responseScope) {
	}

	record ResolvedInboundRoute(JSONRPCMessage message, RouteScope requestScope,
			@Nullable ClientRequestRoute requestRoute) {
	}

	static boolean isInitialize(JSONRPCMessage message) {
		return message instanceof AcpSchema.JSONRPCRequest request
				&& AcpSchema.METHOD_INITIALIZE.equals(request.method());
	}

	static boolean isInitializeRequest(JSONRPCMessage message) {
		return message instanceof AcpSchema.JSONRPCRequest request
				&& AcpSchema.METHOD_INITIALIZE.equals(request.method()) && request.id() != null;
	}

	RouteScope resolveAgentRequestOrNotificationScope(String method, @Nullable Object params) {
		switch (method) {
			case AcpSchema.METHOD_SESSION_REQUEST_PERMISSION:
			case AcpSchema.METHOD_SESSION_UPDATE:
			case AcpSchema.METHOD_FS_READ_TEXT_FILE:
			case AcpSchema.METHOD_FS_WRITE_TEXT_FILE:
			case AcpSchema.METHOD_TERMINAL_CREATE:
			case AcpSchema.METHOD_TERMINAL_OUTPUT:
			case AcpSchema.METHOD_TERMINAL_RELEASE:
			case AcpSchema.METHOD_TERMINAL_WAIT_FOR_EXIT:
			case AcpSchema.METHOD_TERMINAL_KILL:
				return RouteScope.session(requireSessionId(params, method));
			default:
				Optional<String> sessionId = extractSessionId(params);
				return sessionId.map(RouteScope::session).orElseGet(RouteScope::connection);
		}
	}

	ResolvedInboundRoute resolveInboundRoute(JSONRPCMessage message, @Nullable String sessionHeader) {
		String method;
		Object params;
		if (message instanceof AcpSchema.JSONRPCRequest request) {
			method = request.method();
			params = request.params();
		}
		else if (message instanceof AcpSchema.JSONRPCNotification notification) {
			method = notification.method();
			params = notification.params();
		}
		else {
			throw new AcpConnectionException("Unsupported inbound JSON-RPC message type: " + message);
		}

		RouteScope requestScope;
		RequestKind kind = RequestKind.GENERIC;
		RouteScope responseScope;

		switch (method) {
			case AcpSchema.METHOD_AUTHENTICATE:
			case AcpSchema.METHOD_SESSION_NEW:
				requestScope = RouteScope.connection();
				kind = AcpSchema.METHOD_SESSION_NEW.equals(method) ? RequestKind.SESSION_NEW : RequestKind.GENERIC;
				responseScope = RouteScope.connection();
				break;
			case AcpSchema.METHOD_SESSION_LOAD:
			case AcpSchema.METHOD_SESSION_RESUME:
				// The RFD is ambiguous here: its reconnect diagram sends the header, its text
				// says load answers on the connection stream because the client has no session
				// yet. The Python client omits it. Accept either; the id comes from params.
				requestScope = sessionScopeFromParams(method, params, sessionHeader);
				kind = RequestKind.SESSION_LOAD;
				responseScope = RouteScope.connection();
				break;
			case AcpSchema.METHOD_SESSION_FORK:
				// Scoped to the parent session; the reply names the forked session.
				requestScope = requireSessionScope(method, params, sessionHeader);
				kind = RequestKind.SESSION_FORK;
				responseScope = requestScope;
				break;
			case AcpSchema.METHOD_SESSION_PROMPT:
			case AcpSchema.METHOD_SESSION_SET_MODE:
			case AcpSchema.METHOD_SESSION_CANCEL:
				requestScope = requireSessionScope(method, params, sessionHeader);
				responseScope = requestScope;
				break;
			default:
				Optional<String> sessionId = extractSessionId(params);
				if (sessionId.isPresent()) {
					requestScope = requireSessionScope(method, params, sessionHeader);
				}
				else {
					requestScope = RouteScope.connection();
				}
				responseScope = requestScope;
		}

		ClientRequestRoute requestRoute = message instanceof AcpSchema.JSONRPCRequest
				? new ClientRequestRoute(kind, requestScope, responseScope) : null;
		return new ResolvedInboundRoute(message, requestScope, requestRoute);
	}

	/** Like {@link #requireSessionScope} but tolerates a missing header; a conflicting one is still rejected. */
	RouteScope sessionScopeFromParams(String method, @Nullable Object params, @Nullable String sessionHeader) {
		if (sessionHeader == null) {
			return RouteScope.session(requireSessionId(params, method));
		}
		return requireSessionScope(method, params, sessionHeader);
	}

	RouteScope requireSessionScope(String method, @Nullable Object params, @Nullable String sessionHeader) {
		String sessionId = requireSessionId(params, method);
		if (sessionHeader == null) {
			throw new AcpConnectionException(
					HEADER_SESSION_ID + " header required for " + method);
		}
		if (!sessionId.equals(sessionHeader)) {
			throw new AcpConnectionException("Header " + HEADER_SESSION_ID
					+ " does not match params.sessionId");
		}
		return RouteScope.session(sessionId);
	}

	Optional<String> extractSessionId(@Nullable Object params) {
		if (params == null) {
			return Optional.empty();
		}
		Map<?, ?> paramsMap = jsonMapper.convertValue(params, Map.class);
		Object sessionId = paramsMap.get("sessionId");
		return sessionId == null ? Optional.empty() : Optional.of(sessionId.toString());
	}

	String requireSessionId(@Nullable Object params, String method) {
		return extractSessionId(params)
			.filter(sessionId -> !sessionId.isBlank())
			.orElseThrow(() -> new AcpConnectionException("Missing sessionId for method " + method));
	}

	String extractSessionIdFromNewSessionResponse(AcpSchema.JSONRPCResponse response) {
		Object result = response.result();
		if (result == null) {
			throw new AcpConnectionException("session/new response carried no result");
		}
		AcpSchema.NewSessionResponse sessionResponse = jsonMapper.convertValue(result,
				new TypeRef<AcpSchema.NewSessionResponse>() {
				});
		// Required by the schema, but conversion does not enforce it.
		if (sessionResponse.sessionId() == null || sessionResponse.sessionId().isBlank()) {
			throw new AcpConnectionException("session/new response missing sessionId");
		}
		return sessionResponse.sessionId();
	}

}

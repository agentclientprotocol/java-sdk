/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.util.Map;
import java.util.Optional;

import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;

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

	record RouteScope(ScopeKind kind, String sessionId) {

		static RouteScope connection() {
			return new RouteScope(ScopeKind.CONNECTION, null);
		}

		static RouteScope session(String sessionId) {
			return new RouteScope(ScopeKind.SESSION, sessionId);
		}

		boolean isSession() {
			return kind == ScopeKind.SESSION;
		}

	}

	record ClientRequestRoute(RequestKind kind, RouteScope requestScope, RouteScope responseScope) {
	}

	record ResolvedInboundRoute(JSONRPCMessage message, RouteScope requestScope, ClientRequestRoute requestRoute) {
	}

	static boolean isInitialize(JSONRPCMessage message) {
		return message instanceof AcpSchema.JSONRPCRequest request
				&& AcpSchema.METHOD_INITIALIZE.equals(request.method());
	}

	static boolean isInitializeRequest(JSONRPCMessage message) {
		return message instanceof AcpSchema.JSONRPCRequest request
				&& AcpSchema.METHOD_INITIALIZE.equals(request.method()) && request.id() != null;
	}

	RouteScope resolveAgentRequestOrNotificationScope(String method, Object params) {
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

	ResolvedInboundRoute resolveInboundRoute(JSONRPCMessage message, String sessionHeader) {
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
				requestScope = requireSessionScope(method, params, sessionHeader);
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
			case AcpSchema.METHOD_SESSION_SET_MODEL:
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

	RouteScope requireSessionScope(String method, Object params, String sessionHeader) {
		String sessionId = requireSessionId(params, method);
		if (sessionHeader == null) {
			throw new AcpConnectionException(
					StreamableHttpAcpAgentTransport.HEADER_SESSION_ID + " header required for " + method);
		}
		if (!sessionId.equals(sessionHeader)) {
			throw new AcpConnectionException("Header " + StreamableHttpAcpAgentTransport.HEADER_SESSION_ID
					+ " does not match params.sessionId");
		}
		return RouteScope.session(sessionId);
	}

	Optional<String> extractSessionId(Object params) {
		if (params == null) {
			return Optional.empty();
		}
		Map<?, ?> paramsMap = jsonMapper.convertValue(params, Map.class);
		Object sessionId = paramsMap.get("sessionId");
		return sessionId == null ? Optional.empty() : Optional.of(sessionId.toString());
	}

	String requireSessionId(Object params, String method) {
		return extractSessionId(params)
			.filter(sessionId -> !sessionId.isBlank())
			.orElseThrow(() -> new AcpConnectionException("Missing sessionId for method " + method));
	}

	String extractSessionIdFromNewSessionResponse(AcpSchema.JSONRPCResponse response) {
		AcpSchema.NewSessionResponse sessionResponse = jsonMapper.convertValue(response.result(),
				new TypeRef<AcpSchema.NewSessionResponse>() {
				});
		if (sessionResponse.sessionId() == null || sessionResponse.sessionId().isBlank()) {
			throw new AcpConnectionException("session/new response missing sessionId");
		}
		return sessionResponse.sessionId();
	}

}

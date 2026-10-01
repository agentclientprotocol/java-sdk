/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

	/** Agent-to-client methods that always concern one session, so params must name it. */
	private static final Set<String> SESSION_SCOPED_AGENT_METHODS = Set.of(AcpSchema.METHOD_SESSION_REQUEST_PERMISSION,
			AcpSchema.METHOD_SESSION_UPDATE, AcpSchema.METHOD_FS_READ_TEXT_FILE, AcpSchema.METHOD_FS_WRITE_TEXT_FILE,
			AcpSchema.METHOD_TERMINAL_CREATE, AcpSchema.METHOD_TERMINAL_OUTPUT, AcpSchema.METHOD_TERMINAL_RELEASE,
			AcpSchema.METHOD_TERMINAL_WAIT_FOR_EXIT, AcpSchema.METHOD_TERMINAL_KILL);

	private final AcpJsonMapper jsonMapper;

	/** Client-to-agent methods with their own routing rule; any other method uses {@link #defaultRoute}. */
	private final Map<String, InboundRule> inboundRules;

	StreamableHttpRouting(AcpJsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
		this.inboundRules = Map.of(
				AcpSchema.METHOD_AUTHENTICATE, (method, params, header) -> connectionRoute(RequestKind.GENERIC),
				AcpSchema.METHOD_SESSION_NEW, (method, params, header) -> connectionRoute(RequestKind.SESSION_NEW),
				AcpSchema.METHOD_SESSION_LOAD, this::loadRoute,
				AcpSchema.METHOD_SESSION_RESUME, this::loadRoute,
				// Scoped to the parent session; the reply names the forked session.
				AcpSchema.METHOD_SESSION_FORK, (method, params, header) -> sameStreamRoute(RequestKind.SESSION_FORK,
						requireSessionScope(method, params, header)),
				AcpSchema.METHOD_SESSION_PROMPT, this::sessionBoundRoute,
				AcpSchema.METHOD_SESSION_SET_MODE, this::sessionBoundRoute,
				AcpSchema.METHOD_SESSION_CANCEL, this::sessionBoundRoute);
	}

	/** How one client-to-agent method is routed: its request scope and its response scope. */
	@FunctionalInterface
	private interface InboundRule {

		ClientRequestRoute route(String method, @Nullable Object params, @Nullable String sessionHeader);

	}

	/** The method, params and (for a request) id of a JSON-RPC request or notification. */
	record MethodCall(String method, @Nullable Object params, @Nullable Object id) {

		/**
		 * @throws AcpConnectionException for a message that is neither a request nor a
		 * notification; {@code direction} ("inbound", "outbound") names it in the message
		 */
		static MethodCall of(JSONRPCMessage message, String direction) {
			if (message instanceof AcpSchema.JSONRPCRequest request) {
				return new MethodCall(request.method(), request.params(), request.id());
			}
			if (message instanceof AcpSchema.JSONRPCNotification notification) {
				return new MethodCall(notification.method(), notification.params(), null);
			}
			throw new AcpConnectionException("Unsupported " + direction + " JSON-RPC message type: " + message);
		}

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
		if (SESSION_SCOPED_AGENT_METHODS.contains(method)) {
			return RouteScope.session(requireSessionId(params, method));
		}
		return extractSessionId(params).map(RouteScope::session).orElseGet(RouteScope::connection);
	}

	ResolvedInboundRoute resolveInboundRoute(JSONRPCMessage message, @Nullable String sessionHeader) {
		MethodCall call = MethodCall.of(message, "inbound");
		ClientRequestRoute route = inboundRules.getOrDefault(call.method(), this::defaultRoute)
			.route(call.method(), call.params(), sessionHeader);
		// Only a request gets a response to route; a notification keeps just its scope.
		ClientRequestRoute requestRoute = message instanceof AcpSchema.JSONRPCRequest ? route : null;
		return new ResolvedInboundRoute(message, route.requestScope(), requestRoute);
	}

	private static ClientRequestRoute connectionRoute(RequestKind kind) {
		return new ClientRequestRoute(kind, RouteScope.connection(), RouteScope.connection());
	}

	/** A request answered on the stream (connection or session) it was routed to. */
	private static ClientRequestRoute sameStreamRoute(RequestKind kind, RouteScope scope) {
		return new ClientRequestRoute(kind, scope, scope);
	}

	/**
	 * session/load and session/resume. The RFD is ambiguous here: its reconnect diagram sends
	 * the header, its text says load answers on the connection stream because the client has
	 * no session yet. The Python client omits it. Accept either; the id comes from params.
	 */
	private ClientRequestRoute loadRoute(String method, @Nullable Object params, @Nullable String sessionHeader) {
		return new ClientRequestRoute(RequestKind.SESSION_LOAD, sessionScopeFromParams(method, params, sessionHeader),
				RouteScope.connection());
	}

	/** A method that always concerns one session: params and header must both name it. */
	private ClientRequestRoute sessionBoundRoute(String method, @Nullable Object params,
			@Nullable String sessionHeader) {
		return sameStreamRoute(RequestKind.GENERIC, requireSessionScope(method, params, sessionHeader));
	}

	/** Any other method: session-scoped when its params name a session, else connection-scoped. */
	private ClientRequestRoute defaultRoute(String method, @Nullable Object params, @Nullable String sessionHeader) {
		RouteScope scope = extractSessionId(params).isPresent() ? requireSessionScope(method, params, sessionHeader)
				: RouteScope.connection();
		return sameStreamRoute(RequestKind.GENERIC, scope);
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

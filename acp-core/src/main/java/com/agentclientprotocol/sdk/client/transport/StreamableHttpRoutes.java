/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routing state of a Streamable HTTP client connection: which HTTP scope each outbound
 * message is posted in, on which SSE stream the answer to each client request is
 * expected, and in which scope the client must answer each agent request.
 */
final class StreamableHttpRoutes {

	private static final Logger logger = LoggerFactory.getLogger(StreamableHttpRoutes.class);

	/** What a client request is, as far as routing its response goes. */
	enum RequestKind {

		INITIALIZE,

		SESSION_NEW,

		SESSION_LOAD,

		GENERIC

	}

	/** Where a client request was posted, and where its response is expected. */
	record OutboundRequestRoute(RequestKind kind, RouteScope requestScope, RouteScope responseScope) {
	}

	/**
	 * How to deliver an inbound message.
	 * @param newSessionResponse the message answers {@code session/new}, whose session stream
	 * must be opened before the response is delivered
	 * @param answeredRequestId the id of the client request the message answers, to forget
	 * once it is delivered, or null
	 */
	record InboundRoute(boolean newSessionResponse, @Nullable Object answeredRequestId) {

		static final InboundRoute DELIVER = new InboundRoute(false, null);

	}

	private final AcpJsonMapper jsonMapper;

	// Client-originated request id -> where the eventual SSE response is expected.
	private final Map<Object, OutboundRequestRoute> outboundRequestRoutes = new ConcurrentHashMap<>();

	// Agent-originated request id -> HTTP scope required for the later client POST response.
	private final Map<Object, RouteScope> inboundRequestRoutes = new ConcurrentHashMap<>();

	StreamableHttpRoutes(AcpJsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	/**
	 * The scope to post an outbound message in. A client request's expected response route
	 * is recorded as a side effect.
	 */
	RouteScope resolveOutbound(JSONRPCMessage message) {
		if (message instanceof AcpSchema.JSONRPCResponse response) {
			return responseScope(response);
		}
		if (message instanceof AcpSchema.JSONRPCRequest request) {
			RouteScope requestScope = requestScope(request.method(), request.params());
			if (request.id() != null) {
				outboundRequestRoutes.put(request.id(), new OutboundRequestRoute(requestKind(request.method()),
						requestScope, responseScope(request.method(), requestScope)));
			}
			return requestScope;
		}
		if (message instanceof AcpSchema.JSONRPCNotification notification) {
			if (AcpSchema.METHOD_CANCEL_REQUEST.equals(notification.method())) {
				return cancelRequestScope(notification.params());
			}
			return requestScope(notification.method(), notification.params());
		}
		throw new AcpConnectionException("Unsupported outbound JSON-RPC message type: " + message);
	}

	/**
	 * A {@code $/cancel_request} is posted in the scope of the request it cancels, or on the
	 * connection when that request is unknown (already answered, say).
	 */
	private RouteScope cancelRequestScope(@Nullable Object params) {
		Object requestId = (params != null) ? jsonMapper.convertValue(params, Map.class).get("requestId") : null;
		OutboundRequestRoute route = (requestId != null) ? outboundRequestRoutes.get(requestId) : null;
		return (route != null) ? route.requestScope() : RouteScope.connection();
	}

	/** The scope of the agent request this client response answers. */
	private RouteScope responseScope(AcpSchema.JSONRPCResponse response) {
		if (response.id() == null) {
			// The answer to an agent request posted with "id": null, which was never
			// routed (a ConcurrentMap holds no null key).
			return RouteScope.connection();
		}
		RouteScope scope = inboundRequestRoutes.get(response.id());
		if (scope == null) {
			throw new AcpConnectionException("Cannot route outbound response with unknown id " + response.id());
		}
		return scope;
	}

	private RouteScope requestScope(String method, @Nullable Object params) {
		return switch (method) {
			case AcpSchema.METHOD_INITIALIZE -> RouteScope.bootstrap();
			case AcpSchema.METHOD_AUTHENTICATE, AcpSchema.METHOD_SESSION_NEW -> RouteScope.connection();
			case AcpSchema.METHOD_SESSION_LOAD, AcpSchema.METHOD_SESSION_RESUME, AcpSchema.METHOD_SESSION_PROMPT,
					AcpSchema.METHOD_SESSION_SET_MODE, AcpSchema.METHOD_SESSION_CANCEL ->
				RouteScope.session(requireSessionId(params, method));
			default -> inferredScope(method, params);
		};
	}

	private RouteScope inferredScope(String method, @Nullable Object params) {
		Optional<String> sessionId = extractSessionId(params);
		if (sessionId.isPresent()) {
			logger.warn("Falling back to inferred session routing for unknown method '{}'", method);
			return RouteScope.session(sessionId.get());
		}
		logger.warn("Falling back to inferred connection routing for unknown method '{}'", method);
		return RouteScope.connection();
	}

	private static RequestKind requestKind(String method) {
		return switch (method) {
			case AcpSchema.METHOD_INITIALIZE -> RequestKind.INITIALIZE;
			case AcpSchema.METHOD_SESSION_NEW -> RequestKind.SESSION_NEW;
			case AcpSchema.METHOD_SESSION_LOAD, AcpSchema.METHOD_SESSION_RESUME -> RequestKind.SESSION_LOAD;
			default -> RequestKind.GENERIC;
		};
	}

	/**
	 * Where the response to a client request is expected: on the connection stream for
	 * {@code session/load} and {@code session/resume}, else in the request's own scope.
	 */
	private static RouteScope responseScope(String method, RouteScope requestScope) {
		return switch (method) {
			case AcpSchema.METHOD_SESSION_LOAD, AcpSchema.METHOD_SESSION_RESUME -> RouteScope.connection();
			default -> requestScope;
		};
	}

	private Optional<String> extractSessionId(@Nullable Object params) {
		if (params == null) {
			return Optional.empty();
		}
		Map<?, ?> paramsMap = jsonMapper.convertValue(params, Map.class);
		Object sessionId = paramsMap.get("sessionId");
		return sessionId == null ? Optional.empty() : Optional.of(sessionId.toString());
	}

	private String requireSessionId(@Nullable Object params, String method) {
		return extractSessionId(params)
			.filter(sessionId -> !sessionId.isBlank())
			.orElseThrow(() -> new AcpConnectionException("Missing sessionId for outbound method " + method));
	}

	/** The message was posted: an answered agent request needs no route any more. */
	void posted(JSONRPCMessage message) {
		if (message instanceof AcpSchema.JSONRPCResponse response && response.id() != null) {
			inboundRequestRoutes.remove(response.id());
		}
	}

	/** The message could not be posted: no response to a failed client request will come. */
	void postFailed(JSONRPCMessage message) {
		if (message instanceof AcpSchema.JSONRPCRequest request && request.id() != null) {
			outboundRequestRoutes.remove(request.id());
		}
	}

	/**
	 * Routes a message that arrived on the SSE stream of {@code actualScope}: an agent
	 * request records the scope its answer must be posted in; a response is matched to the
	 * client request it answers.
	 */
	InboundRoute routeInbound(RouteScope actualScope, JSONRPCMessage message) {
		if (message instanceof AcpSchema.JSONRPCResponse response) {
			return routeResponse(actualScope, response);
		}
		if (message instanceof AcpSchema.JSONRPCRequest request && request.id() != null) {
			inboundRequestRoutes.put(request.id(), actualScope);
		}
		return InboundRoute.DELIVER;
	}

	private InboundRoute routeResponse(RouteScope actualScope, AcpSchema.JSONRPCResponse response) {
		Object responseId = response.id();
		if (responseId == null) {
			// JSON-RPC's answer to a request the agent could not parse: no route to match.
			return InboundRoute.DELIVER;
		}
		OutboundRequestRoute expectedRoute = outboundRequestRoutes.get(responseId);
		if (expectedRoute == null) {
			return InboundRoute.DELIVER;
		}
		if (!Objects.equals(expectedRoute.responseScope(), actualScope)) {
			// Peers differ on which stream carries session/load and session/resume replies
			// (the Rust server uses the session stream, TypeScript the connection stream).
			// The reply is still ours by id: deliver it.
			logger.warn("Response id {} arrived on {} but was expected on {}; delivering it anyway", responseId,
					actualScope, expectedRoute.responseScope());
		}
		return new InboundRoute(expectedRoute.kind() == RequestKind.SESSION_NEW, responseId);
	}

	/** Forgets a client request whose response has been delivered. */
	void answered(Object requestId) {
		outboundRequestRoutes.remove(requestId);
	}

	/** Whether a client request still awaits its response on the stream of this scope. */
	boolean hasPendingResponseFor(RouteScope scope) {
		return outboundRequestRoutes.values()
			.stream()
			.anyMatch(route -> Objects.equals(route.responseScope(), scope));
	}

	void clear() {
		inboundRequestRoutes.clear();
		outboundRequestRoutes.clear();
	}

}

/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpRouting.ClientRequestRoute;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpRouting.RequestKind;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpRouting.ResolvedInboundRoute;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpRouting.RouteScope;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpRouting.SessionState;
import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import static com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport.CONTENT_TYPE_EVENT_STREAM;
import static com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport.HEADER_CONNECTION_ID;
import static com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport.HEADER_SESSION_ID;

/**
 * One remote ACP connection over Streamable HTTP (POST/SSE): its agent runtime, its
 * connection stream and session streams, the sessions it knows (including provisional
 * {@code session/load} sessions), and the request-id route maps that send each response
 * back on the stream its request came from.
 *
 * @author Kaiser Dandangi
 */
final class StreamableHttpConnection {

	private static final Logger logger = LoggerFactory.getLogger(StreamableHttpConnection.class);


	private final String id;

	private final RemoteAcpConnection connection;

	private final SseOutboundStream connectionStream;

	private final ConcurrentMap<String, SseOutboundStream> sessionStreams = new ConcurrentHashMap<>();

	private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();

	// Client-originated request id -> route expected for the later agent response.
	private final ConcurrentMap<Object, ClientRequestRoute> clientRequestRoutes = new ConcurrentHashMap<>();

	// Agent-originated request id -> route required for the later client response.
	private final ConcurrentMap<Object, RouteScope> agentRequestRoutes = new ConcurrentHashMap<>();

	private final Sinks.One<JSONRPCMessage> initializeResponse = Sinks.one();

	private final AtomicBoolean initialized = new AtomicBoolean(false);

	private volatile Object initializeRequestId;

	private final AcpJsonMapper jsonMapper;

	private final AcpAgentFactory agentFactory;

	private final StreamableHttpRouting routing;

	private final StreamableHttpAcpAgentTransportOptions options;

	private final Consumer<StreamableHttpConnection> deregister;

	StreamableHttpConnection(String id, AcpJsonMapper jsonMapper, AcpAgentFactory agentFactory,
			StreamableHttpRouting routing, StreamableHttpAcpAgentTransportOptions options,
			Consumer<StreamableHttpConnection> deregister) {
		this.id = id;
		this.jsonMapper = jsonMapper;
		this.agentFactory = agentFactory;
		this.routing = routing;
		this.options = options;
		this.deregister = deregister;
		this.connectionStream = new SseOutboundStream(options.mailboxCapacity(), options.maxPendingSseEvents());
		this.connection = new RemoteAcpConnection(id, jsonMapper, this::routeAgentMessage);
	}

	String id() {
		return id;
	}

	Mono<Void> start() {
		return this.connection.start(agentFactory);
	}

	Mono<JSONRPCMessage> initialize(AcpSchema.JSONRPCRequest request) {
		this.initializeRequestId = request.id();
		connection.acceptInbound(request);
		return initializeResponse.asMono().doOnSuccess(ignored -> initialized.set(true));
	}

	void acceptClientPost(JSONRPCMessage message, String sessionHeader) {
		if (message instanceof AcpSchema.JSONRPCResponse response) {
			validateClientResponseScope(response, sessionHeader);
			connection.acceptInbound(message);
			return;
		}

		ResolvedInboundRoute resolved = routing.resolveInboundRoute(message, sessionHeader);
		if (resolved.requestScope().isSession()) {
			prepareSessionForInbound(resolved.requestScope().sessionId(), resolved.requestRoute());
		}
		if (message instanceof AcpSchema.JSONRPCRequest request && request.id() != null
				&& resolved.requestRoute() != null) {
			clientRequestRoutes.put(request.id(), resolved.requestRoute());
		}
		connection.acceptInbound(message);
	}

	void openStream(HttpServletRequest request, HttpServletResponse response, String sessionId)
			throws IOException {
		RouteScope scope = sessionId == null ? RouteScope.connection() : RouteScope.session(sessionId);
		SseOutboundStream stream;
		if (scope.isSession()) {
			stream = openSessionStream(scope.sessionId());
		}
		else {
			stream = connectionStream;
		}

		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType(CONTENT_TYPE_EVENT_STREAM);
		response.setHeader("Cache-Control", "no-cache");
		response.setHeader(HEADER_CONNECTION_ID, id);
		if (scope.isSession()) {
			response.setHeader(HEADER_SESSION_ID, scope.sessionId());
		}
		AsyncContext asyncContext = request.startAsync();
		asyncContext.setTimeout(0);
		stream.subscribe(asyncContext, response);
	}

	Mono<Void> closeGracefully() {
		deregister.accept(this);
		connectionStream.close();
		sessionStreams.values().forEach(SseOutboundStream::close);
		return connection.closeGracefully();
	}

	void close() {
		closeGracefully().subscribe(v -> {
		}, error -> logger.warn("Error closing Streamable HTTP ACP connection {}", id, error));
	}

	private void routeAgentMessage(JSONRPCMessage message) {
		try {
			if (message instanceof AcpSchema.JSONRPCResponse response
					&& Objects.equals(response.id(), initializeRequestId) && !initialized.get()) {
				initializeResponse.tryEmitValue(message);
				return;
			}

			RouteScope scope = resolveAgentOutboundScope(message);
			String payload = jsonMapper.writeValueAsString(message);
			if (scope.isSession()) {
				sessionStream(scope.sessionId()).push(payload);
			}
			else {
				connectionStream.push(payload);
			}
		}
		catch (Exception e) {
			connection.signalException(e);
			close();
		}
	}

	private RouteScope resolveAgentOutboundScope(JSONRPCMessage message) {
		if (message instanceof AcpSchema.JSONRPCResponse response) {
			ClientRequestRoute route = clientRequestRoutes.remove(response.id());
			if (route == null) {
				logger.warn("Agent emitted response for unknown client request id {}; routing to connection stream",
						response.id());
				return RouteScope.connection();
			}
			if ((route.kind() == RequestKind.SESSION_NEW || route.kind() == RequestKind.SESSION_FORK)
					&& response.error() == null) {
				// Both replies carry the id of a session that now exists on this connection.
				String sessionId = routing.extractSessionIdFromNewSessionResponse(response);
				markSessionKnown(sessionId);
			}
			if (route.kind() == RequestKind.SESSION_LOAD) {
				if (response.error() == null) {
					markSessionKnown(route.requestScope().sessionId());
				}
				else {
					discardProvisionalSession(route.requestScope().sessionId());
				}
			}
			return route.responseScope();
		}

		String method;
		Object params;
		Object id = null;
		if (message instanceof AcpSchema.JSONRPCRequest request) {
			method = request.method();
			params = request.params();
			id = request.id();
		}
		else if (message instanceof AcpSchema.JSONRPCNotification notification) {
			method = notification.method();
			params = notification.params();
		}
		else {
			throw new AcpConnectionException("Unsupported outbound JSON-RPC message type: " + message);
		}

		RouteScope scope = routing.resolveAgentRequestOrNotificationScope(method, params);
		if (id != null) {
			agentRequestRoutes.put(id, scope);
		}
		return scope;
	}

	private void prepareSessionForInbound(String sessionId, ClientRequestRoute route) {
		SessionState current = sessions.get(sessionId);
		if (route != null && route.kind() == RequestKind.SESSION_LOAD) {
			if (current == null) {
				addProvisionalSession(sessionId);
				sessionStream(sessionId);
			}
			return;
		}
		if (current != SessionState.KNOWN) {
			throw new UnknownSessionException("Unknown session " + sessionId);
		}
	}

	private void validateClientResponseScope(AcpSchema.JSONRPCResponse response, String sessionHeader) {
		RouteScope expected = agentRequestRoutes.get(response.id());
		if (expected == null) {
			logger.warn("Client posted response for unknown agent request id {}", response.id());
			return;
		}
		RouteScope actual = sessionHeader == null ? RouteScope.connection() : RouteScope.session(sessionHeader);
		if (!Objects.equals(expected, actual)) {
			throw new AcpConnectionException(
					"Response id " + response.id() + " arrived on " + actual + " but expected " + expected);
		}
		agentRequestRoutes.remove(response.id(), expected);
	}

	private SseOutboundStream openSessionStream(String sessionId) {
		SessionState current = sessions.get(sessionId);
		if (current == null) {
			/*
			 * RFD gap:
			 * The current text says unknown session-scoped GET requests return 404,
			 * but its resume flow also asks clients to open a session stream before
			 * sending session/load. Keep a provisional stream so practical resume can work.
			 */
			addProvisionalSession(sessionId);
		}
		return sessionStream(sessionId);
	}

	/** Provisional sessions are bounded: a client cannot grow state with arbitrary ids. */
	private void addProvisionalSession(String sessionId) {
		long provisional = sessions.values().stream().filter(state -> state == SessionState.PENDING_LOAD).count();
		if (provisional >= options.maxProvisionalSessions()
				&& sessions.get(sessionId) != SessionState.PENDING_LOAD) {
			throw new UnknownSessionException("Too many provisional sessions on connection " + id
					+ " (limit " + options.maxProvisionalSessions() + ")");
		}
		sessions.putIfAbsent(sessionId, SessionState.PENDING_LOAD);
	}

	/** A failed session/load leaves no provisional state behind. */
	private void discardProvisionalSession(String sessionId) {
		if (sessions.remove(sessionId, SessionState.PENDING_LOAD)) {
			SseOutboundStream stream = sessionStreams.remove(sessionId);
			if (stream != null) {
				stream.close();
			}
		}
	}

	void keepAlive() {
		connectionStream.keepAlive();
		sessionStreams.values().forEach(SseOutboundStream::keepAlive);
	}

	private SseOutboundStream sessionStream(String sessionId) {
		return sessionStreams.computeIfAbsent(sessionId,
				ignored -> new SseOutboundStream(options.mailboxCapacity(), options.maxPendingSseEvents()));
	}

	private void markSessionKnown(String sessionId) {
		sessions.put(sessionId, SessionState.KNOWN);
		sessionStream(sessionId);
	}

	static final class UnknownSessionException extends RuntimeException {

		UnknownSessionException(String message) {
			super(message);
		}

	}

}

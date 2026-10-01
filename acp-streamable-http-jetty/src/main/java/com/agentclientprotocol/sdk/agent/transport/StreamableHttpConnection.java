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
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpRouting.MethodCall;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpRouting.RequestKind;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpRouting.ResolvedInboundRoute;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpRouting.RouteScope;
import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import static com.agentclientprotocol.sdk.agent.transport.StreamableHttpRouting.CONTENT_TYPE_EVENT_STREAM;
import static com.agentclientprotocol.sdk.agent.transport.StreamableHttpRouting.HEADER_CONNECTION_ID;
import static com.agentclientprotocol.sdk.agent.transport.StreamableHttpRouting.HEADER_SESSION_ID;

/**
 * One remote ACP connection over Streamable HTTP (POST/SSE): its agent runtime, its
 * connection stream, its sessions and their streams ({@link StreamableHttpSessions}), and
 * the request-id route maps that send each response back on the stream its request came
 * from.
 *
 * @author Kaiser Dandangi
 */
final class StreamableHttpConnection {

	private static final Logger logger = LoggerFactory.getLogger(StreamableHttpConnection.class);


	private final String id;

	private final RemoteAcpConnection connection;

	private final SseOutboundStream connectionStream;

	private final StreamableHttpSessions sessions;

	// Client-originated request id -> route expected for the later agent response.
	private final ConcurrentMap<Object, ClientRequestRoute> clientRequestRoutes = new ConcurrentHashMap<>();

	// Agent-originated request id -> route required for the later client response.
	private final ConcurrentMap<Object, RouteScope> agentRequestRoutes = new ConcurrentHashMap<>();

	private final Sinks.One<JSONRPCMessage> initializeResponse = Sinks.one();

	private final AtomicBoolean initialized = new AtomicBoolean(false);

	private volatile @Nullable Object initializeRequestId;

	private final AcpJsonMapper jsonMapper;

	private final AcpAgentFactory agentFactory;

	private final StreamableHttpRouting routing;

	private final Consumer<StreamableHttpConnection> deregister;

	/**
	 * What a connection needs from the servlet that holds it.
	 * @param deregister removes the connection from the servlet once it closes
	 * @param exceptionHandler receives the connection's transport errors
	 */
	record Owner(Consumer<StreamableHttpConnection> deregister, Consumer<Throwable> exceptionHandler) {
	}

	StreamableHttpConnection(String id, AcpJsonMapper jsonMapper, AcpAgentFactory agentFactory,
			StreamableHttpRouting routing, StreamableHttpAcpAgentTransportOptions options, Owner owner) {
		this.id = id;
		this.jsonMapper = jsonMapper;
		this.agentFactory = agentFactory;
		this.routing = routing;
		this.deregister = owner.deregister();
		this.connectionStream = new SseOutboundStream(options.mailboxCapacity(), options.maxPendingSseEvents());
		this.sessions = new StreamableHttpSessions(id, options);
		this.connection = new RemoteAcpConnection(id, jsonMapper, this::routeAgentMessage, owner.exceptionHandler());
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

	void acceptClientPost(JSONRPCMessage message, @Nullable String sessionHeader) {
		if (message instanceof AcpSchema.JSONRPCResponse response) {
			validateClientResponseScope(response, sessionHeader);
			connection.acceptInbound(message);
			return;
		}

		ResolvedInboundRoute resolved = routing.resolveInboundRoute(message, sessionHeader);
		if (resolved.requestScope().isSession()) {
			ClientRequestRoute route = resolved.requestRoute();
			sessions.admitInbound(resolved.requestScope().boundSessionId(),
					route != null && route.kind() == RequestKind.SESSION_LOAD);
		}
		if (message instanceof AcpSchema.JSONRPCRequest request && request.id() != null
				&& resolved.requestRoute() != null) {
			clientRequestRoutes.put(request.id(), resolved.requestRoute());
		}
		connection.acceptInbound(message);
	}

	void openStream(HttpServletRequest request, HttpServletResponse response, @Nullable String sessionId)
			throws IOException {
		RouteScope scope = sessionId == null ? RouteScope.connection() : RouteScope.session(sessionId);
		SseOutboundStream stream;
		if (scope.isSession()) {
			stream = sessions.openStream(scope.boundSessionId());
		}
		else {
			stream = connectionStream;
		}

		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType(CONTENT_TYPE_EVENT_STREAM);
		response.setHeader("Cache-Control", "no-cache");
		response.setHeader(HEADER_CONNECTION_ID, id);
		if (scope.isSession()) {
			response.setHeader(HEADER_SESSION_ID, scope.boundSessionId());
		}
		AsyncContext asyncContext = request.startAsync();
		asyncContext.setTimeout(0);
		stream.subscribe(asyncContext, response);
	}

	Mono<Void> closeGracefully() {
		deregister.accept(this);
		connectionStream.close();
		sessions.close();
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
				sessions.stream(scope.boundSessionId()).push(payload);
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
			return resolveResponseScope(response);
		}
		MethodCall call = MethodCall.of(message, "outbound");
		RouteScope scope = routing.resolveAgentRequestOrNotificationScope(call.method(), call.params());
		Object requestId = call.id();
		if (requestId != null) {
			agentRequestRoutes.put(requestId, scope);
		}
		return scope;
	}

	/** The stream the client request answered by {@code response} asked for its reply. */
	private RouteScope resolveResponseScope(AcpSchema.JSONRPCResponse response) {
		Object responseId = response.id();
		if (responseId == null) {
			// The answer to a request posted with "id": null, which was never routed (a
			// ConcurrentMap holds no null key); only the connection stream can carry it.
			return RouteScope.connection();
		}
		ClientRequestRoute route = clientRequestRoutes.remove(responseId);
		if (route == null) {
			logger.warn("Agent emitted response for unknown client request id {}; routing to connection stream",
					responseId);
			return RouteScope.connection();
		}
		recordSessionOutcome(route, response);
		return route.responseScope();
	}

	/** Applies what a reply to session/new, session/fork or session/load says about its session. */
	private void recordSessionOutcome(ClientRequestRoute route, AcpSchema.JSONRPCResponse response) {
		boolean succeeded = response.error() == null;
		switch (route.kind()) {
			case SESSION_NEW, SESSION_FORK -> {
				// Both replies carry the id of a session that now exists on this connection.
				if (succeeded) {
					sessions.markKnown(routing.extractSessionIdFromNewSessionResponse(response));
				}
			}
			case SESSION_LOAD -> {
				if (succeeded) {
					sessions.markKnown(route.requestScope().boundSessionId());
				}
				else {
					sessions.discardProvisional(route.requestScope().boundSessionId());
				}
			}
			default -> {
				// No session changes state.
			}
		}
	}

	private void validateClientResponseScope(AcpSchema.JSONRPCResponse response, @Nullable String sessionHeader) {
		Object responseId = response.id();
		if (responseId == null) {
			// JSON-RPC's answer to a request the client could not parse: it names no agent
			// request, so there is no scope to check. The session logs and drops it.
			return;
		}
		RouteScope expected = agentRequestRoutes.get(responseId);
		if (expected == null) {
			logger.warn("Client posted response for unknown agent request id {}", responseId);
			return;
		}
		if (sessionHeader == null) {
			// The RFD asks for Acp-Session-Id on permission responses; the Rust and Python
			// clients omit it on every response. The id alone identifies the exchange, so a
			// missing header is accepted. A header naming a different scope is still an error.
			logger.debug("Client response {} carried no {}; accepting it for {}", responseId,
					HEADER_SESSION_ID, expected);
			agentRequestRoutes.remove(responseId, expected);
			return;
		}
		RouteScope actual = RouteScope.session(sessionHeader);
		if (!Objects.equals(expected, actual)) {
			throw new AcpConnectionException(
					"Response id " + responseId + " arrived on " + actual + " but expected " + expected);
		}
		agentRequestRoutes.remove(responseId, expected);
	}

	void keepAlive() {
		connectionStream.keepAlive();
		sessions.keepAlive();
	}

}

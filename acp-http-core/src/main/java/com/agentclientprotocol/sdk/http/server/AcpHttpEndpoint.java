/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.server;

import java.util.function.Consumer;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import com.agentclientprotocol.sdk.annotation.UnstableAcpApi;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import reactor.core.publisher.Mono;

/**
 * The ACP Streamable HTTP and WebSocket endpoint, independent of any server: one per mounted
 * path, holding every client connection on it and creating one agent per connection with an
 * {@link AcpAgentFactory}. A host (a servlet, a framework's router) mounts it and adapts I/O;
 * every protocol rule is here.
 *
 * <p>A host:
 * <ul>
 * <li>hands every request on the path to {@link #handle}, or a WebSocket upgrade request
 * ({@link AcpHttpExchange#isWebSocketUpgrade()}) to {@link #webSocketHandshake}, and writes the
 * answer as it is;</li>
 * <li>calls {@link #start()} when its server starts serving the path, and
 * {@link #closeGracefully()} <em>before</em> its server's own graceful shutdown, since open SSE
 * streams and WebSockets never finish by themselves;</li>
 * <li>never blocks an I/O thread on the endpoint: {@link #handle} returns a {@link Mono} and
 * the agent's work runs on the SDK's schedulers.</li>
 * </ul>
 *
 * <p>The endpoint answers 403 to a request whose {@code Origin} is neither a loopback origin nor
 * listed in {@link StreamableHttpAcpAgentTransportOptions#allowedOrigins()}, over HTTP and on the
 * handshake. It has no authentication: the host's framework security protects the path, and
 * hands the principal over in {@link AcpHttpExchange#principal()}.
 *
 * @author Mark Pollack
 */
@UnstableAcpApi
public interface AcpHttpEndpoint {

	/**
	 * Creates an endpoint.
	 * @param jsonMapper the mapper that reads and writes the messages
	 * @param agentFactory creates the agent for each connection
	 * @param options the endpoint's limits, timings and allowed origins
	 * @return the endpoint, not started
	 * @throws IllegalArgumentException if an argument is null
	 */
	static AcpHttpEndpoint create(AcpJsonMapper jsonMapper, AcpAgentFactory agentFactory,
			StreamableHttpAcpAgentTransportOptions options) {
		return new DefaultAcpHttpEndpoint(jsonMapper, agentFactory, options);
	}

	/**
	 * Returns the options the endpoint was created with; a host applies the ones that concern
	 * its server, such as the inbound size limit.
	 * @return the options
	 */
	StreamableHttpAcpAgentTransportOptions options();

	/**
	 * Answers one HTTP request: a POST carrying a JSON-RPC message, a GET opening an SSE stream,
	 * or a DELETE closing a connection. The returned Mono never errors: every refusal, and any
	 * unexpected failure (500), is a reply.
	 * @param exchange the request
	 * @return the reply to write
	 */
	Mono<AcpHttpReply> handle(AcpHttpExchange exchange);

	/**
	 * Decides a WebSocket upgrade request: refused (with the reply to write) or accepted.
	 * @param handshake the upgrade request
	 * @return the decision
	 */
	AcpWsHandshake webSocketHandshake(AcpHttpExchange handshake);

	/**
	 * Starts the SSE keep-alive. Idempotent; the endpoint serves requests without it too.
	 */
	void start();

	/**
	 * Closes every connection: new connections are refused (503), an {@code initialize} still in
	 * flight is answered 503, open SSE streams get a closing comment and complete, WebSockets are
	 * closed with 1001 (going away), and each connection's agent is closed, cancelling in-flight
	 * prompts. Nothing waits for a client. A connection whose agent has not closed within the
	 * {@linkplain StreamableHttpAcpAgentTransportOptions#shutdownTimeout() shutdown timeout} is
	 * closed at once. Only the first call has an effect.
	 * @return a Mono that completes when every connection has closed
	 */
	Mono<Void> closeGracefully();

	/**
	 * Returns the number of open connections, HTTP and WebSocket, not counting those whose
	 * {@code initialize} is still being answered.
	 * @return the connection count
	 */
	int activeConnectionCount();

	/**
	 * Sets the handler for the transport errors of every connection, including those opened
	 * before the call. The default logs them.
	 * @param handler receives the connections' transport errors
	 */
	void setExceptionHandler(Consumer<Throwable> handler);

}

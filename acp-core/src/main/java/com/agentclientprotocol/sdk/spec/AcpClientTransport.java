/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.function.Consumer;
import java.util.function.Function;

import reactor.core.publisher.Mono;

/**
 * The client side of an ACP connection: the transport through which a client built with
 * {@link com.agentclientprotocol.sdk.client.AcpClient} reaches its agent. Create one per agent
 * connection and pass it to {@code AcpClient.sync(transport)} or
 * {@code AcpClient.async(transport)}; building the client connects the transport, and
 * closing the client closes it.
 *
 * <p>The SDK has three.
 * {@link com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport} starts the agent
 * as a child process;
 * {@link com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport} and
 * {@link com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport} reach an
 * agent that is already running behind a URL. For tests, the {@code acp-test} module's
 * {@code InMemoryTransportPair} connects a client and an agent inside one JVM.
 *
 * <p>Implementations carry one connection each and must refuse a second {@link #connect}. The
 * client session passes the agent's messages to the handler given to {@code connect} and
 * sends its answers with {@link #sendMessage}; its handler returns an empty Mono, so an
 * implementation need not write what the handler returns.
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 */
public interface AcpClientTransport extends AcpTransport {

	/**
	 * Connects to the agent, or prepares to, and registers the handler for every message the
	 * agent sends. The client session calls it once, when the client is built.
	 * @param handler receives each message from the agent as a one-element Mono
	 * @return a Mono that completes once the transport can be used, which need not mean that a
	 * connection is open (the Streamable HTTP transport opens it with the first request); it
	 * errors when the connection cannot be made or the transport was connected before
	 */
	Mono<Void> connect(Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> handler);

	/**
	 * Sets the handler for errors that belong to no single request: a line or frame from the
	 * agent that is not a JSON-RPC message, or a failed read or write. The SDK installs none;
	 * its transports log such errors by default. The interface's default ignores the handler.
	 * @param handler receives the transport's errors
	 */
	default void setExceptionHandler(Consumer<Throwable> handler) {
	}

	/**
	 * Returns a Mono that completes when this transport can no longer deliver messages: the
	 * agent closed the connection, the transport failed for good, or it was closed locally.
	 * It errors with the cause when the transport failed. The client session waits on it to
	 * fail pending requests at once instead of letting them run into the request timeout.
	 * The interface's default never completes, as suits a transport that cannot tell.
	 * @return a Mono that terminates when the transport does
	 */
	default Mono<Void> awaitTermination() {
		return Mono.never();
	}

}

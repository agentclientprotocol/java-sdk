/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.function.Consumer;
import java.util.function.Function;

import reactor.core.publisher.Mono;

/**
 * The agent side of an ACP connection: the transport through which an agent built with
 * {@link com.agentclientprotocol.sdk.agent.AcpAgent} serves one client. Pass it to
 * {@link com.agentclientprotocol.sdk.agent.AcpAgent#sync(AcpAgentTransport)
 * AcpAgent.sync(transport)} or
 * {@link com.agentclientprotocol.sdk.agent.AcpAgent#async(AcpAgentTransport)
 * AcpAgent.async(transport)}; starting the agent starts the transport, and the agent's
 * {@code awaitTermination()}, which {@code AcpSyncAgent.run()} blocks on, waits for this
 * transport's {@link #awaitTermination()}.
 *
 * <p>{@link com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport} serves the
 * client that started the agent process. Listeners that serve many clients over the network
 * are not agent transports themselves: the {@code acp-streamable-http-jetty} module's
 * {@code StreamableHttpAcpAgentTransport} and {@code StreamableHttpAcpServlet} make one per
 * connection ({@link com.agentclientprotocol.sdk.agent.transport.RemoteAcpConnection}) and hand
 * it to an {@link com.agentclientprotocol.sdk.agent.AcpAgentFactory}. For tests, the
 * {@code acp-test} module's {@code InMemoryTransportPair} connects an agent and a client inside
 * one JVM.
 *
 * <p>Implementations carry one connection each and must refuse a second {@link #start}. They
 * pass each message from the client to the session's handler and write what the handler's
 * Mono emits, the response to a request, back to the client.
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 */
public interface AcpAgentTransport extends AcpTransport {

	/**
	 * Starts reading messages from the client and registers the handler that answers them.
	 * The agent session calls it once, when the agent starts.
	 * @param handler receives each message from the client as a one-element Mono, and emits
	 * the response to write back, or nothing for a notification or a response
	 * @return a Mono that completes once the transport can receive and send; it errors when
	 * the transport cannot start or was started before
	 */
	Mono<Void> start(Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> handler);

	/**
	 * Sets the handler for errors that belong to no single request: a line or frame from the
	 * client that is not a JSON-RPC message, or a failed read or write. The SDK's agents
	 * install none; its transports log such errors by default. The interface's default
	 * ignores the handler.
	 * @param handler receives the transport's errors
	 */
	default void setExceptionHandler(Consumer<Throwable> handler) {
	}

	/**
	 * Returns a Mono that completes when the transport has ended: it was closed, or the client
	 * went away (for stdio, once standard input ended and every request received before it was
	 * answered). An agent's main thread blocks on it so that the process stays up while the
	 * transport serves the client; {@code AcpSyncAgent.run()} does this for you.
	 *
	 * <pre>{@code
	 * AcpAgentTransport transport = new StdioAcpAgentTransport();
	 * AcpSyncAgent agent = AcpAgent.sync(transport)
	 *     .initializeHandler(request -> AcpSchema.InitializeResponse.ok())
	 *     .build();
	 * agent.start();
	 * transport.awaitTermination().block(); // until standard input ends and every reply is written
	 * }</pre>
	 * @return a Mono that completes when the transport terminates
	 */
	Mono<Void> awaitTermination();

}

/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import com.agentclientprotocol.sdk.util.Assert;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * An in-process ACP connection: two connected transports, {@link #clientTransport()} and
 * {@link #agentTransport()}, that link a client and an agent running in the same JVM without a
 * child process, a socket or a port. Build the agent on one and the client on the other, as an
 * application does that serves its own agent to its own code (a web endpoint that drives an
 * agent, for instance), or as a test does.
 *
 * <pre>{@code
 * InMemoryTransportPair pair = InMemoryTransportPair.create();
 *
 * AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport())
 *     .initializeHandler(request -> AcpSchema.InitializeResponse.ok())
 *     .newSessionHandler(request -> new AcpSchema.NewSessionResponse("session-1", null, null))
 *     .promptHandler((request, context) -> {
 *         context.sendMessage("Hello");
 *         return AcpSchema.PromptResponse.endTurn();
 *     })
 *     .build();
 * agent.start();
 *
 * AcpSyncClient client = AcpClient.sync(pair.clientTransport()).build();
 * client.initialize();
 * String sessionId = client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of()))
 *     .sessionId();
 * client.prompt(new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent("Hi"))));
 *
 * client.close(); // ends the connection: the agent transport terminates too
 * agent.close();
 * }</pre>
 *
 * <p><b>Messages.</b> The two sides exchange ACP's JSON-RPC messages, with the protocol's
 * methods, ids and lifecycle, but as {@link AcpSchema} objects: nothing is written as JSON or
 * parsed. A side converts the params and results it receives to the records it expects with the
 * pair's {@link AcpJsonMapper}, one mapper for the pair, from {@link #create()} or given to
 * {@link #create(AcpJsonMapper)}. The receiver gets the sender's objects, not copies, so do not
 * change a map or list after sending it. A test of what goes over the wire as text needs a real
 * transport instead.
 *
 * <p><b>Delivery.</b> Each side receives what the other sends, in the order it was sent. A
 * message sent before the other side has started waits until it starts, so the client and the
 * agent can be built and started in either order. Both transports accept sends from several
 * threads at once, and a send never waits for the receiver: the message is delivered to the
 * receiving session on the thread of a sender, the first of the senders that finds no
 * delivery in progress. The sessions hand handler work to their own schedulers, so a sending
 * thread is not held for a handler's duration.
 *
 * <p><b>One connection.</b> Each pair carries one connection: a second connect or start fails
 * with {@link IllegalStateException}, so use a new pair for every client and agent.
 *
 * <p><b>The end of the connection.</b> Closing either transport, or the client or agent built
 * on it, or calling {@link #closeGracefully()}, ends the connection for both sides at once, as
 * closing a socket does:
 * <ul>
 * <li>both transports' {@code awaitTermination()} complete, so {@code AcpSyncAgent.run()}
 * returns once the client closes, and the client fails its requests still waiting for the agent
 * at once rather than at their timeout;</li>
 * <li>a request the agent sent to the client and that is still unanswered fails with a JSON-RPC
 * error ({@code -32603}), as on stdio when the client goes away;</li>
 * <li>messages sent before the end are still delivered; a send after it fails with an
 * {@link AcpConnectionException}.</li>
 * </ul>
 * An application that keeps its agent running after its client goes away therefore builds a new
 * pair, and a new agent on it, for the next client.
 *
 * @author Mark Pollack
 */
public final class InMemoryTransportPair {

	private static final Logger logger = LoggerFactory.getLogger(InMemoryTransportPair.class);

	/** The message of the error that fails a send once the connection has ended. */
	static final String CONNECTION_ENDED = "The in-memory ACP connection has ended";

	/** The message of the error that fails a request to the client once the connection has ended. */
	static final String CLIENT_GONE = "The in-memory ACP connection has ended; the client can no longer answer requests";

	private final AcpJsonMapper jsonMapper;

	private final InMemoryChannel<JSONRPCMessage> clientToAgent = new InMemoryChannel<>();

	private final InMemoryChannel<JSONRPCMessage> agentToClient = new InMemoryChannel<>();

	private final Sinks.One<Void> ended = Sinks.one();

	private final AtomicBoolean disconnected = new AtomicBoolean();

	private final ClientSide clientTransport = new ClientSide();

	private final AgentSide agentTransport = new AgentSide();

	private InMemoryTransportPair(AcpJsonMapper jsonMapper) {
		Assert.notNull(jsonMapper, "The jsonMapper can not be null");
		this.jsonMapper = jsonMapper;
	}

	/**
	 * Creates a pair whose transports convert params and results with a mapper from
	 * {@link AcpJsonMapper#createDefault()}, so a JSON module ({@code acp-json-jackson2} or
	 * {@code acp-json-jackson3}) must be on the classpath. Neither transport is connected or
	 * started yet.
	 * @return a new pair
	 * @throws IllegalStateException if no JSON module is on the classpath
	 */
	public static InMemoryTransportPair create() {
		return new InMemoryTransportPair(AcpJsonMapper.createDefault());
	}

	/**
	 * Creates a pair whose transports convert params and results with the given mapper, for
	 * example one built around an application's own Jackson mapper. Neither transport is
	 * connected or started yet.
	 * @param jsonMapper the mapper both transports use
	 * @return a new pair
	 */
	public static InMemoryTransportPair create(AcpJsonMapper jsonMapper) {
		return new InMemoryTransportPair(jsonMapper);
	}

	/**
	 * Returns the client side of the pair. Pass it to {@code AcpClient.sync(...)} or
	 * {@code AcpClient.async(...)}; it can be connected once.
	 * @return the client transport, the same instance on every call
	 */
	public AcpClientTransport clientTransport() {
		return this.clientTransport;
	}

	/**
	 * Returns the agent side of the pair. Pass it to {@code AcpAgent.sync(...)} or
	 * {@code AcpAgent.async(...)}; it can be started once.
	 * @return the agent transport, the same instance on every call
	 */
	public AcpAgentTransport agentTransport() {
		return this.agentTransport;
	}

	/**
	 * Ends the connection, as closing either transport does (see the class documentation).
	 * It can be called more than once, before or after either side is closed; only the first
	 * close ends the connection.
	 * @return a {@code Mono} that completes when the connection has ended
	 */
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(this::disconnect);
	}

	/** Ends both directions and both transports, once. */
	private void disconnect() {
		if (!this.disconnected.compareAndSet(false, true)) {
			return;
		}
		logger.debug("In-memory ACP connection ending");
		this.clientToAgent.close();
		this.agentToClient.close();
		this.ended.tryEmitEmpty();
	}

	private static Mono<Void> send(InMemoryChannel<JSONRPCMessage> channel, JSONRPCMessage message) {
		return Mono.defer(() -> channel.send(message) ? Mono.empty()
				: Mono.error(new AcpConnectionException(CONNECTION_ENDED)));
	}

	private static void logError(Throwable error) {
		logger.warn("In-memory ACP transport error: {}", error.toString(), error);
	}

	/** The client side: receives what the agent sends and sends to the agent. */
	private final class ClientSide implements AcpClientTransport {

		private final AtomicBoolean connected = new AtomicBoolean();

		private volatile Consumer<Throwable> exceptionHandler = InMemoryTransportPair::logError;

		/**
		 * {@inheritDoc}
		 * <p>Completes at once; the agent's messages are delivered from then on, including those
		 * it sent before.
		 */
		@Override
		public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
			return Mono.defer(() -> {
				if (!this.connected.compareAndSet(false, true)) {
					return Mono.error(new IllegalStateException("Already connected"));
				}
				agentToClient.asFlux()
					.flatMap(message -> Mono.just(message)
						.transform(handler)
						// One message's failure must not end the subscription for the rest.
						.onErrorResume(error -> {
							this.exceptionHandler.accept(error);
							return Mono.empty();
						}))
					.subscribe(ignored -> {
					}, error -> this.exceptionHandler.accept(error));
				return Mono.empty();
			});
		}

		/**
		 * {@inheritDoc}
		 * <p>Completes once the message is queued for the agent; after the end of the connection
		 * it fails with an {@link AcpConnectionException}.
		 */
		@Override
		public Mono<Void> sendMessage(JSONRPCMessage message) {
			return send(clientToAgent, message);
		}

		@Override
		public Mono<Void> closeGracefully() {
			return InMemoryTransportPair.this.closeGracefully();
		}

		/**
		 * {@inheritDoc}
		 * <p>The default logs each error at WARN.
		 */
		@Override
		public void setExceptionHandler(Consumer<Throwable> handler) {
			this.exceptionHandler = handler;
		}

		/**
		 * {@inheritDoc}
		 * <p>Completes when the connection ends, from either side.
		 */
		@Override
		public Mono<Void> awaitTermination() {
			return ended.asMono();
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			return jsonMapper.convertValue(data, typeRef);
		}

	}

	/** The agent side: receives what the client sends and answers it. */
	private final class AgentSide implements AcpAgentTransport {

		private final AtomicBoolean started = new AtomicBoolean();

		/** The agent's requests to the client that the client has not answered yet. */
		private final UnansweredRequests awaitingClient = new UnansweredRequests();

		private volatile @Nullable Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler;

		private volatile Consumer<Throwable> exceptionHandler = InMemoryTransportPair::logError;

		/**
		 * {@inheritDoc}
		 * <p>Completes at once; the client's messages are delivered from then on, including
		 * those it sent before.
		 */
		@Override
		public Mono<Void> start(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
			return Mono.defer(() -> {
				if (!this.started.compareAndSet(false, true)) {
					return Mono.error(new IllegalStateException("Already started"));
				}
				this.handler = handler;
				clientToAgent.asFlux()
					.doOnNext(this::recordAnswer)
					// Every message the client sent has been handed on: no answer can come now.
					.doOnComplete(this::clientInputEnded)
					.flatMap(message -> Mono.just(message)
						.transform(handler)
						.flatMap(response -> send(agentToClient, response))
						// One message's failure (an answer after the end, for one) must not end
						// the subscription for the rest.
						.onErrorResume(error -> {
							this.exceptionHandler.accept(error);
							return Mono.empty();
						}))
					.subscribe(ignored -> {
					}, error -> this.exceptionHandler.accept(error));
				return Mono.empty();
			});
		}

		private void recordAnswer(JSONRPCMessage message) {
			if (message instanceof AcpSchema.JSONRPCResponse response && response.id() != null) {
				this.awaitingClient.answered(response.id());
			}
		}

		/** Fails each request to the client still waiting, as the session fails any request. */
		private void clientInputEnded() {
			Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> sessionHandler = this.handler;
			for (Object id : this.awaitingClient.end()) {
				if (sessionHandler == null) {
					continue;
				}
				JSONRPCMessage failure = new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, id, null,
						new AcpSchema.JSONRPCError(AcpErrorCodes.INTERNAL_ERROR, CLIENT_GONE, null));
				Mono.just(failure)
					.transform(sessionHandler)
					.subscribe(reply -> logger.debug("Unexpected reply to a failed request: {}", reply),
							error -> logger.debug("Failing request {} to the client failed", id, error));
			}
		}

		/**
		 * {@inheritDoc}
		 * <p>Completes once the message is queued for the client. After the end of the
		 * connection it fails with an {@link AcpConnectionException}.
		 */
		@Override
		public Mono<Void> sendMessage(JSONRPCMessage message) {
			if (!(message instanceof AcpSchema.JSONRPCRequest request) || request.id() == null) {
				return send(agentToClient, message);
			}
			Object id = request.id();
			return Mono.defer(() -> {
				if (!this.awaitingClient.sent(id)) {
					return Mono.error(new AcpConnectionException(CLIENT_GONE));
				}
				if (!agentToClient.send(message)) {
					this.awaitingClient.answered(id);
					return Mono.error(new AcpConnectionException(CONNECTION_ENDED));
				}
				return Mono.empty();
			});
		}

		@Override
		public Mono<Void> closeGracefully() {
			return InMemoryTransportPair.this.closeGracefully();
		}

		/**
		 * {@inheritDoc}
		 * <p>The default logs each error at WARN.
		 */
		@Override
		public void setExceptionHandler(Consumer<Throwable> handler) {
			this.exceptionHandler = handler;
		}

		/**
		 * {@inheritDoc}
		 * <p>Completes when the connection ends, from either side: the client or agent was
		 * closed, or {@link InMemoryTransportPair#closeGracefully()} was called.
		 */
		@Override
		public Mono<Void> awaitTermination() {
			return ended.asMono();
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			return jsonMapper.convertValue(data, typeRef);
		}

	}

}

/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import com.agentclientprotocol.sdk.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Shared per-connection core for listener-backed remote ACP agent transports.
 *
 * <p>
 * Remote transports such as Streamable HTTP and WebSocket have different wire-level
 * framing, but they both need the same agent-side shape once a remote ACP connection
 * exists: one connection-bound {@link AcpAgentTransport}, one fresh agent runtime from
 * {@link AcpAgentFactory}, inbound JSON-RPC delivery to the agent, and outbound JSON-RPC
 * delivery back to the wire adapter.
 * </p>
 *
 * <p>
 * This class intentionally does not know about HTTP headers, SSE streams, WebSocket
 * sessions, or route maps. Those remain transport-adapter concerns.
 * </p>
 *
 * @author Kaiser Dandangi
 */
public final class RemoteAcpConnection {

	private static final Logger logger = LoggerFactory.getLogger(RemoteAcpConnection.class);

	private final String id;

	private final AcpJsonMapper jsonMapper;

	private final ConnectionTransport transport;

	private final AtomicBoolean started = new AtomicBoolean(false);

	private final AtomicBoolean closing = new AtomicBoolean(false);

	private volatile AcpAsyncAgent agent;

	/**
	 * Creates a new remote ACP connection core.
	 * @param id stable transport connection id
	 * @param jsonMapper JSON mapper used by the connection transport
	 * @param outboundConsumer callback that receives agent-originated outbound messages
	 */
	public RemoteAcpConnection(String id, AcpJsonMapper jsonMapper, Consumer<JSONRPCMessage> outboundConsumer) {
		Assert.hasText(id, "The id can not be empty");
		Assert.notNull(jsonMapper, "The jsonMapper can not be null");
		Assert.notNull(outboundConsumer, "The outboundConsumer can not be null");
		this.id = id;
		this.jsonMapper = jsonMapper;
		this.transport = new ConnectionTransport(outboundConsumer);
	}

	/**
	 * Returns the transport-level connection id.
	 * @return connection id
	 */
	public String id() {
		return id;
	}

	/**
	 * Starts a fresh agent runtime for this connection.
	 * @param agentFactory factory used to create the connection-bound agent runtime
	 * @return mono that completes when the agent runtime is started
	 */
	public Mono<Void> start(AcpAgentFactory agentFactory) {
		Assert.notNull(agentFactory, "The agentFactory can not be null");
		if (!started.compareAndSet(false, true)) {
			return Mono.error(new IllegalStateException("Already started"));
		}
		return Mono.defer(() -> {
			this.agent = agentFactory.create(transport);
			return this.agent.start();
		}).doOnError(this::signalException);
	}

	/**
	 * Accepts one client-originated JSON-RPC message for delivery to the connection's
	 * agent runtime.
	 * @param message inbound message
	 */
	public void acceptInbound(JSONRPCMessage message) {
		transport.acceptInbound(message);
	}

	/**
	 * Reports a transport adapter exception to the agent transport exception handler.
	 * @param error exception to report
	 */
	public void signalException(Throwable error) {
		transport.signalException(error);
	}

	/**
	 * Closes the connection and its agent runtime gracefully.
	 * @return mono that completes when close work has been requested
	 */
	public Mono<Void> closeGracefully() {
		return Mono.defer(() -> {
			if (!closing.compareAndSet(false, true)) {
				return Mono.empty();
			}
			AcpAsyncAgent currentAgent = this.agent;
			if (currentAgent != null) {
				return currentAgent.closeGracefully()
					.onErrorResume(error -> {
						signalException(error);
						return Mono.empty();
					})
					.then(transport.closeGracefully());
			}
			return transport.closeGracefully();
		});
	}

	/**
	 * Closes the connection and its agent runtime immediately.
	 */
	public void close() {
		if (!closing.compareAndSet(false, true)) {
			return;
		}
		AcpAsyncAgent currentAgent = this.agent;
		if (currentAgent != null) {
			currentAgent.close();
		}
		transport.close();
	}

	private final class ConnectionTransport implements AcpAgentTransport {

		private final Consumer<JSONRPCMessage> outboundConsumer;

		private final Sinks.Many<JSONRPCMessage> inboundSink = Sinks.many().unicast().onBackpressureBuffer();

		/*
		 * Streamable HTTP can deliver multiple POST requests for one ACP connection on
		 * different server threads. Reactor unicast sinks require serialized producers,
		 * so all transport-adapter ingress is funneled through this monitor before
		 * emission.
		 */
		private final Object inboundEmitMonitor = new Object();

		private final Sinks.One<Void> terminationSink = Sinks.one();

		private final AtomicBoolean transportStarted = new AtomicBoolean(false);

		private final AtomicBoolean transportClosing = new AtomicBoolean(false);

		private volatile Consumer<Throwable> exceptionHandler = t -> logger.error("Remote ACP transport error", t);

		ConnectionTransport(Consumer<JSONRPCMessage> outboundConsumer) {
			this.outboundConsumer = outboundConsumer;
		}

		@Override
		public Mono<Void> start(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
			Assert.notNull(handler, "The handler can not be null");
			if (!transportStarted.compareAndSet(false, true)) {
				return Mono.error(new IllegalStateException("Already started"));
			}
			inboundSink.asFlux()
				.flatMap(message -> Mono.just(message).transform(handler))
				.doOnNext(response -> {
					if (response != null) {
						outboundConsumer.accept(response);
					}
				})
				.doOnError(this::signalException)
				.doFinally(signal -> terminationSink.tryEmitValue(null))
				.subscribe();
			return Mono.empty();
		}

		void acceptInbound(JSONRPCMessage message) {
			Assert.notNull(message, "The message can not be null");
			if (transportClosing.get()) {
				throw new AcpConnectionException("Remote ACP connection is closing");
			}
			synchronized (inboundEmitMonitor) {
				Sinks.EmitResult result = inboundSink.tryEmitNext(message);
				if (result.isFailure()) {
					throw new AcpConnectionException("Failed to enqueue inbound message: " + result);
				}
			}
		}

		void signalException(Throwable error) {
			exceptionHandler.accept(error);
		}

		@Override
		public Mono<Void> sendMessage(JSONRPCMessage message) {
			return Mono.fromRunnable(() -> {
				if (transportClosing.get()) {
					throw new AcpConnectionException("Remote ACP connection is closing");
				}
				outboundConsumer.accept(message);
			});
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			return jsonMapper.convertValue(data, typeRef);
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.fromRunnable(this::close);
		}

		@Override
		public void close() {
			if (transportClosing.compareAndSet(false, true)) {
				inboundSink.tryEmitComplete();
				terminationSink.tryEmitValue(null);
			}
		}

		@Override
		public void setExceptionHandler(Consumer<Throwable> handler) {
			Assert.notNull(handler, "The handler can not be null");
			this.exceptionHandler = handler;
		}

		@Override
		public Mono<Void> awaitTermination() {
			return terminationSink.asMono();
		}

	}

}

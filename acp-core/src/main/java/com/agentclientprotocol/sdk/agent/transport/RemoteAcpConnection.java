/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import com.agentclientprotocol.sdk.util.Assert;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * One client connection on a network listener for agents: it owns the agent an
 * {@link AcpAgentFactory} creates for the connection, and the connection-bound
 * {@link AcpAgentTransport} that agent talks through. Use it when you write a network
 * transport of your own: create one per accepted connection, pass each message from the
 * client to {@link #acceptInbound}, and send the client each message the outbound consumer
 * given to the constructor receives. The {@code acp-streamable-http-jetty} module builds its
 * HTTP and WebSocket connections this way; an application that only serves agents does not
 * use this class.
 *
 * <p>It knows nothing about the wire: HTTP headers, SSE streams, WebSocket sessions and
 * routing stay in the adapter. Its life is: construct it, {@link #start} it with the factory,
 * feed it messages and forward what it emits, then {@link #closeGracefully()} or
 * {@link #close()} it when the client goes away or the listener shuts down.
 *
 * <p>{@link #acceptInbound} may be called from several threads at once (Streamable HTTP
 * delivers one connection's POSTs on different server threads); the calls are serialized. The
 * outbound consumer is called from whichever thread the agent sends on, possibly several at
 * once, so it must be thread-safe.
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

	/** Set by {@link #close()}, which may follow a graceful close that has not finished. */
	private final AtomicBoolean closed = new AtomicBoolean(false);

	/**
	 * Orders {@link #start} against a close: either the start sees the close and creates no
	 * agent, or the close sees the agent and closes it. Without it a close that ran between a
	 * listener's shutdown check and the start closed only the transport, and the agent created
	 * after it was never closed. A lock, not a monitor: callers may be virtual threads.
	 */
	private final ReentrantLock lifecycleLock = new ReentrantLock();

	/** The connection's agent runtime; null until {@link #start} creates it. */
	private volatile @Nullable AcpAsyncAgent agent;

	/** Signals when the agent's start has ended, so a graceful close closes a started agent. */
	private final Sinks.Empty<Void> agentStartEnded = Sinks.empty();

	/**
	 * Creates a connection whose transport errors are logged at ERROR.
	 * @param id the connection's id, unique among the listener's connections; the Streamable
	 * HTTP transport sends it to the client in its Acp-Connection-Id header
	 * @param jsonMapper the mapper the agent's transport converts params and results with
	 * @param outboundConsumer receives every message the agent sends to the client, responses
	 * included
	 * @throws IllegalArgumentException if {@code id} is empty or an argument is null
	 */
	public RemoteAcpConnection(String id, AcpJsonMapper jsonMapper, Consumer<JSONRPCMessage> outboundConsumer) {
		this(id, jsonMapper, outboundConsumer, error -> logger.error("Remote ACP transport error", error));
	}

	/**
	 * Creates a connection that reports its transport errors to the listener that owns it.
	 * The agent installs no exception handler on its transport, so without this one the
	 * listener would never see them; the agent factory may still replace it through
	 * {@link AcpAgentTransport#setExceptionHandler}.
	 * @param id the connection's id, unique among the listener's connections; the Streamable
	 * HTTP transport sends it to the client in its Acp-Connection-Id header
	 * @param jsonMapper the mapper the agent's transport converts params and results with
	 * @param outboundConsumer receives every message the agent sends to the client, responses
	 * included
	 * @param exceptionHandler receives the connection's transport errors
	 * @throws IllegalArgumentException if {@code id} is empty or an argument is null
	 */
	public RemoteAcpConnection(String id, AcpJsonMapper jsonMapper, Consumer<JSONRPCMessage> outboundConsumer,
			Consumer<Throwable> exceptionHandler) {
		Assert.hasText(id, "The id can not be empty");
		Assert.notNull(jsonMapper, "The jsonMapper can not be null");
		Assert.notNull(outboundConsumer, "The outboundConsumer can not be null");
		Assert.notNull(exceptionHandler, "The exceptionHandler can not be null");
		this.id = id;
		this.jsonMapper = jsonMapper;
		this.transport = new ConnectionTransport(outboundConsumer, exceptionHandler);
	}

	/**
	 * Returns the connection's id, as given to the constructor.
	 * @return the connection id
	 */
	public String id() {
		return id;
	}

	/**
	 * Creates this connection's agent with {@code agentFactory}, on the connection's
	 * transport, and starts it. The agent is created when the returned Mono is subscribed; a
	 * failure, such as a factory that throws, is also reported to the exception handler.
	 * @param agentFactory creates the agent for this connection
	 * @return a Mono that completes once the agent has started; it errors with an
	 * {@link IllegalStateException} if the connection was started before, and with an
	 * {@link AcpConnectionException}, creating no agent, if the connection is already closing
	 * @throws IllegalArgumentException if {@code agentFactory} is null
	 */
	public Mono<Void> start(AcpAgentFactory agentFactory) {
		Assert.notNull(agentFactory, "The agentFactory can not be null");
		// Guard at subscribe time so a start publisher that is subscribed twice is refused
		// the second time and one that is never subscribed does not consume the start.
		return Mono.defer(() -> {
			if (!started.compareAndSet(false, true)) {
				return Mono.<Void>error(new IllegalStateException("Already started")).doOnError(this::signalException);
			}
			AcpAsyncAgent created;
			lifecycleLock.lock();
			try {
				if (closing.get()) {
					// Closed before its agent existed, as when a listener shuts down while the
					// connection opens: an agent created now would never be closed. Not
					// reported: the close was the owner's own.
					return Mono.error(new AcpConnectionException("Remote ACP connection is closing"));
				}
				created = agentFactory.create(transport);
				this.agent = created;
			}
			catch (RuntimeException e) {
				return Mono.<Void>error(e).doOnError(this::signalException);
			}
			finally {
				lifecycleLock.unlock();
			}
			return created.start()
				.doOnError(this::signalException)
				.doFinally(signal -> agentStartEnded.tryEmitEmpty());
		});
	}

	/**
	 * Hands one message from the client to the connection's agent. It returns once the
	 * message is queued; the agent's answer, if any, reaches the outbound consumer later.
	 * Messages accepted before {@link #start} are kept and delivered once the agent runs.
	 * @param message the message from the client
	 * @throws AcpConnectionException if the connection is closing, or the message cannot be
	 * queued
	 */
	public void acceptInbound(JSONRPCMessage message) {
		transport.acceptInbound(message);
	}

	/**
	 * Reports an error of the wire adapter, such as a failed write or a dropped stream, to the
	 * connection's exception handler: the one given to the constructor, unless the agent
	 * factory replaced it on the transport.
	 * @param error the error to report
	 */
	public void signalException(Throwable error) {
		transport.signalException(error);
	}

	/**
	 * Closes the connection's agent gracefully, then its transport. The agent's in-flight
	 * handlers are cancelled and the outbound consumer receives nothing more. An error from
	 * the agent's close is reported to the exception handler, not returned. Only the first
	 * call has an effect.
	 * @return a Mono that completes when the agent and the transport are closed
	 */
	public Mono<Void> closeGracefully() {
		return Mono.defer(() -> {
			AcpAsyncAgent currentAgent;
			lifecycleLock.lock();
			try {
				if (!closing.compareAndSet(false, true)) {
					return Mono.empty();
				}
				currentAgent = this.agent;
			}
			finally {
				lifecycleLock.unlock();
			}
			if (currentAgent != null) {
				// Closed once its start has ended: an agent closed while it starts, such as the
				// default one, has nothing to close yet and would then start a session that
				// nothing closes.
				return agentStartEnded.asMono()
					.then(Mono.defer(currentAgent::closeGracefully))
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
	 * Closes the connection and its agent runtime immediately, also when a graceful close
	 * has begun but not finished (an agent that does not finish closing gracefully is then
	 * closed at once). Only the first call has an effect.
	 */
	public void close() {
		AcpAsyncAgent currentAgent;
		lifecycleLock.lock();
		try {
			closing.set(true);
			if (!closed.compareAndSet(false, true)) {
				return;
			}
			currentAgent = this.agent;
		}
		finally {
			lifecycleLock.unlock();
		}
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
		 * so all transport-adapter ingress is funneled through this lock before emission.
		 * A lock, not a monitor: emitting runs the agent's handler chain on the caller's
		 * thread, a virtual thread on a listener that serves on them, and a virtual thread
		 * that parks inside a synchronized block pins its carrier on JDK 21 to 23. Enough
		 * pinned carriers waiting on a lock held by an unmounted virtual thread deadlock
		 * the listener.
		 */
		private final ReentrantLock inboundEmitLock = new ReentrantLock();

		private final Sinks.One<Void> terminationSink = Sinks.one();

		private final AtomicBoolean transportStarted = new AtomicBoolean(false);

		private final AtomicBoolean transportClosing = new AtomicBoolean(false);

		/**
		 * The inbound dispatch subscription. Owned so that closing the connection cancels
		 * every in-flight handler: completing the inbound sink alone lets flatMap's active
		 * inner publishers (a running prompt, for instance) run to completion after DELETE.
		 */
		private volatile @Nullable Disposable inboundSubscription;

		private volatile Consumer<Throwable> exceptionHandler;

		ConnectionTransport(Consumer<JSONRPCMessage> outboundConsumer, Consumer<Throwable> exceptionHandler) {
			this.outboundConsumer = outboundConsumer;
			this.exceptionHandler = exceptionHandler;
		}

		@Override
		public Mono<Void> start(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
			Assert.notNull(handler, "The handler can not be null");
			return Mono.defer(() -> {
				if (!transportStarted.compareAndSet(false, true)) {
					return Mono.error(new IllegalStateException("Already started"));
				}
				this.inboundSubscription = inboundSink.asFlux()
					.flatMap(message -> Mono.just(message).transform(handler))
					.doOnNext(response -> {
						outboundConsumer.accept(response);
					})
					.doFinally(signal -> terminationSink.tryEmitValue(null))
					// Reported to the exception handler, not dropped to Reactor's ERROR hook.
					.subscribe(ignored -> {
					}, this::signalException);
				return Mono.empty();
			});
		}

		void acceptInbound(JSONRPCMessage message) {
			Assert.notNull(message, "The message can not be null");
			if (transportClosing.get()) {
				throw new AcpConnectionException("Remote ACP connection is closing");
			}
			inboundEmitLock.lock();
			try {
				Sinks.EmitResult result = inboundSink.tryEmitNext(message);
				if (result.isFailure()) {
					throw new AcpConnectionException("Failed to enqueue inbound message: " + result);
				}
			}
			finally {
				inboundEmitLock.unlock();
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
				Disposable current = this.inboundSubscription;
				if (current != null) {
					// Cancels in-flight handlers (their doFinally releases the prompt lock).
					current.dispose();
				}
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

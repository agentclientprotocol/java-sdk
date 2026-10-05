/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.test;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Creates a bidirectional in-memory transport pair for testing client ↔ agent communication
 * without real processes or network connections.
 *
 * <p>
 * This class provides connected client and agent transports that communicate through
 * in-memory sinks, enabling:
 * </p>
 * <ul>
 * <li>Unit testing of protocol logic without I/O</li>
 * <li>Fast, deterministic tests</li>
 * <li>Testing both client and agent sides in isolation or together</li>
 * </ul>
 *
 * <p>
 * Example usage:
 * </p>
 * <pre>{@code
 * InMemoryTransportPair pair = InMemoryTransportPair.create();
 *
 * // Use client transport in client code
 * AcpClientTransport clientTransport = pair.clientTransport();
 *
 * // Use agent transport in agent code
 * AcpAgentTransport agentTransport = pair.agentTransport();
 *
 * // Messages sent by client arrive at agent, and vice versa
 * }</pre>
 *
 * @author Mark Pollack
 */
public class InMemoryTransportPair {

	private final InMemoryClientTransport clientTransport;

	private final InMemoryAgentTransport agentTransport;

	private InMemoryTransportPair() {
		Channel clientToAgent = new Channel();
		Channel agentToClient = new Channel();

		this.clientTransport = new InMemoryClientTransport(clientToAgent, agentToClient);
		this.agentTransport = new InMemoryAgentTransport(agentToClient, clientToAgent);
	}

	/**
	 * Creates a new transport pair with connected client and agent transports.
	 * @return a new InMemoryTransportPair
	 */
	public static InMemoryTransportPair create() {
		return new InMemoryTransportPair();
	}

	/**
	 * Gets the client-side transport.
	 * @return the client transport
	 */
	public AcpClientTransport clientTransport() {
		return clientTransport;
	}

	/**
	 * Gets the agent-side transport.
	 * @return the agent transport
	 */
	public AcpAgentTransport agentTransport() {
		return agentTransport;
	}

	/**
	 * Closes both transports gracefully.
	 * @return a Mono that completes when both transports are closed
	 */
	public Mono<Void> closeGracefully() {
		return Mono.when(clientTransport.closeGracefully(), agentTransport.closeGracefully());
	}


	/**
	 * One direction of the pair, which several threads send on. A {@code Sinks.many()} sink
	 * refuses a message while another thread is emitting on it ({@code FAIL_NON_SERIALIZED}),
	 * and that thread can hold it for as long as the receiver takes, since delivery runs on
	 * the emitting thread. So a send is queued, and whichever thread finds the sink free emits
	 * the queue in order: no message is dropped and no sender waits, including a receiver that
	 * sends from inside a delivery.
	 */
	private static final class Channel {

		private final Sinks.Many<AcpSchema.JSONRPCMessage> sink = Sinks.many().unicast().onBackpressureBuffer();

		private final Queue<AcpSchema.JSONRPCMessage> pending = new ConcurrentLinkedQueue<>();

		private final AtomicInteger emitters = new AtomicInteger();

		Flux<AcpSchema.JSONRPCMessage> asFlux() {
			return sink.asFlux();
		}

		void complete() {
			sink.tryEmitComplete();
		}

		Mono<Void> send(AcpSchema.JSONRPCMessage message, String what) {
			return Mono.fromRunnable(() -> {
				pending.offer(message);
				if (emitters.getAndIncrement() != 0) {
					return; // the thread emitting now emits this message too
				}
				// A failure means the channel is closed, so it is reported to this sender.
				Sinks.EmitResult sent = Sinks.EmitResult.OK;
				int missed = 1;
				do {
					AcpSchema.JSONRPCMessage next;
					while ((next = pending.poll()) != null) {
						Sinks.EmitResult result = sink.tryEmitNext(next);
						if (result.isFailure()) {
							sent = result;
						}
					}
					missed = emitters.addAndGet(-missed);
				}
				while (missed != 0);
				if (sent.isFailure()) {
					throw new RuntimeException("Failed to send " + what + ": " + sent,
							new Sinks.EmissionException(sent));
				}
			});
		}

	}

	/**
	 * In-memory client transport implementation.
	 */
	private static class InMemoryClientTransport implements AcpClientTransport {

		private final Channel outbound;

		private final Channel inbound;

		private volatile boolean connected = false;

		private Consumer<Throwable> exceptionHandler = t -> {
		};

		InMemoryClientTransport(Channel outbound, Channel inbound) {
			this.outbound = outbound;
			this.inbound = inbound;
		}

		@Override
		public List<Integer> protocolVersions() {
			return List.of(AcpSchema.LATEST_PROTOCOL_VERSION);
		}

		@Override
		public Mono<Void> connect(Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> handler) {
			if (connected) {
				return Mono.error(new IllegalStateException("Already connected"));
			}
			connected = true;
			return inbound.asFlux()
				.flatMap(message -> Mono.just(message).transform(handler))
				.doOnError(exceptionHandler::accept)
				.doFinally(signal -> connected = false)
				.then();
		}

		@Override
		public Mono<Void> sendMessage(AcpSchema.JSONRPCMessage message) {
			return outbound.send(message, "message");
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.defer(() -> {
				connected = false;
				outbound.complete();
				return Mono.empty();
			});
		}

		@Override
		public void setExceptionHandler(Consumer<Throwable> handler) {
			this.exceptionHandler = handler;
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			return AcpJsonMapper.createDefault().convertValue(data, typeRef);
		}

	}

	/**
	 * In-memory agent transport implementation.
	 */
	private static class InMemoryAgentTransport implements AcpAgentTransport {

		private final Channel outbound;

		private final Channel inbound;

		private final Sinks.One<Void> terminationSink = Sinks.one();

		private volatile boolean started = false;

		private Consumer<Throwable> exceptionHandler = t -> {
		};

		InMemoryAgentTransport(Channel outbound, Channel inbound) {
			this.outbound = outbound;
			this.inbound = inbound;
		}

		@Override
		public List<Integer> protocolVersions() {
			return List.of(AcpSchema.LATEST_PROTOCOL_VERSION);
		}

		@Override
		public Mono<Void> start(Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> handler) {
			if (started) {
				return Mono.error(new IllegalStateException("Already started"));
			}
			started = true;
			return inbound.asFlux()
				.flatMap(message -> Mono.just(message)
					.transform(handler)
					.flatMap(response -> outbound.send(response, "response"))
					// A failed emission is this message's problem, not the transport's: it
					// must not terminate the inbound subscription and kill the agent.
					.onErrorResume(error -> {
						exceptionHandler.accept(error);
						return Mono.empty();
					}))
				.doOnError(exceptionHandler::accept)
				.doFinally(signal -> started = false)
				.then();
		}

		@Override
		public Mono<Void> sendMessage(AcpSchema.JSONRPCMessage message) {
			return outbound.send(message, "message");
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.defer(() -> {
				started = false;
				outbound.complete();
				terminationSink.tryEmitValue(null);
				return Mono.empty();
			});
		}

		@Override
		public Mono<Void> awaitTermination() {
			return terminationSink.asMono();
		}

		@Override
		public void setExceptionHandler(Consumer<Throwable> handler) {
			this.exceptionHandler = handler;
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			return AcpJsonMapper.createDefault().convertValue(data, typeRef);
		}

	}

}

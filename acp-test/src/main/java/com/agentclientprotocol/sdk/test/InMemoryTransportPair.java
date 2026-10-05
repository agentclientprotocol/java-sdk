/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.test;

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
 * Two connected transports, {@link #clientTransport()} and {@link #agentTransport()}, that link an
 * ACP client and an agent inside one JVM for tests: build a client on one and an agent on the
 * other, and they talk without a process or a network. Use it with {@link MockAcpAgent} or
 * {@link MockAcpClient}, or with a real client and agent from
 * {@link com.agentclientprotocol.sdk.client.AcpClient} and
 * {@link com.agentclientprotocol.sdk.agent.AcpAgent}. Messages pass between the two as Java objects
 * and are never written as JSON, so a test of what goes over the wire needs a real transport
 * instead.
 *
 * <p>Each side receives what the other sends, in order. A message sent before the other side has
 * started waits until it starts, so the client and the agent can be built and started in either
 * order. Both transports accept sends from several threads at once. Each carries one connection: a
 * second connect or start fails with {@link IllegalStateException}, so use a new pair for every
 * client and agent. Params and results are converted to the records the SDK expects with a mapper
 * from {@link AcpJsonMapper#createDefault()}, created for each conversion, so a JSON module must be
 * on the classpath; {@code acp-test} brings in {@code acp-json-jackson2}.
 *
 * <p>Closing one side ends the other side's input, but neither transport reports that as its end:
 * the agent transport's {@code awaitTermination()} completes only once the agent side is closed (so
 * {@code AcpSyncAgent.run()} keeps running after the client closes), and the client transport never
 * reports termination, so a request the agent leaves unanswered when its transport closes fails
 * only at the client's timeout. Close both sides, or call {@link #closeGracefully()}, at the end of
 * a test.
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
 * AcpSchema.PromptResponse response = client.prompt(new AcpSchema.PromptRequest(sessionId,
 *     List.of(new AcpSchema.TextContent("Hi"))));
 *
 * client.close();
 * agent.close();
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
	 * Creates a pair of connected transports, neither of them connected or started yet.
	 * @return a new pair
	 */
	public static InMemoryTransportPair create() {
		return new InMemoryTransportPair();
	}

	/**
	 * Returns the client side of the pair. Pass it to {@code AcpClient.sync(...)},
	 * {@code AcpClient.async(...)} or {@link MockAcpClient#builder}; it can be connected once.
	 * @return the client transport, the same instance on every call
	 */
	public AcpClientTransport clientTransport() {
		return clientTransport;
	}

	/**
	 * Returns the agent side of the pair. Pass it to {@code AcpAgent.sync(...)},
	 * {@code AcpAgent.async(...)} or {@link MockAcpAgent#builder}; it can be started once.
	 * @return the agent transport, the same instance on every call
	 */
	public AcpAgentTransport agentTransport() {
		return agentTransport;
	}

	/**
	 * Closes both transports: each stops sending to the other, and the agent transport's
	 * {@code awaitTermination()} completes. Closing the client and the agent built on the pair
	 * closes their transports as well; this method is a final clean-up for a test, and can be
	 * called more than once.
	 * @return a {@code Mono} that completes when both transports are closed
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
		public Mono<Void> connect(Function<Mono<AcpSchema.JSONRPCMessage>, Mono<AcpSchema.JSONRPCMessage>> handler) {
			if (connected) {
				return Mono.error(new IllegalStateException("Already connected"));
			}
			connected = true;
			return inbound.asFlux()
				.flatMap(message -> Mono.just(message).transform(handler))
				.doOnError(error -> exceptionHandler.accept(error))
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
				.doOnError(error -> exceptionHandler.accept(error))
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

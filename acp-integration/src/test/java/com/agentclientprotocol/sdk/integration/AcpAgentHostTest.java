/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.integration.AcpAgentDiscovery.AgentCandidate;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class AcpAgentHostTest {

	private final InMemoryTransportPair pair = InMemoryTransportPair.create();

	@Test
	void servesUntilStoppedAndStopsOnce() throws Exception {
		AtomicInteger transportEnds = new AtomicInteger();
		AcpAgentHost host = new AcpAgentHost(agent(pair.agentTransport()), pair.agentTransport(),
				transportEnds::incrementAndGet);
		assertThat(host.port()).isEmpty();
		host.start();
		host.start(); // once
		assertThat(TestAgents.roundTrip(pair.clientTransport())).containsExactly("echo: hello!");

		CompletionStage<Void> stopped = host.stopGracefully();
		assertThat(host.stopGracefully()).isSameAs(stopped);
		stopped.toCompletableFuture().get(10, TimeUnit.SECONDS);
		host.termination().toCompletableFuture().get(10, TimeUnit.SECONDS);
		host.stop(Duration.ofSeconds(1)); // already stopped: returns at once
		host.start(); // nor does a start after the stop
		Thread.sleep(200);
		// The host stopped the agent itself: that is no transport end.
		assertThat(transportEnds).hasValue(0);
	}

	@Test
	void theTransportEndingRunsTheActionOnceOnTheHostsThread() throws Exception {
		CountDownLatch ran = new CountDownLatch(1);
		List<String> threads = new java.util.concurrent.CopyOnWriteArrayList<>();
		AcpAgentHost host = new AcpAgentHost(agent(pair.agentTransport()), pair.agentTransport(), () -> {
			threads.add(Thread.currentThread().getName());
			ran.countDown();
		});
		host.start();
		pair.agentTransport().closeGracefully().block(Duration.ofSeconds(5));
		assertThat(ran.await(10, TimeUnit.SECONDS)).isTrue();
		host.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
		host.stop(Duration.ofSeconds(5));
		Thread.sleep(100);
		assertThat(threads).containsExactly(AcpAgentHost.TRANSPORT_END_THREAD_NAME);
	}

	@Test
	void aTransportThatEndedBeforeTheHostSawItStillCounts() throws Exception {
		pair.agentTransport().closeGracefully().block(Duration.ofSeconds(5));
		CountDownLatch ran = new CountDownLatch(1);
		AcpAgentHost host = new AcpAgentHost(agent(pair.agentTransport()), pair.agentTransport(), ran::countDown);
		assertThat(ran.await(10, TimeUnit.SECONDS)).isTrue();
		host.stop(Duration.ofSeconds(5));
	}

	@Test
	void aTransportFailingCountsAsItsEnd() throws Exception {
		CountDownLatch ran = new CountDownLatch(1);
		AcpAgentTransport failing = new TestAgents.StuckAgentTransport(pair.agentTransport()) {

			@Override
			public Mono<Void> awaitTermination() {
				return Mono.error(new IllegalStateException("broken pipe"));
			}

		};
		AcpAgentHost host = new AcpAgentHost(agent(failing), failing, ran::countDown);
		assertThat(ran.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(host.termination().toCompletableFuture().get(5, TimeUnit.SECONDS)).isNull();
	}

	@Test
	void aStopThatDoesNotFinishInTimeClosesAtOnce() {
		TestAgents.StuckAgentTransport stuck = new TestAgents.StuckAgentTransport(pair.agentTransport());
		AcpAgentHost host = new AcpAgentHost(agent(stuck, Duration.ofSeconds(2)), stuck, () -> {
		});
		host.start();
		host.stop(Duration.ofMillis(50));
		assertThat(stuck.closes).hasPositiveValue();
	}

	@Test
	void anInterruptedStopClosesAtOnceAndKeepsTheInterrupt() {
		TestAgents.StuckAgentTransport stuck = new TestAgents.StuckAgentTransport(pair.agentTransport());
		AcpAgentHost host = new AcpAgentHost(agent(stuck, Duration.ofSeconds(2)), stuck, () -> {
		});
		host.start();
		Thread.currentThread().interrupt();
		host.stop(Duration.ofSeconds(5));
		assertThat(Thread.interrupted()).isTrue();
		assertThat(stuck.closes).hasPositiveValue();
	}

	@Test
	void holdsTheJvmUntilTermination() throws Exception {
		AcpAgentHost host = new AcpAgentHost(agent(pair.agentTransport()), pair.agentTransport(), () -> {
		});
		host.start();
		host.holdJvmUntilTermination();
		assertThat(holdThread()).isTrue();
		host.stop(Duration.ofSeconds(5));
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (holdThread() && System.nanoTime() < deadline) {
			Thread.sleep(20);
		}
		assertThat(holdThread()).isFalse();
	}

	@Test
	void theHoldThreadEndsAlsoWhenTerminationFails() throws Exception {
		CompletableFuture<Void> failed = new CompletableFuture<>();
		AcpHost host = new FailedTermination(failed);
		host.holdJvmUntilTermination();
		failed.completeExceptionally(new IllegalStateException("gone"));
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (holdThread() && System.nanoTime() < deadline) {
			Thread.sleep(20);
		}
		assertThat(holdThread()).isFalse();
	}

	static boolean holdThread() {
		return Thread.getAllStackTraces()
			.keySet()
			.stream()
			.anyMatch(t -> t.isAlive() && t.getName().equals(AcpHost.HOLD_THREAD_NAME));
	}

	private static AcpAgentSupport agent(AcpAgentTransport transport) {
		return agent(transport, null);
	}

	private static AcpAgentSupport agent(AcpAgentTransport transport, Duration requestTimeout) {
		return AcpAgents
			.builder(new AgentCandidate<>("echo", TestAgents.EchoAgent.class, TestAgents.EchoAgent::new),
					AcpAgentSettings.builder().requestTimeout(requestTimeout).build(), List.of(),
					List.of(new TestAgents.SuffixResolver()), List.of(new TestAgents.ReplyHandler()))
			.transport(transport)
			.build();
	}

	/** Only a termination, for the default method. */
	private record FailedTermination(CompletableFuture<Void> termination) implements AcpHost {

		@Override
		public void start() {
		}

		@Override
		public CompletionStage<Void> stopGracefully() {
			return termination;
		}

		@Override
		public void stop(Duration timeout) {
		}

		@Override
		public java.util.OptionalInt port() {
			return java.util.OptionalInt.empty();
		}

	}

}

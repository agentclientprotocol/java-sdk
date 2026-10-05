/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.server;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.scheduler.VirtualTimeScheduler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The WebSocket connection's idle timeout and initialize deadline, timed on a scheduler the
 * test owns: on virtual time for when they fire, and on a real timer queue for what stays
 * scheduled, so a closed connection is shown to leave no task behind.
 */
class WebSocketConnectionTimeoutTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final String INITIALIZE = """
			{"jsonrpc":"2.0","id":"init","method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{}}}""";

	private static final AcpAgentFactory AGENTS = AcpAgentFactory.async(transport -> AcpAgent.async(transport)
		.initializeHandler(request -> Mono.just(AcpSchema.InitializeResponse.ok()))
		.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse("s-1", null, null)))
		.promptHandler((request, context) -> Mono.just(AcpSchema.PromptResponse.endTurn()))
		.build());

	private final ScheduledThreadPoolExecutor timerQueue = new ScheduledThreadPoolExecutor(1);

	private @Nullable WebSocketConnection connection;

	WebSocketConnectionTimeoutTest() {
		timerQueue.setRemoveOnCancelPolicy(true);
	}

	@AfterEach
	void close() {
		WebSocketConnection current = connection;
		if (current != null) {
			current.closeNow();
		}
		timerQueue.shutdownNow();
	}

	@Test
	void closingLeavesNoTimerScheduled() {
		FakeSocket socket = new FakeSocket();
		open(socket, Duration.ofHours(1), Duration.ofHours(1), Schedulers.fromExecutorService(timerQueue),
				System::nanoTime);
		// The initialize deadline and the idle check.
		awaitTrue(() -> timerQueue.getQueue().size() == 2);

		connection().onClose(1000, "bye");

		awaitTrue(() -> timerQueue.getQueue().isEmpty());
		assertThat(socket.closeCode.get()).isNull();
	}

	@Test
	void closingByTheEndpointLeavesNoTimerScheduled() {
		FakeSocket socket = new FakeSocket();
		open(socket, Duration.ofHours(1), Duration.ofHours(1), Schedulers.fromExecutorService(timerQueue),
				System::nanoTime);
		awaitTrue(() -> timerQueue.getQueue().size() == 2);

		connection().close(WebSocketConnection.NORMAL, "done");

		awaitTrue(() -> timerQueue.getQueue().isEmpty());
		assertThat(socket.closeCode.get()).isEqualTo(1000);
	}

	@Test
	void initializeCancelsTheDeadline() {
		FakeSocket socket = new FakeSocket();
		open(socket, Duration.ofHours(1), Duration.ofHours(1), Schedulers.fromExecutorService(timerQueue),
				System::nanoTime);
		awaitTrue(() -> timerQueue.getQueue().size() == 2);

		connection().onText(INITIALIZE);
		awaitTrue(() -> socket.sent.size() == 1);

		// Only the idle check is left.
		awaitTrue(() -> timerQueue.getQueue().size() == 1);
	}

	@Test
	void aConnectionWithoutInitializeIsClosedWith1008AtTheDeadline() {
		VirtualTimeScheduler time = VirtualTimeScheduler.create();
		FakeSocket socket = new FakeSocket();
		open(socket, Duration.ofSeconds(30), Duration.ofMinutes(30), time, nanos(time));

		time.advanceTimeBy(Duration.ofSeconds(29));
		assertThat(socket.closeCode.get()).isNull();

		time.advanceTimeBy(Duration.ofSeconds(1));
		assertThat(socket.closeCode.get()).isEqualTo(1008);
		assertThat(socket.closeReason.get()).isEqualTo("initialize not received");
	}

	@Test
	void anInitializedConnectionIsNotClosedAtTheDeadline() {
		VirtualTimeScheduler time = VirtualTimeScheduler.create();
		FakeSocket socket = new FakeSocket();
		open(socket, Duration.ofSeconds(30), Duration.ofMinutes(30), time, nanos(time));
		connection().onText(INITIALIZE);
		awaitTrue(() -> socket.sent.size() == 1);

		time.advanceTimeBy(Duration.ofMinutes(5));

		assertThat(socket.closeCode.get()).isNull();
	}

	@Test
	void anIdleConnectionIsClosedWith1001() {
		VirtualTimeScheduler time = VirtualTimeScheduler.create();
		FakeSocket socket = new FakeSocket();
		open(socket, Duration.ofSeconds(30), Duration.ofSeconds(10), time, nanos(time));
		connection().onText(INITIALIZE);
		awaitTrue(() -> socket.sent.size() == 1);

		time.advanceTimeBy(Duration.ofSeconds(9));
		assertThat(socket.closeCode.get()).isNull();

		time.advanceTimeBy(Duration.ofSeconds(1));
		assertThat(socket.closeCode.get()).isEqualTo(1001);
		assertThat(socket.closeReason.get()).isEqualTo("idle timeout");
	}

	@Test
	void anInboundFrameResetsTheIdleTimer() {
		VirtualTimeScheduler time = VirtualTimeScheduler.create();
		FakeSocket socket = new FakeSocket();
		open(socket, Duration.ofSeconds(30), Duration.ofSeconds(10), time, nanos(time));
		connection().onText(INITIALIZE);
		awaitTrue(() -> socket.sent.size() == 1);

		time.advanceTimeBy(Duration.ofSeconds(6));
		// A frame at 6 s: answered -32600, and both the frame and the answer are activity.
		connection().onText(INITIALIZE);
		awaitTrue(() -> socket.sent.size() == 2);

		// The check at 10 s finds 4 s of idleness and checks again at 16 s.
		time.advanceTimeBy(Duration.ofSeconds(9));
		assertThat(socket.closeCode.get()).isNull();

		time.advanceTimeBy(Duration.ofSeconds(1));
		assertThat(socket.closeCode.get()).isEqualTo(1001);
	}

	@Test
	void anOutboundFrameResetsTheIdleTimer() {
		VirtualTimeScheduler time = VirtualTimeScheduler.create();
		FakeSocket socket = new FakeSocket();
		open(socket, Duration.ofSeconds(30), Duration.ofSeconds(10), time, nanos(time));
		connection().onText(INITIALIZE);
		awaitTrue(() -> socket.sent.size() == 1);

		time.advanceTimeBy(Duration.ofSeconds(6));
		// The agent sends at 6 s, unprompted.
		connection().sendToClient(new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION, "_test/ping", null));
		awaitTrue(() -> socket.sent.size() == 2);

		time.advanceTimeBy(Duration.ofSeconds(9));
		assertThat(socket.closeCode.get()).isNull();

		time.advanceTimeBy(Duration.ofSeconds(1));
		assertThat(socket.closeCode.get()).isEqualTo(1001);
	}

	@Test
	void theTimeoutsAreTheOptionsAndDefaultToThirtyMinutesAndThirtySeconds() {
		StreamableHttpAcpAgentTransportOptions defaults = StreamableHttpAcpAgentTransportOptions.defaults();
		assertThat(defaults.webSocketIdleTimeout()).isEqualTo(Duration.ofMinutes(30));
		assertThat(defaults.initializeTimeout()).isEqualTo(Duration.ofSeconds(30));
		AcpHttpEndpoint endpoint = AcpHttpEndpoint.create(AcpJsonMapper.createDefault(), AGENTS,
				StreamableHttpAcpAgentTransportOptions.builder().webSocketIdleTimeout(Duration.ofSeconds(7)).build());
		AcpWsHandshake.Accepted accepted = (AcpWsHandshake.Accepted) endpoint
			.webSocketHandshake(new HeaderExchange());
		// The container's own idle timeout backs the endpoint's up, past it.
		assertThat(accepted.idleTimeout()).isEqualTo(Duration.ofSeconds(12));
	}

	private void open(FakeSocket socket, Duration initializeTimeout, Duration idleTimeout, Scheduler timer,
			LongSupplier clock) {
		StreamableHttpAcpAgentTransportOptions options = StreamableHttpAcpAgentTransportOptions.builder()
			.initializeTimeout(initializeTimeout)
			.webSocketIdleTimeout(idleTimeout)
			.build();
		WebSocketConnection opened = new WebSocketConnection("ws-1", AcpJsonMapper.createDefault(), options, socket,
				new WebSocketConnection.Owner(closed -> {
				}, error -> {
				}), new WebSocketConnection.Timing(timer, clock));
		this.connection = opened;
		opened.start(AGENTS);
	}

	private WebSocketConnection connection() {
		WebSocketConnection current = connection;
		assertThat(current).isNotNull();
		return current;
	}

	private static LongSupplier nanos(VirtualTimeScheduler time) {
		return () -> time.now(TimeUnit.NANOSECONDS);
	}

	private static void awaitTrue(BooleanSupplier condition) {
		long deadline = System.nanoTime() + TIMEOUT.toNanos();
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("Condition not met within " + TIMEOUT);
			}
			LockSupport.parkNanos(10_000_000);
		}
	}

	private static final class FakeSocket implements AcpWsOutbound {

		final List<String> sent = new CopyOnWriteArrayList<>();

		final AtomicReference<@Nullable Integer> closeCode = new AtomicReference<>();

		final AtomicReference<@Nullable String> closeReason = new AtomicReference<>();

		@Override
		public CompletionStage<Void> sendText(String text) {
			sent.add(text);
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public CompletionStage<Void> close(int code, String reason) {
			if (closeCode.compareAndSet(null, code)) {
				closeReason.set(reason);
			}
			return CompletableFuture.completedFuture(null);
		}

	}

	/** A WebSocket upgrade request without headers. */
	private static final class HeaderExchange implements AcpHttpExchange {

		@Override
		public String method() {
			return "GET";
		}

		@Override
		public @Nullable String header(String name) {
			return "Upgrade".equalsIgnoreCase(name) ? "websocket" : null;
		}

		@Override
		public Mono<byte[]> body(long maxBytes) {
			return Mono.empty();
		}

		@Override
		public java.security.@Nullable Principal principal() {
			return null;
		}

	}

}

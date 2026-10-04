/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransportOptions;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.util.VirtualThreads;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Given an executor of the application's, the client transports and the listener run on its
 * threads and create no pools of their own; they leave that executor running when they close.
 */
class TransportThreadsTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final AtomicInteger appTasks = new AtomicInteger();

	private final ExecutorService appPool = Executors.newCachedThreadPool(r -> {
		Thread thread = new Thread(r, "app-worker");
		thread.setDaemon(true);
		return thread;
	});

	private final ExecutorService app = new java.util.concurrent.AbstractExecutorService() {

		@Override
		public void execute(Runnable command) {
			appTasks.incrementAndGet();
			appPool.execute(command);
		}

		@Override
		public void shutdown() {
			appPool.shutdown();
		}

		@Override
		public List<Runnable> shutdownNow() {
			return appPool.shutdownNow();
		}

		@Override
		public boolean isShutdown() {
			return appPool.isShutdown();
		}

		@Override
		public boolean isTerminated() {
			return appPool.isTerminated();
		}

		@Override
		public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
			return appPool.awaitTermination(timeout, unit);
		}

	};

	@AfterEach
	void stopAppPool() {
		appPool.shutdownNow();
	}

	@Test
	void theHttpClientTransportRunsOnTheApplicationsExecutorAndLeavesItRunning() throws Exception {
		StreamableHttpAcpAgentTransport server = startServer(StreamableHttpAcpAgentTransportOptions.defaults());
		try {
			Set<Thread> before = Thread.getAllStackTraces().keySet();
			var transport = new StreamableHttpAcpClientTransport(endpoint(server, "http"),
					AcpJsonMapper.createDefault(), StreamableHttpAcpClientTransportOptions.builder().executor(app).build());
			roundTrip(transport);
			// The JDK's HttpClient always runs one selector thread of its own; only an
			// HttpClient passed to the transport avoids it.
			assertThat(newThreads(before, "acp-", "HttpClient-"))
				.allMatch(name -> name.matches("HttpClient-\\d+-SelectorManager"))
				.hasSizeLessThanOrEqualTo(1);
			assertThat(appTasks).hasPositiveValue();
			assertThat(app.isShutdown()).isFalse();
		}
		finally {
			server.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void theWebSocketClientTransportRunsOnTheApplicationsExecutorAndLeavesItRunning() throws Exception {
		StreamableHttpAcpAgentTransport server = startServer(StreamableHttpAcpAgentTransportOptions.defaults());
		try {
			Set<Thread> before = Thread.getAllStackTraces().keySet();
			var transport = new WebSocketAcpClientTransport(endpoint(server, "ws"), AcpJsonMapper.createDefault(), app);
			roundTrip(transport);
			assertThat(newThreads(before, "acp-", "HttpClient-"))
				.allMatch(name -> name.matches("HttpClient-\\d+-SelectorManager"))
				.hasSizeLessThanOrEqualTo(1);
			assertThat(appTasks).hasPositiveValue();
			assertThat(app.isShutdown()).isFalse();
		}
		finally {
			server.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void theListenerRunsOnTheApplicationsExecutorAndLeavesItRunning() throws Exception {
		assumeTrue(VirtualThreads.isSupported(), "Jetty's VirtualThreadPool needs JDK 21");
		Set<Thread> before = Thread.getAllStackTraces().keySet();
		StreamableHttpAcpAgentTransport server = startServer(
				StreamableHttpAcpAgentTransportOptions.builder().executor(app).build());
		Set<String> serverThreads;
		try {
			serverThreads = newThreads(before, "acp-", "qtp", "Scheduler-", "jetty-");
			// The client's own threads are not the listener's.
			roundTrip(new StreamableHttpAcpClientTransport(endpoint(server, "http"), AcpJsonMapper.createDefault(),
					StreamableHttpAcpClientTransportOptions.builder().executor(app).build()));
		}
		finally {
			server.closeGracefully().block(TIMEOUT);
		}
		// Jetty's VirtualThreadPool parks one platform thread for as long as the server runs,
		// as Jetty's own pool would keep the JVM alive.
		assertThat(serverThreads).containsExactly("jetty-virtual-thread-pool-keepalive");
		assertThat(appTasks).hasPositiveValue();
		assertThat(app.isShutdown()).isFalse();
	}

	@Test
	void theListenerSchedulesItsKeepAlivesOnTheSharedTimer() throws Exception {
		Set<Thread> before = Thread.getAllStackTraces().keySet();
		StreamableHttpAcpAgentTransport server = startServer(
				StreamableHttpAcpAgentTransportOptions.builder().keepAliveInterval(Duration.ofSeconds(1)).build());
		try {
			// The servlet starts its keep-alive when the first request puts it into service.
			roundTrip(new StreamableHttpAcpClientTransport(endpoint(server, "http"), AcpJsonMapper.createDefault()));
			assertThat(newThreads(before, "acp-streamable-http-keepalive")).isEmpty();
		}
		finally {
			server.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void onJdk21TheDefaultClientTransportsStartNoPlatformThreadsOfTheirOwn() throws Exception {
		assumeTrue(VirtualThreads.isSupported(), "virtual threads need JDK 21");
		StreamableHttpAcpAgentTransport server = startServer(StreamableHttpAcpAgentTransportOptions.defaults());
		try {
			Set<Thread> before = Thread.getAllStackTraces().keySet();
			roundTrip(new StreamableHttpAcpClientTransport(endpoint(server, "http"), AcpJsonMapper.createDefault()));
			roundTrip(new WebSocketAcpClientTransport(endpoint(server, "ws"), AcpJsonMapper.createDefault()));
			// One JDK HttpClient selector thread per transport, gone once its client is
			// unreachable; nothing of the SDK's.
			assertThat(newThreads(before, "acp-", "HttpClient-"))
				.allMatch(name -> name.matches("HttpClient-\\d+-SelectorManager"))
				.hasSizeLessThanOrEqualTo(2);
		}
		finally {
			server.closeGracefully().block(TIMEOUT);
		}
	}

	@Test
	void onJdk21TheDefaultListenerServesOnVirtualThreads() throws Exception {
		assumeTrue(VirtualThreads.isSupported(), "virtual threads need JDK 21");
		Set<Thread> before = Thread.getAllStackTraces().keySet();
		StreamableHttpAcpAgentTransport server = startServer(StreamableHttpAcpAgentTransportOptions.defaults());
		Set<String> serverThreads;
		try {
			serverThreads = newThreads(before, "acp-", "qtp", "Scheduler-", "jetty-");
			roundTrip(new StreamableHttpAcpClientTransport(endpoint(server, "http"), AcpJsonMapper.createDefault()));
		}
		finally {
			server.closeGracefully().block(TIMEOUT);
		}
		assertThat(serverThreads).containsExactly("jetty-virtual-thread-pool-keepalive");
	}

	@Test
	void withVirtualThreadsOffTheListenerAndTheHttpClientKeepTheirPlatformPools() throws Exception {
		Set<Thread> before = Thread.getAllStackTraces().keySet();
		StreamableHttpAcpAgentTransport server = startServer(
				StreamableHttpAcpAgentTransportOptions.builder().virtualThreads(false).build());
		Set<String> threads = new java.util.HashSet<>();
		try {
			// Counted before the client closes: closing shuts its pools down.
			roundTrip(new StreamableHttpAcpClientTransport(endpoint(server, "http"), AcpJsonMapper.createDefault(),
					StreamableHttpAcpClientTransportOptions.builder().virtualThreads(false).build()),
					() -> threads.addAll(newThreads(before, "acp-", "qtp")));
		}
		finally {
			server.closeGracefully().block(TIMEOUT);
		}
		assertThat(threads).anyMatch(name -> name.startsWith("qtp"))
			.contains("acp-streamable-http-client", "acp-streamable-http-signal", "acp-streamable-http-sse");
	}

	@Test
	void anExecutorForTheListenerNeedsVirtualThreads() {
		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> StreamableHttpAcpAgentTransportOptions.builder()
				.executor(app)
				.virtualThreads(false)
				.build())
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void anExecutorForTheListenerNeedsJdk21() {
		assumeTrue(!VirtualThreads.isSupported(), "only before JDK 21");
		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> new StreamableHttpAcpAgentTransport(0, "/acp", AcpJsonMapper.createDefault(),
					agentFactory(), StreamableHttpAcpAgentTransportOptions.builder().executor(app).build()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("JDK 21");
	}

	private static void roundTrip(AcpClientTransport transport) {
		roundTrip(transport, () -> {
		});
	}

	private static void roundTrip(AcpClientTransport transport, Runnable beforeClose) {
		AcpAsyncClient client = AcpClient.async(transport).requestTimeout(TIMEOUT).build();
		try {
			client.initialize().block(TIMEOUT);
			AcpSchema.NewSessionResponse session = client
				.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of()))
				.block(TIMEOUT);
			AcpSchema.PromptResponse prompt = client
				.prompt(new AcpSchema.PromptRequest(session.sessionId(), List.of(new AcpSchema.TextContent("hi"))))
				.block(TIMEOUT);
			assertThat(prompt.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
			beforeClose.run();
		}
		finally {
			client.closeGracefully().block(TIMEOUT);
		}
	}

	/**
	 * Names of the live platform threads started since {@code before} whose name starts so,
	 * leaving out the SDK's one JVM-wide timer ({@code acp-timeout}), which the first timeout
	 * in the JVM starts whichever transport sets it.
	 */
	private static Set<String> newThreads(Set<Thread> before, String... prefixes) {
		return Thread.getAllStackTraces()
			.keySet()
			.stream()
			.filter(thread -> !before.contains(thread))
			.map(Thread::getName)
			.filter(name -> !name.equals("acp-timeout"))
			.filter(name -> java.util.Arrays.stream(prefixes).anyMatch(name::startsWith))
			.collect(Collectors.toSet());
	}

	private static AcpAgentFactory agentFactory() {
		return AcpAgentFactory.async(transport -> AcpAgent.async(transport)
			.initializeHandler(request -> Mono.just(AcpSchema.InitializeResponse.ok()))
			.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse("sess-1", null, null)))
			.promptHandler((request, context) -> context.sendMessage("hello")
				.thenReturn(AcpSchema.PromptResponse.endTurn()))
			.build());
	}

	private static StreamableHttpAcpAgentTransport startServer(StreamableHttpAcpAgentTransportOptions options)
			throws Exception {
		int port;
		try (ServerSocket socket = new ServerSocket(0)) {
			port = socket.getLocalPort();
		}
		StreamableHttpAcpAgentTransport server = new StreamableHttpAcpAgentTransport(port,
				StreamableHttpAcpAgentTransport.DEFAULT_ACP_PATH, AcpJsonMapper.createDefault(), agentFactory(), options);
		server.start().block(TIMEOUT);
		return server;
	}

	private static URI endpoint(StreamableHttpAcpAgentTransport server, String scheme) {
		return URI.create(scheme + "://127.0.0.1:" + server.getPort() + "/acp");
	}

}

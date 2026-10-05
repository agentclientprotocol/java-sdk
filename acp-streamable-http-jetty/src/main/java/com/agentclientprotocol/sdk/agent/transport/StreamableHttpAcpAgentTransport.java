/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.util.Assert;
import com.agentclientprotocol.sdk.util.VirtualThreads;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.VirtualThreadPool;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * A network server for ACP agents: runs an embedded Jetty server that accepts Streamable HTTP
 * and WebSocket connections on one endpoint path ({@value #DEFAULT_ACP_PATH} by default), and
 * creates a fresh agent for each connection with an {@link AcpAgentFactory}. Use it to run an
 * agent as a network service from a plain Java program; to mount the endpoint in a servlet
 * container you already run (Spring Boot, Tomcat), use {@link StreamableHttpAcpServlet}
 * instead, and to serve the one client that launched the agent process,
 * {@link StdioAcpAgentTransport}.
 *
 * <pre>{@code
 * AcpAgentFactory agents = AcpAgentFactory.sync(transport -> AcpAgent.sync(transport)
 *     .initializeHandler(request -> AcpSchema.InitializeResponse.ok())
 *     .build());
 * var server = new StreamableHttpAcpAgentTransport(8080, AcpJsonMapper.createDefault(), agents);
 * server.start().block(); // http://localhost:8080/acp and ws://localhost:8080/acp
 * }</pre>
 *
 * <p>Unlike the stdio transport, it is not an
 * {@link com.agentclientprotocol.sdk.spec.AcpAgentTransport} and is not passed to an agent
 * builder; it works the other way round. Each connection, opened by an
 * {@code initialize} POST or a WebSocket upgrade, gets a connection-bound transport of its own
 * ({@link RemoteAcpConnection}), and the factory builds that connection's agent on it. The
 * agent lives until the client sends {@code DELETE} or closes the WebSocket, or the listener
 * closes. Agents from one factory run concurrently, so whatever they share must be
 * thread-safe. Clients connect with {@code StreamableHttpAcpClientTransport} or
 * {@code WebSocketAcpClientTransport} from {@code acp-core}, or with another SDK's client.
 *
 * <p>It listens on the loopback interface only by default ({@code 127.0.0.1}, and {@code ::1}
 * where the machine has IPv6), over plain HTTP/1.1 and cleartext HTTP/2 (h2c), so only
 * programs on the same machine can connect. It has no authentication of its own: anyone who
 * can reach the port can start an agent. Exposing it to the network is an explicit opt-in,
 * {@link StreamableHttpAcpAgentTransportOptions.Builder#host host("0.0.0.0")} (or a single
 * address); do so only behind a proxy or firewall that controls who connects, and for TLS put
 * a proxy that terminates it in front, or mount the servlet in a container that has TLS.
 * Browser requests from an origin other than a loopback one are refused (403) unless listed
 * in {@link StreamableHttpAcpAgentTransportOptions.Builder#allowedOrigins allowedOrigins}.
 * Close it with {@link #closeGracefully()}; it registers no JVM shutdown hook.
 *
 * <p>On JDK 21 and later it serves on Jetty's {@code VirtualThreadPool}: every task on a
 * virtual thread ({@code acp-listener-*}), or on the application's executor when the options
 * name one ({@link StreamableHttpAcpAgentTransportOptions.Builder#executor}), and its timer on a
 * virtual thread too; Jetty parks one platform thread
 * ({@code jetty-virtual-thread-pool-keepalive}) while the server runs. Before JDK 21, or with
 * {@link StreamableHttpAcpAgentTransportOptions.Builder#virtualThreads virtualThreads(false)},
 * it serves on Jetty's default pool of platform threads ({@code qtp*}) and its scheduler
 * thread.
 *
 * @author Kaiser Dandangi
 */
public class StreamableHttpAcpAgentTransport {

	private static final Logger logger = LoggerFactory.getLogger(StreamableHttpAcpAgentTransport.class);

	/**
	 * The endpoint path used when none is given: {@value}, the path that ACP's remote
	 * transport RFD names.
	 */
	public static final String DEFAULT_ACP_PATH = "/acp";

	private final int configuredPort;

	private final String path;

	private final StreamableHttpAcpAgentTransportOptions options;

	private final StreamableHttpAcpServlet servlet;

	private final AtomicBoolean started = new AtomicBoolean(false);

	private final AtomicBoolean closing = new AtomicBoolean(false);

	private final Sinks.One<Void> terminationSink = Sinks.one();

	private volatile @Nullable Server server;

	/** The timer of a server on the application's executor; Jetty does not stop it. */
	private volatile @Nullable ScheduledThreadPoolExecutor ownTimer;

	/** The port the listener bound when it started; 0 until then. */
	private volatile int boundPort;

	/**
	 * Creates a listener on the default path, {@value #DEFAULT_ACP_PATH}, with the default limits
	 * and JSON mapper ({@link AcpJsonMapper#createDefault()}). Nothing listens until
	 * {@link #start()}.
	 * @param port the port to listen on, or 0 for an ephemeral port chosen when the listener
	 * starts
	 * @param agentFactory creates the agent for each connection
	 * @throws IllegalArgumentException if the port is outside 0 to 65535 or
	 * {@code agentFactory} is null
	 */
	public StreamableHttpAcpAgentTransport(int port, AcpAgentFactory agentFactory) {
		this(port, AcpJsonMapper.createDefault(), agentFactory);
	}

	/**
	 * Creates a listener on the default path, {@value #DEFAULT_ACP_PATH}, with the default
	 * limits. Nothing listens until {@link #start()}.
	 * @param port the port to listen on, or 0 for an ephemeral port chosen when the listener
	 * starts (see {@link #getPort()})
	 * @param jsonMapper the mapper that reads and writes the messages
	 * @param agentFactory creates the agent for each connection
	 * @throws IllegalArgumentException if the port is outside 0 to 65535 or an argument is
	 * null
	 */
	public StreamableHttpAcpAgentTransport(int port, AcpJsonMapper jsonMapper, AcpAgentFactory agentFactory) {
		this(port, DEFAULT_ACP_PATH, jsonMapper, agentFactory);
	}

	/**
	 * Creates a listener on the given path, with the default limits. Nothing listens until
	 * {@link #start()}.
	 * @param port the port to listen on, or 0 for an ephemeral port chosen when the listener
	 * starts (see {@link #getPort()})
	 * @param path the endpoint path, such as {@code /acp}
	 * @param jsonMapper the mapper that reads and writes the messages
	 * @param agentFactory creates the agent for each connection
	 * @throws IllegalArgumentException if the port is outside 0 to 65535, the path is empty
	 * or an argument is null
	 */
	public StreamableHttpAcpAgentTransport(int port, String path, AcpJsonMapper jsonMapper,
			AcpAgentFactory agentFactory) {
		this(port, path, jsonMapper, agentFactory, StreamableHttpAcpAgentTransportOptions.defaults());
	}

	/**
	 * Creates a listener on the given path, with the limits and timings of {@code options}.
	 * Nothing listens until {@link #start()}.
	 * @param port the port to listen on, or 0 for an ephemeral port chosen when the listener
	 * starts (see {@link #getPort()})
	 * @param path the endpoint path, such as {@code /acp}
	 * @param jsonMapper the mapper that reads and writes the messages
	 * @param agentFactory creates the agent for each connection
	 * @param options the endpoint's limits and timings
	 * @throws IllegalArgumentException if the port is outside 0 to 65535, the path is empty,
	 * an argument is null, or the options name an executor before JDK 21
	 */
	public StreamableHttpAcpAgentTransport(int port, String path, AcpJsonMapper jsonMapper,
			AcpAgentFactory agentFactory, StreamableHttpAcpAgentTransportOptions options) {
		Assert.isTrue(port >= 0 && port <= 65535, "Port must be between 0 and 65535, 0 for an ephemeral port");
		Assert.hasText(path, "Path must not be empty");
		Assert.notNull(jsonMapper, "The JsonMapper can not be null");
		Assert.notNull(agentFactory, "The agentFactory can not be null");
		Assert.notNull(options, "The options can not be null");
		Assert.isTrue(options.executor() == null || VirtualThreads.isSupported(),
				"A listener on an executor of the application's needs JDK 21 or later (Jetty's VirtualThreadPool)");
		this.configuredPort = port;
		this.path = path;
		this.options = options;
		this.servlet = new StreamableHttpAcpServlet(jsonMapper, agentFactory, options);
	}

	/**
	 * Starts the embedded Jetty server and binds the port, on the thread that subscribes to
	 * the returned Mono. A listener whose start failed, for instance because the port is in
	 * use, cannot be started again.
	 * @return a Mono that completes once the listener accepts connections; it errors with an
	 * {@link IllegalStateException} if the listener was started before, and with Jetty's
	 * exception if the server cannot start
	 */
	public Mono<Void> start() {
		return Mono.fromCallable(() -> {
			// Guard at subscribe time: a start publisher subscribed twice is refused the
			// second time instead of starting a second Jetty server.
			if (!started.compareAndSet(false, true)) {
				throw new IllegalStateException("Already started");
			}
			Server jettyServer = createServer();
			List<ServerConnector> connectors;
			try {
				connectors = openConnectors(jettyServer);
			}
			catch (Exception e) {
				stopOwnTimer();
				throw e;
			}
			connectors.forEach(jettyServer::addConnector);
			jettyServer.setHandler(createContext());

			try {
				jettyServer.start();
			}
			catch (Exception e) {
				connectors.forEach(ServerConnector::close);
				stopOwnTimer();
				throw e;
			}
			this.server = jettyServer;
			this.boundPort = connectors.get(0).getLocalPort();
			logger.info("Streamable HTTP agent listener started on {} port {} at path {}",
					options.host() != null ? options.host() : "loopback", getPort(), path);
			return null;
		}).then();
	}

	/**
	 * Before JDK 21, Jetty's default server. From JDK 21, a server on Jetty's
	 * {@link VirtualThreadPool}, every task on a virtual thread of its own or on the
	 * application's executor, and a timer on a virtual thread of the listener's.
	 */
	private Server createServer() {
		Executor executor = options.executor();
		if (!VirtualThreads.isSupported() || !options.virtualThreads()) {
			return new Server();
		}
		VirtualThreadPool threadPool = new VirtualThreadPool();
		if (executor != null) {
			// Jetty does not shut down an executor it was given.
			threadPool.setVirtualThreadsExecutor(executor);
		}
		else {
			threadPool.setName("acp-listener");
		}
		ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1,
				VirtualThreads.factoryOrDaemon("acp-listener-timer"));
		timer.setRemoveOnCancelPolicy(true);
		this.ownTimer = timer;
		// Given its executor, Jetty's scheduler does not shut it down either: stopServer does.
		return new Server(threadPool, new ScheduledExecutorScheduler(timer), null);
	}

	private void stopOwnTimer() {
		ScheduledThreadPoolExecutor timer = this.ownTimer;
		if (timer != null) {
			timer.shutdownNow();
		}
	}

	/**
	 * The connectors, bound before the server starts: one on the configured host, or by default
	 * one on {@code 127.0.0.1} and, where the machine has IPv6, one on {@code ::1} on the same
	 * port. Binding first gives the IPv6 connector the port an ephemeral IPv4 one was given, and
	 * lets a machine without IPv6 run on IPv4 alone.
	 */
	private List<ServerConnector> openConnectors(Server jettyServer) throws IOException {
		String host = options.host();
		ServerConnector primary = createConnector(jettyServer, host != null ? host : "127.0.0.1", configuredPort);
		primary.open();
		if (host != null) {
			return List.of(primary);
		}
		ServerConnector ipv6 = createConnector(jettyServer, "::1", primary.getLocalPort());
		try {
			ipv6.open();
			return List.of(primary, ipv6);
		}
		catch (IOException e) {
			logger.debug("Not listening on ::1 (port {}): {}", primary.getLocalPort(), e.getMessage());
			ipv6.close();
			return List.of(primary);
		}
	}

	/** HTTP/1.1 and cleartext HTTP/2 on one address and port. */
	private ServerConnector createConnector(Server jettyServer, String host, int port) {
		HttpConfiguration httpConfig = new HttpConfiguration();
		HTTP2CServerConnectionFactory h2c = new HTTP2CServerConnectionFactory(httpConfig);
		// Every SSE stream is a long-lived HTTP/2 stream: one ACP client holds one per
		// session plus the connection stream. Jetty's default of 128 per connection
		// is reached by a client with ~120 sessions, and the server then answers with
		// GOAWAY, which takes down every exchange on the connection.
		h2c.setMaxConcurrentStreams(options.maxConcurrentStreamsPerConnection());
		ServerConnector jettyConnector = new ServerConnector(jettyServer, new HttpConnectionFactory(httpConfig), h2c);
		jettyConnector.setHost(host);
		jettyConnector.setPort(port);
		return jettyConnector;
	}

	/**
	 * The servlet at the endpoint path, on a context with Jetty's Jakarta WebSocket
	 * implementation, which the servlet upgrades requests with: one host, the same one an
	 * application's own container runs.
	 */
	private ServletContextHandler createContext() {
		ServletContextHandler context = new ServletContextHandler();
		context.setContextPath("/");
		ServletHolder holder = new ServletHolder(servlet);
		holder.setAsyncSupported(true);
		context.addServlet(holder, path);
		JakartaWebSocketServletContainerInitializer.configure(context, null);
		return context;
	}

	/**
	 * Sets the handler for the transport errors of every remote connection this listener
	 * holds, over HTTP/SSE and WebSocket alike, including those opened before the call. The
	 * default logs them. An agent factory may still install its own handler on the
	 * transport it is given.
	 * @param handler receives the connections' transport errors
	 */
	public void setExceptionHandler(Consumer<Throwable> handler) {
		servlet.setExceptionHandler(handler);
	}

	/**
	 * Returns the port the listener accepts connections on. Before {@link #start()} completes
	 * this is the configured port, which is 0 for an ephemeral port; once started it is the
	 * port actually bound (the OS-chosen one for 0), and it stays that after the listener
	 * closes.
	 * @return the bound port once started, otherwise the configured port
	 */
	public int getPort() {
		int bound = this.boundPort;
		return bound > 0 ? bound : configuredPort;
	}

	/**
	 * Closes every connection, HTTP and WebSocket alike, as
	 * {@link StreamableHttpAcpServlet#closeGracefully()} does, then stops Jetty and completes
	 * {@link #awaitTermination()}. Nothing waits for a client, and a connection whose agent
	 * has not closed within the
	 * {@linkplain StreamableHttpAcpAgentTransportOptions#shutdownTimeout() shutdown timeout}
	 * is closed at once. The listener registers no JVM shutdown hook; an application stops it
	 * by calling this method. Only the first call has an effect.
	 * @return a Mono that completes when the listener has stopped
	 */
	public Mono<Void> closeGracefully() {
		return Mono.defer(() -> {
			if (!closing.compareAndSet(false, true)) {
				return Mono.empty();
			}
			// The servlet's close is bounded by the shutdown timeout.
			return servlet.closeGracefully()
				.then(Mono.<Void>fromRunnable(this::stopServer))
				.doOnSuccess(ignored -> terminationSink.tryEmitValue(null));
		});
	}

	private void stopServer() {
		Server currentServer = this.server;
		if (currentServer != null) {
			try {
				currentServer.stop();
			}
			catch (Exception e) {
				throw new AcpConnectionException("Failed to stop Streamable HTTP listener", e);
			}
			finally {
				stopOwnTimer();
			}
		}
	}

	/**
	 * Returns a Mono that completes once {@link #closeGracefully()} has stopped the listener.
	 * @return a Mono that completes when the listener has stopped
	 */
	public Mono<Void> awaitTermination() {
		return terminationSink.asMono();
	}

	int activeConnectionCount() {
		return servlet.activeConnectionCount();
	}

}

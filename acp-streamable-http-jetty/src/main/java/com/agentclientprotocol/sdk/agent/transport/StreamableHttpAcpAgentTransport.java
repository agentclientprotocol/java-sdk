/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.util.AcpSchedulers;
import com.agentclientprotocol.sdk.util.Assert;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.server.ServerUpgradeResponse;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
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
 * <p>It listens on every network interface, over plain HTTP/1.1 and cleartext HTTP/2 (h2c),
 * and has no authentication of its own: anyone who can reach the port can start an agent.
 * For TLS, put a proxy that terminates it in front, or mount the servlet in a container that
 * has TLS. Close it with {@link #closeGracefully()}; it registers no JVM shutdown hook.
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

	private final AcpJsonMapper jsonMapper;

	private final AcpAgentFactory agentFactory;

	private final StreamableHttpAcpAgentTransportOptions options;

	private final StreamableHttpAcpServlet servlet;

	private final ConcurrentMap<String, StreamableHttpWebSocketConnection> webSocketConnections =
			new ConcurrentHashMap<>();

	private final AtomicBoolean started = new AtomicBoolean(false);

	private final AtomicBoolean closing = new AtomicBoolean(false);

	private final Sinks.One<Void> terminationSink = Sinks.one();

	private volatile @Nullable Server server;

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
	 * @throws IllegalArgumentException if the port is outside 0 to 65535, the path is empty
	 * or an argument is null
	 */
	public StreamableHttpAcpAgentTransport(int port, String path, AcpJsonMapper jsonMapper,
			AcpAgentFactory agentFactory, StreamableHttpAcpAgentTransportOptions options) {
		Assert.isTrue(port >= 0 && port <= 65535, "Port must be between 0 and 65535, 0 for an ephemeral port");
		Assert.hasText(path, "Path must not be empty");
		Assert.notNull(jsonMapper, "The JsonMapper can not be null");
		Assert.notNull(agentFactory, "The agentFactory can not be null");
		Assert.notNull(options, "The options can not be null");
		this.configuredPort = port;
		this.path = path;
		this.jsonMapper = jsonMapper;
		this.agentFactory = agentFactory;
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
			Server jettyServer = new Server();
			ServerConnector jettyConnector = createConnector(jettyServer);
			jettyServer.addConnector(jettyConnector);
			jettyServer.setHandler(createContext(jettyServer));

			jettyServer.start();
			this.server = jettyServer;
			this.boundPort = jettyConnector.getLocalPort();
			logger.info("Streamable HTTP agent listener started on port {} at path {}", getPort(), path);
			return null;
		}).then();
	}

	/** HTTP/1.1 and cleartext HTTP/2 on the configured port. */
	private ServerConnector createConnector(Server jettyServer) {
		HttpConfiguration httpConfig = new HttpConfiguration();
		HTTP2CServerConnectionFactory h2c = new HTTP2CServerConnectionFactory(httpConfig);
		// Every SSE stream is a long-lived HTTP/2 stream: one ACP client holds one per
		// session plus the connection stream. Jetty's default of 128 per connection
		// is reached by a client with ~120 sessions, and the server then answers with
		// GOAWAY, which takes down every exchange on the connection.
		h2c.setMaxConcurrentStreams(options.maxConcurrentStreamsPerConnection());
		ServerConnector jettyConnector = new ServerConnector(jettyServer, new HttpConnectionFactory(httpConfig), h2c);
		jettyConnector.setPort(configuredPort);
		return jettyConnector;
	}

	/** The servlet at the endpoint path, with WebSocket upgrades accepted on the same path. */
	private ServletContextHandler createContext(Server jettyServer) {
		ServletContextHandler context = new ServletContextHandler();
		context.setContextPath("/");
		ServletHolder holder = new ServletHolder(servlet);
		holder.setAsyncSupported(true);
		context.addServlet(holder, path);

		WebSocketUpgradeHandler webSocketHandler = WebSocketUpgradeHandler.from(jettyServer, context, container -> {
			container.setIdleTimeout(Duration.ofMinutes(30));
			// Jetty's default is 64 KB; a prompt or file content is often larger. One inbound
			// limit for both profiles: the POST body cap also bounds a WebSocket text message.
			container.setMaxTextMessageSize(options.maxPostBodyBytes());
			container.addMapping(path, (request, response, callback) -> acceptWebSocket(response, callback));
		});
		context.insertHandler(webSocketHandler);
		return context;
	}

	/** Starts a connection for an accepted WebSocket upgrade; null (refused) when it fails to start. */
	private StreamableHttpWebSocketConnection.@Nullable AcpWebSocketEndpoint acceptWebSocket(
			ServerUpgradeResponse response, Callback callback) {
		StreamableHttpWebSocketConnection connection = createWebSocketConnection();
		try {
			connection.start();
			webSocketConnections.put(connection.id(), connection);
			response.getHeaders().put(StreamableHttpRouting.HEADER_CONNECTION_ID, connection.id());
			return new StreamableHttpWebSocketConnection.AcpWebSocketEndpoint(connection, jsonMapper);
		}
		catch (Exception e) {
			connection.close();
			callback.failed(e);
			return null;
		}
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
			List<StreamableHttpWebSocketConnection> closingWebSockets = List.copyOf(webSocketConnections.values());
			webSocketConnections.clear();
			List<Mono<Void>> webSocketClosures = new ArrayList<>();
			closingWebSockets.forEach(connection -> webSocketClosures.add(connection.closeGracefully()));
			Duration timeout = options.shutdownTimeout();
			Mono<Void> webSockets = Mono.whenDelayError(webSocketClosures)
				.timeout(timeout, AcpSchedulers.timeouts())
				.onErrorResume(TimeoutException.class, timedOut -> {
					logger.warn("Streamable ACP WebSocket connections did not close within {}; closing them now",
							timeout);
					closingWebSockets.forEach(StreamableHttpWebSocketConnection::closeNow);
					return Mono.empty();
				});

			// The servlet's close is bounded by the same shutdown timeout.
			return Mono.whenDelayError(servlet.closeGracefully(), webSockets)
				.then(Mono.<Void>fromRunnable(this::stopServer))
				.doOnSuccess(ignored -> {
					terminationSink.tryEmitValue(null);
				});
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
		return servlet.activeConnectionCount() + webSocketConnections.size();
	}

	private StreamableHttpWebSocketConnection createWebSocketConnection() {
		String connectionId = UUID.randomUUID().toString();
		return new StreamableHttpWebSocketConnection(connectionId, jsonMapper, agentFactory, options,
				connection -> webSocketConnections.remove(connection.id(), connection), servlet::reportException);
	}

}

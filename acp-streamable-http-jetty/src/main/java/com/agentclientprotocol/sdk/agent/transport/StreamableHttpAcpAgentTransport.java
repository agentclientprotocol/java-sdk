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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
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
 * Listener-backed ACP Streamable HTTP transport for agents.
 *
 * <p>
 * This transport hosts the ACP Streamable HTTP endpoint on Jetty, including POST/SSE
 * request handling and WebSocket upgrades on the same path. It creates one fresh agent
 * runtime per remote ACP connection through {@link AcpAgentFactory}. The accepted
 * connection then owns its own per-connection {@link RemoteAcpConnection}, while the
 * listener remains responsible only for wire-level concerns such as headers, SSE
 * streams, WebSocket frames, and request routing.
 * </p>
 *
 * <p>
 * WebSocket support is intentionally hosted here instead of as a separate public
 * listener so one {@code /acp} endpoint can behave like the RFD and the Rust
 * {@code AcpHttpServer}: HTTP requests fall through to the servlet, while valid
 * WebSocket upgrade requests are accepted by Jetty's {@link WebSocketUpgradeHandler}.
 * </p>
 *
 * <p>
 * The wire work is split across package collaborators: {@link StreamableHttpAcpServlet}
 * (POST/GET/DELETE), {@link StreamableHttpConnection} (one POST/SSE connection and its
 * scope routing), {@link SseOutboundStream} (mailbox and SSE subscriber),
 * {@link StreamableHttpWebSocketConnection} (one upgraded connection) and
 * {@link StreamableHttpRouting} (method-to-stream rules). This class owns the Jetty
 * server, the connection registries, keep-alive and shutdown.
 * </p>
 *
 * @author Kaiser Dandangi
 */
public class StreamableHttpAcpAgentTransport {

	private static final Logger logger = LoggerFactory.getLogger(StreamableHttpAcpAgentTransport.class);

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
	 * Creates a new Streamable HTTP listener on the default ACP path.
	 * @param port port to listen on, or 0 for an ephemeral port chosen when the listener
	 * starts (see {@link #getPort()})
	 * @param jsonMapper JSON mapper used for serialization
	 * @param agentFactory factory used to create one agent runtime per connection
	 */
	public StreamableHttpAcpAgentTransport(int port, AcpJsonMapper jsonMapper, AcpAgentFactory agentFactory) {
		this(port, DEFAULT_ACP_PATH, jsonMapper, agentFactory);
	}

	/**
	 * Creates a new Streamable HTTP listener.
	 * @param port port to listen on, or 0 for an ephemeral port chosen when the listener
	 * starts (see {@link #getPort()})
	 * @param path endpoint path
	 * @param jsonMapper JSON mapper used for serialization
	 * @param agentFactory factory used to create one agent runtime per connection
	 */
	public StreamableHttpAcpAgentTransport(int port, String path, AcpJsonMapper jsonMapper,
			AcpAgentFactory agentFactory) {
		this(port, path, jsonMapper, agentFactory, StreamableHttpAcpAgentTransportOptions.defaults());
	}

	/**
	 * Creates a new Streamable HTTP listener with explicit limits.
	 * @param port port to listen on, or 0 for an ephemeral port chosen when the listener
	 * starts (see {@link #getPort()})
	 * @param path endpoint path
	 * @param jsonMapper JSON mapper used for serialization
	 * @param agentFactory factory used to create one agent runtime per connection
	 * @param options bounds and timings
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
	 * Starts the embedded Jetty server.
	 * @return a mono that completes when the listener is ready
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
	 * Closes all active connections and stops the listener.
	 * @return a mono that completes when shutdown finishes
	 */
	public Mono<Void> closeGracefully() {
		return Mono.defer(() -> {
			if (!closing.compareAndSet(false, true)) {
				return Mono.empty();
			}
			List<Mono<Void>> connectionClosures = new ArrayList<>();
			connectionClosures.add(servlet.closeGracefully());
			webSocketConnections.values().forEach(connection -> connectionClosures.add(connection.closeGracefully()));
			webSocketConnections.clear();

			return Mono.whenDelayError(connectionClosures)
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
	 * Returns a mono that completes once the listener terminates.
	 * @return termination mono
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

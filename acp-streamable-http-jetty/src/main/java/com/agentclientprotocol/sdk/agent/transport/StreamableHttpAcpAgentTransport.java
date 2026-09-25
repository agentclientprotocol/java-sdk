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
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
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

	static final String HEADER_CONNECTION_ID = "Acp-Connection-Id";

	static final String HEADER_SESSION_ID = "Acp-Session-Id";

	static final String CONTENT_TYPE_EVENT_STREAM = "text/event-stream";

	static final Duration INITIALIZE_TIMEOUT = Duration.ofSeconds(30);

	private final int configuredPort;

	private final String path;

	private final AcpJsonMapper jsonMapper;

	private final AcpAgentFactory agentFactory;

	private final StreamableHttpAcpAgentTransportOptions options;

	private final StreamableHttpRouting routing;

	private volatile Scheduler keepAliveScheduler;

	private volatile Disposable keepAliveTask;

	private final ConcurrentMap<String, StreamableHttpConnection> connections = new ConcurrentHashMap<>();

	private final ConcurrentMap<String, StreamableHttpWebSocketConnection> webSocketConnections =
			new ConcurrentHashMap<>();

	private final AtomicBoolean started = new AtomicBoolean(false);

	private final AtomicBoolean closing = new AtomicBoolean(false);

	private final Sinks.One<Void> terminationSink = Sinks.one();

	private volatile Server server;

	private volatile ServerConnector connector;

	/**
	 * Creates a new Streamable HTTP listener on the default ACP path.
	 * @param port port to listen on
	 * @param jsonMapper JSON mapper used for serialization
	 * @param agentFactory factory used to create one agent runtime per connection
	 */
	public StreamableHttpAcpAgentTransport(int port, AcpJsonMapper jsonMapper, AcpAgentFactory agentFactory) {
		this(port, DEFAULT_ACP_PATH, jsonMapper, agentFactory);
	}

	/**
	 * Creates a new Streamable HTTP listener.
	 * @param port port to listen on
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
	 * @param port port to listen on
	 * @param path endpoint path
	 * @param jsonMapper JSON mapper used for serialization
	 * @param agentFactory factory used to create one agent runtime per connection
	 * @param options bounds and timings
	 */
	public StreamableHttpAcpAgentTransport(int port, String path, AcpJsonMapper jsonMapper,
			AcpAgentFactory agentFactory, StreamableHttpAcpAgentTransportOptions options) {
		Assert.isTrue(port > 0, "Port must be positive");
		Assert.hasText(path, "Path must not be empty");
		Assert.notNull(jsonMapper, "The JsonMapper can not be null");
		Assert.notNull(agentFactory, "The agentFactory can not be null");
		Assert.notNull(options, "The options can not be null");
		this.configuredPort = port;
		this.path = path;
		this.jsonMapper = jsonMapper;
		this.agentFactory = agentFactory;
		this.options = options;
		this.routing = new StreamableHttpRouting(jsonMapper);
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
			HttpConfiguration httpConfig = new HttpConfiguration();
			ServerConnector jettyConnector = new ServerConnector(jettyServer,
					new HttpConnectionFactory(httpConfig), new HTTP2CServerConnectionFactory(httpConfig));
			jettyConnector.setPort(configuredPort);
			jettyServer.addConnector(jettyConnector);

			ServletContextHandler context = new ServletContextHandler();
			context.setContextPath("/");
			context.addServlet(new ServletHolder(
					new StreamableHttpAcpServlet(jsonMapper, options, connections, this::createConnection)), path);

			WebSocketUpgradeHandler webSocketHandler = WebSocketUpgradeHandler.from(jettyServer, context, container -> {
				container.setIdleTimeout(Duration.ofMinutes(30));
				container.addMapping(path, (request, response, callback) -> {
					StreamableHttpWebSocketConnection connection = createWebSocketConnection();
					try {
						connection.start();
						webSocketConnections.put(connection.id(), connection);
						response.getHeaders().put(HEADER_CONNECTION_ID, connection.id());
						return new StreamableHttpWebSocketConnection.AcpWebSocketEndpoint(connection, jsonMapper);
					}
					catch (Exception e) {
						connection.close();
						callback.failed(e);
						return null;
					}
				});
			});
			context.insertHandler(webSocketHandler);
			jettyServer.setHandler(context);

			jettyServer.start();
			this.server = jettyServer;
			this.connector = jettyConnector;
			startKeepAlive();
			logger.info("Streamable HTTP agent listener started on port {} at path {}", getPort(), path);
			return null;
		}).then();
	}

	/**
	 * Returns the bound port.
	 * @return listener port
	 */
	public int getPort() {
		ServerConnector currentConnector = this.connector;
		return currentConnector != null ? currentConnector.getLocalPort() : configuredPort;
	}

	/**
	 * Closes all active connections and stops the listener.
	 * @return a mono that completes when shutdown finishes
	 */
	public Mono<Void> closeGracefully() {
		return Mono.defer(() -> {
			if (!closing.compareAndSet(false, true)) {
				return Mono.<Void>empty();
			}
			List<Mono<Void>> connectionClosures = new ArrayList<>();
			connections.values().forEach(connection -> connectionClosures.add(connection.closeGracefully()));
			connections.clear();
			webSocketConnections.values().forEach(connection -> connectionClosures.add(connection.closeGracefully()));
			webSocketConnections.clear();

			return Mono.whenDelayError(connectionClosures)
				.then(Mono.<Void>fromRunnable(this::stopServer))
				.doOnSuccess(ignored -> {
					terminationSink.tryEmitValue(null);
				});
		});
	}

	private void startKeepAlive() {
		Duration interval = options.keepAliveInterval();
		if (interval.isZero()) {
			return;
		}
		Scheduler scheduler = Schedulers.newSingle("acp-streamable-http-keepalive", true);
		this.keepAliveScheduler = scheduler;
		// A comment every interval keeps proxies from cutting idle streams and surfaces
		// dead subscribers (the write fails) without waiting for the next real event.
		this.keepAliveTask = Flux.interval(interval, interval, scheduler)
			.subscribe(tick -> connections.values().forEach(connection -> {
				try {
					connection.keepAlive();
				}
				catch (RuntimeException e) {
					// One broken connection must not stop keep-alives for every other one.
					logger.debug("Keep-alive failed for connection {}: {}", connection.id(), e.getMessage());
				}
			}));
	}

	private void stopKeepAlive() {
		Disposable task = this.keepAliveTask;
		if (task != null) {
			task.dispose();
		}
		Scheduler scheduler = this.keepAliveScheduler;
		if (scheduler != null) {
			scheduler.dispose();
		}
	}

	private void stopServer() {
		stopKeepAlive();
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
		return connections.size() + webSocketConnections.size();
	}

	private StreamableHttpConnection createConnection() {
		String connectionId = UUID.randomUUID().toString();
		return new StreamableHttpConnection(connectionId, jsonMapper, agentFactory, routing, options,
				connection -> connections.remove(connection.id(), connection));
	}

	private StreamableHttpWebSocketConnection createWebSocketConnection() {
		String connectionId = UUID.randomUUID().toString();
		return new StreamableHttpWebSocketConnection(connectionId, jsonMapper, agentFactory, options,
				connection -> webSocketConnections.remove(connection.id(), connection));
	}

}

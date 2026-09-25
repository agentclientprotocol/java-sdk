/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpConnection.UnknownSessionException;
import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import com.agentclientprotocol.sdk.util.AcpSchedulers;
import com.agentclientprotocol.sdk.util.Assert;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import static com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport.CONTENT_TYPE_EVENT_STREAM;
import static com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport.HEADER_CONNECTION_ID;
import static com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport.HEADER_SESSION_ID;
import static com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransport.INITIALIZE_TIMEOUT;

/**
 * The HTTP side of the ACP Streamable HTTP endpoint: {@code POST} carries client
 * messages (and {@code initialize}, which creates the connection), {@code GET} opens the
 * connection or a session SSE stream, {@code DELETE} closes the connection.
 *
 * <p>
 * <b>Mountable in any Servlet 6 container</b> (Spring Boot, Tomcat, Jetty, Undertow) at
 * the path of your choice; register it with async support enabled:
 * </p>
 *
 * <pre>{@code
 * StreamableHttpAcpServlet servlet = new StreamableHttpAcpServlet(AcpJsonMapper.createDefault(), agentFactory);
 * ServletRegistration.Dynamic registration = servletContext.addServlet("acp", servlet);
 * registration.addMapping("/acp");
 * registration.setAsyncSupported(true);
 * }</pre>
 *
 * <p>
 * The servlet owns its connections: {@link #init()} starts the SSE keep-alive and
 * {@link #destroy()} closes every connection, cancelling in-flight prompts, so the
 * container's lifecycle drives it. It serves the HTTP/SSE profile of the RFD; the
 * WebSocket upgrade on the same path needs {@link StreamableHttpAcpAgentTransport}, which
 * mounts this servlet on its own Jetty server next to the upgrade handler. HTTP/2 is the
 * container's to configure.
 * </p>
 *
 * @author Kaiser Dandangi
 */
public class StreamableHttpAcpServlet extends HttpServlet {

	private static final Logger logger = LoggerFactory.getLogger(StreamableHttpAcpServlet.class);

	private static final String CONTENT_TYPE_JSON = "application/json";

	private final transient AcpJsonMapper jsonMapper;

	private final transient StreamableHttpAcpAgentTransportOptions options;

	private final transient ConcurrentMap<String, StreamableHttpConnection> connections = new ConcurrentHashMap<>();

	private final transient AcpAgentFactory agentFactory;

	private final transient StreamableHttpRouting routing;

	private final transient AtomicBoolean closing = new AtomicBoolean(false);

	private transient volatile Scheduler keepAliveScheduler;

	private transient volatile Disposable keepAliveTask;

	/**
	 * Creates a servlet with the default limits.
	 * @param jsonMapper JSON mapper used for serialization
	 * @param agentFactory creates one agent runtime per remote connection
	 */
	public StreamableHttpAcpServlet(AcpJsonMapper jsonMapper, AcpAgentFactory agentFactory) {
		this(jsonMapper, agentFactory, StreamableHttpAcpAgentTransportOptions.defaults());
	}

	/**
	 * Creates a servlet with explicit limits.
	 * @param jsonMapper JSON mapper used for serialization
	 * @param agentFactory creates one agent runtime per remote connection
	 * @param options bounds and timings; {@code maxConcurrentStreamsPerConnection} is the
	 * container's to configure when this servlet is mounted elsewhere
	 */
	public StreamableHttpAcpServlet(AcpJsonMapper jsonMapper, AcpAgentFactory agentFactory,
			StreamableHttpAcpAgentTransportOptions options) {
		Assert.notNull(jsonMapper, "The JsonMapper can not be null");
		Assert.notNull(agentFactory, "The agentFactory can not be null");
		Assert.notNull(options, "The options can not be null");
		this.jsonMapper = jsonMapper;
		this.agentFactory = agentFactory;
		this.options = options;
		this.routing = new StreamableHttpRouting(jsonMapper);
	}

	/** Starts the SSE keep-alive. Called by the container when the servlet is put into service. */
	@Override
	public void init() throws ServletException {
		super.init();
		Duration interval = options.keepAliveInterval();
		if (interval.isZero() || keepAliveTask != null) {
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

	/** Closes every connection and stops the keep-alive. Called by the container on shutdown. */
	@Override
	public void destroy() {
		try {
			closeGracefully().block(INITIALIZE_TIMEOUT);
		}
		catch (RuntimeException e) {
			logger.warn("Streamable HTTP servlet did not close within {}: {}", INITIALIZE_TIMEOUT, e.getMessage());
		}
		super.destroy();
	}

	/**
	 * Closes every connection this servlet holds, cancelling in-flight prompts, and stops
	 * the keep-alive. New requests are refused afterwards.
	 * @return a Mono that completes when every connection has closed
	 */
	public Mono<Void> closeGracefully() {
		return Mono.defer(() -> {
			if (!closing.compareAndSet(false, true)) {
				return Mono.<Void>empty();
			}
			Disposable task = this.keepAliveTask;
			if (task != null) {
				task.dispose();
			}
			Scheduler scheduler = this.keepAliveScheduler;
			if (scheduler != null) {
				scheduler.dispose();
			}
			List<Mono<Void>> closures = new ArrayList<>();
			connections.values().forEach(connection -> closures.add(connection.closeGracefully()));
			connections.clear();
			return Mono.whenDelayError(closures);
		});
	}

	/**
	 * The number of remote connections this servlet currently holds.
	 * @return open connections
	 */
	public int activeConnectionCount() {
		return connections.size();
	}

	private StreamableHttpConnection createConnection() {
		return new StreamableHttpConnection(UUID.randomUUID().toString(), jsonMapper, agentFactory, routing, options,
				connection -> connections.remove(connection.id(), connection));
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		if (!hasContentType(request, CONTENT_TYPE_JSON)) {
			writeText(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
					"Content-Type must be application/json");
			return;
		}

		long declaredLength = request.getContentLengthLong();
		if (declaredLength > options.maxPostBodyBytes()) {
			writeText(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
					"POST body exceeds " + options.maxPostBodyBytes() + " bytes");
			return;
		}
		byte[] bodyBytes = request.getInputStream().readNBytes((int) Math.min(Integer.MAX_VALUE,
				options.maxPostBodyBytes() + 1));
		if (bodyBytes.length > options.maxPostBodyBytes()) {
			writeText(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
					"POST body exceeds " + options.maxPostBodyBytes() + " bytes");
			return;
		}
		String body = new String(bodyBytes, StandardCharsets.UTF_8);
		if (body.stripLeading().startsWith("[")) {
			writeText(response, HttpServletResponse.SC_NOT_IMPLEMENTED, "JSON-RPC batches are not supported");
			return;
		}

		JSONRPCMessage message;
		try {
			message = AcpSchema.deserializeJsonRpcMessage(jsonMapper, body);
		}
		catch (Exception e) {
			writeText(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON-RPC");
			return;
		}

		if (StreamableHttpRouting.isInitialize(message)) {
			handleInitialize(request, response, (AcpSchema.JSONRPCRequest) message);
			return;
		}

		String connectionId = header(request, HEADER_CONNECTION_ID).orElse(null);
		if (connectionId == null) {
			writeText(response, HttpServletResponse.SC_BAD_REQUEST, HEADER_CONNECTION_ID + " header required");
			return;
		}
		StreamableHttpConnection connection = connections.get(connectionId);
		if (connection == null) {
			response.setStatus(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		try {
			connection.acceptClientPost(message, header(request, HEADER_SESSION_ID).orElse(null));
			response.setStatus(HttpServletResponse.SC_ACCEPTED);
		}
		catch (UnknownSessionException e) {
			writeText(response, HttpServletResponse.SC_NOT_FOUND, e.getMessage());
		}
		catch (AcpConnectionException | IllegalArgumentException e) {
			writeText(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
		}
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		if (!accepts(request, CONTENT_TYPE_EVENT_STREAM)) {
			writeText(response, HttpServletResponse.SC_NOT_ACCEPTABLE, "client must accept text/event-stream");
			return;
		}

		String connectionId = header(request, HEADER_CONNECTION_ID).orElse(null);
		if (connectionId == null) {
			writeText(response, HttpServletResponse.SC_BAD_REQUEST, HEADER_CONNECTION_ID + " header required");
			return;
		}
		StreamableHttpConnection connection = connections.get(connectionId);
		if (connection == null) {
			response.setStatus(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		try {
			connection.openStream(request, response, header(request, HEADER_SESSION_ID).orElse(null));
		}
		catch (UnknownSessionException e) {
			writeText(response, HttpServletResponse.SC_NOT_FOUND, e.getMessage());
		}
	}

	@Override
	protected void doDelete(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		String connectionId = header(request, HEADER_CONNECTION_ID).orElse(null);
		if (connectionId == null) {
			writeText(response, HttpServletResponse.SC_BAD_REQUEST, HEADER_CONNECTION_ID + " header required");
			return;
		}
		StreamableHttpConnection connection = connections.remove(connectionId);
		if (connection == null) {
			response.setStatus(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		connection.close();
		response.setStatus(HttpServletResponse.SC_ACCEPTED);
	}

	private void handleInitialize(HttpServletRequest request, HttpServletResponse response,
			AcpSchema.JSONRPCRequest initializeRequest) throws IOException {
		if (header(request, HEADER_CONNECTION_ID).isPresent()) {
			writeText(response, HttpServletResponse.SC_BAD_REQUEST,
					"initialize must not include " + HEADER_CONNECTION_ID);
			return;
		}

		if (closing.get()) {
			writeText(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ACP endpoint is shutting down");
			return;
		}
		StreamableHttpConnection connection = createConnection();
		AsyncContext asyncContext = request.startAsync();
		asyncContext.setTimeout(INITIALIZE_TIMEOUT.toMillis());
		AtomicBoolean completed = new AtomicBoolean(false);
		asyncContext.addListener(new AsyncListener() {

			@Override
			public void onComplete(AsyncEvent event) {
			}

			@Override
			public void onTimeout(AsyncEvent event) {
				completeInitializeFailure(asyncContext, response, connection, completed);
			}

			@Override
			public void onError(AsyncEvent event) {
				completeInitializeFailure(asyncContext, response, connection, completed);
			}

			@Override
			public void onStartAsync(AsyncEvent event) {
				event.getAsyncContext().addListener(this);
			}

		});

		connection.start()
			.then(Mono.defer(() -> connection.initialize(initializeRequest)))
			.timeout(INITIALIZE_TIMEOUT, AcpSchedulers.timeouts())
			// Runs on the servlet thread: the request is already async, agent creation
			// is cheap, and the handler runs on the agent's own scheduler. The SDK does
			// not use the global boundedElastic scheduler.
			.subscribe(initializeResponse -> completeInitializeSuccess(asyncContext, response, connection,
					completed, initializeResponse),
				error -> completeInitializeFailure(asyncContext, response, connection, completed));
	}

	private void completeInitializeSuccess(AsyncContext asyncContext, HttpServletResponse response,
			StreamableHttpConnection connection, AtomicBoolean completed, JSONRPCMessage initializeResponse) {
		if (!completed.compareAndSet(false, true)) {
			return;
		}
		try {
			if (!(initializeResponse instanceof AcpSchema.JSONRPCResponse)) {
				throw new AcpConnectionException("initialize did not produce a JSON-RPC response");
			}
			connections.put(connection.id(), connection);
			response.setStatus(HttpServletResponse.SC_OK);
			response.setContentType(CONTENT_TYPE_JSON);
			response.setHeader(HEADER_CONNECTION_ID, connection.id());
			response.getWriter().write(jsonMapper.writeValueAsString(initializeResponse));
		}
		catch (Exception e) {
			connections.remove(connection.id(), connection);
			connection.close();
			try {
				writeText(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "initialize failed");
			}
			catch (IOException writeError) {
				logger.warn("Failed to write Streamable HTTP initialize failure", writeError);
			}
		}
		finally {
			completeAsyncContext(asyncContext);
		}
	}

	private void completeInitializeFailure(AsyncContext asyncContext, HttpServletResponse response,
			StreamableHttpConnection connection, AtomicBoolean completed) {
		if (!completed.compareAndSet(false, true)) {
			return;
		}
		connections.remove(connection.id(), connection);
		connection.close();
		try {
			writeText(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "initialize failed");
		}
		catch (IOException writeError) {
			logger.warn("Failed to write Streamable HTTP initialize failure", writeError);
		}
		finally {
			completeAsyncContext(asyncContext);
		}
	}

	private void completeAsyncContext(AsyncContext asyncContext) {
		try {
			asyncContext.complete();
		}
		catch (IllegalStateException ignored) {
		}
	}

	private boolean hasContentType(HttpServletRequest request, String expected) {
		return Optional.ofNullable(request.getContentType())
			.map(String::toLowerCase)
			.map(contentType -> contentType.split(";", 2)[0].trim())
			.filter(contentType -> contentType.equals(expected))
			.isPresent();
	}

	private boolean accepts(HttpServletRequest request, String expected) {
		return Optional.ofNullable(request.getHeader("Accept"))
			.map(String::toLowerCase)
			.filter(accept -> accept.contains(expected))
			.isPresent();
	}

	private Optional<String> header(HttpServletRequest request, String name) {
		return Optional.ofNullable(request.getHeader(name)).filter(value -> !value.isBlank());
	}

	private void writeText(HttpServletResponse response, int status, String body) throws IOException {
		response.setStatus(status);
		response.setContentType("text/plain");
		response.getWriter().write(body);
	}

}

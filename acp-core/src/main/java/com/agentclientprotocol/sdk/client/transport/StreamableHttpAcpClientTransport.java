/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import com.agentclientprotocol.sdk.client.transport.StreamableHttpRequests.HttpClientBundle;
import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import com.agentclientprotocol.sdk.util.AcpSchedulers;
import com.agentclientprotocol.sdk.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The client side of the Streamable HTTP transport: talks to an agent that is already running
 * behind an HTTP endpoint, posting the client's messages to it and reading the agent's
 * messages from Server-Sent Event (SSE) streams. Use it to reach a remote agent, for instance
 * one served by the {@code acp-streamable-http-jetty} module's listener or servlet; to start
 * the agent as a child process, use {@link StdioAcpClientTransport}. Pass it to
 * {@code AcpClient.sync(transport)} or {@code AcpClient.async(transport)}:
 *
 * <pre>{@code
 * var transport = new StreamableHttpAcpClientTransport(URI.create("http://localhost:8080/acp"),
 *         AcpJsonMapper.createDefault());
 * AcpSyncClient client = AcpClient.sync(transport).build();
 * }</pre>
 *
 * <p>Unlike the stdio transport, connecting does not reach the agent. The {@code initialize}
 * request opens the connection, so it must be the first message sent; its answer carries the
 * connection's id in the Acp-Connection-Id header, which every later request sends, and
 * requests about one ACP session also name it in the Acp-Session-Id header. The transport
 * opens one SSE stream for the connection and one for each ACP session, before the session's
 * first request, and reconnects a stream that the server or the network closes. If the
 * connection's stream, or a session stream that still owes a response, cannot be reopened,
 * the transport ends: its {@link #awaitTermination()} errors and the client's pending requests
 * fail. The wire format is the one of ACP's Streamable HTTP and WebSocket transport RFD
 * (https://agentclientprotocol.com/rfds/streamable-http-websocket-transport).
 *
 * <p>The default HTTP client asks for HTTP/2. Over {@code https} it negotiates it; over plain
 * {@code http} the transport first sends a bodiless GET so that a server that speaks
 * cleartext HTTP/2 (h2c) can upgrade the connection, and uses HTTP/1.1 for every request when
 * the server does not. The default client keeps cookies in a cookie manager of its own and
 * runs on a bounded pool of daemon threads; {@link StreamableHttpAcpClientTransportOptions}
 * sets its sizes and the number of SSE streams. Pass an {@link HttpClient} of your own for TLS,
 * proxy or authentication settings.
 *
 * <p>{@link #closeGracefully()} closes the streams and sends {@code DELETE} for the connection,
 * waiting at most five seconds for the answer; {@link #close()} does the same and blocks for up
 * to ten seconds. The transport is thread-safe: messages may be sent from any thread, and are
 * posted concurrently.
 *
 * @author Kaiser Dandangi
 */
public class StreamableHttpAcpClientTransport implements AcpClientTransport {

	private static final Logger logger = LoggerFactory.getLogger(StreamableHttpAcpClientTransport.class);

	/**
	 * The endpoint path that ACP's remote transport RFD names, and that the SDK's listener
	 * serves by default: {@value}. The transport does not add it; the endpoint URI must
	 * include the path.
	 */
	public static final String DEFAULT_ACP_PATH = "/acp";

	private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(10);

	private final AcpJsonMapper jsonMapper;

	private final StreamableHttpRequests requests;

	private final StreamableHttpRoutes routes;

	private final StreamableHttpStreams streams;

	private final StreamableHttpInbound inbound;

	private final AtomicBoolean connected = new AtomicBoolean(false);

	private final AtomicBoolean initialized = new AtomicBoolean(false);

	private final AtomicBoolean closing = new AtomicBoolean(false);

	private volatile Consumer<Throwable> exceptionHandler = t -> logger.error("Transport error", t);

	private final Sinks.One<Void> terminationSink = Sinks.one();

	/**
	 * Creates a transport for the endpoint at {@code endpointUri}, with the default HTTP
	 * client (HTTP/2, its own {@link CookieManager}) and the default limits.
	 * @param endpointUri the agent's endpoint: an {@code http} or {@code https} URI including
	 * its path, such as {@code http://localhost:8080/acp}
	 * @param jsonMapper the mapper that reads and writes the messages
	 * @throws IllegalArgumentException if an argument is null or the URI's scheme is not
	 * {@code http} or {@code https}
	 */
	public StreamableHttpAcpClientTransport(URI endpointUri, AcpJsonMapper jsonMapper) {
		this(endpointUri, jsonMapper, StreamableHttpAcpClientTransportOptions.defaults());
	}

	/**
	 * Creates a transport for the endpoint at {@code endpointUri}, with the default HTTP client
	 * sized by {@code options}.
	 * @param endpointUri the agent's endpoint: an {@code http} or {@code https} URI including
	 * its path
	 * @param jsonMapper the mapper that reads and writes the messages
	 * @param options the transport's limits
	 * @throws IllegalArgumentException if an argument is null or the URI's scheme is not
	 * {@code http} or {@code https}
	 */
	public StreamableHttpAcpClientTransport(URI endpointUri, AcpJsonMapper jsonMapper,
			StreamableHttpAcpClientTransportOptions options) {
		this(endpointUri, jsonMapper, createDefaultHttpClient(options), options);
	}

	/**
	 * Creates a transport for the endpoint at {@code endpointUri} that sends its requests with
	 * {@code httpClient}, for TLS, proxy, authentication or cookie settings of your own. The
	 * transport does not close the client or its executor.
	 * @param endpointUri the agent's endpoint: an {@code http} or {@code https} URI including
	 * its path
	 * @param jsonMapper the mapper that reads and writes the messages
	 * @param httpClient the client the requests are sent with
	 * @throws IllegalArgumentException if an argument is null or the URI's scheme is not
	 * {@code http} or {@code https}
	 */
	public StreamableHttpAcpClientTransport(URI endpointUri, AcpJsonMapper jsonMapper, HttpClient httpClient) {
		this(endpointUri, jsonMapper, httpClient, StreamableHttpAcpClientTransportOptions.defaults());
	}

	/**
	 * Creates a transport for the endpoint at {@code endpointUri} that sends its requests with
	 * {@code httpClient} and has the limits of {@code options}. The options' worker threads
	 * size only the default client, so they do not apply here. The transport does not close
	 * the client or its executor.
	 * @param endpointUri the agent's endpoint: an {@code http} or {@code https} URI including
	 * its path
	 * @param jsonMapper the mapper that reads and writes the messages
	 * @param httpClient the client the requests are sent with
	 * @param options the transport's limits
	 * @throws IllegalArgumentException if an argument is null or the URI's scheme is not
	 * {@code http} or {@code https}
	 */
	public StreamableHttpAcpClientTransport(URI endpointUri, AcpJsonMapper jsonMapper, HttpClient httpClient,
			StreamableHttpAcpClientTransportOptions options) {
		this(endpointUri, jsonMapper, new HttpClientBundle(httpClient, null), options);
	}

	private StreamableHttpAcpClientTransport(URI endpointUri, AcpJsonMapper jsonMapper, HttpClientBundle bundle,
			StreamableHttpAcpClientTransportOptions options) {
		Assert.notNull(endpointUri, "The endpointUri can not be null");
		Assert.notNull(jsonMapper, "The JsonMapper can not be null");
		Assert.notNull(bundle, "The HttpClient bundle can not be null");
		Assert.notNull(bundle.httpClient(), "The HttpClient can not be null");
		Assert.notNull(options, "The transport options can not be null");
		Assert.isTrue("http".equalsIgnoreCase(endpointUri.getScheme())
				|| "https".equalsIgnoreCase(endpointUri.getScheme()),
				"The endpointUri must use http or https");

		this.jsonMapper = jsonMapper;
		this.requests = new StreamableHttpRequests(endpointUri, bundle, options);
		this.routes = new StreamableHttpRoutes(jsonMapper);
		this.streams = new StreamableHttpStreams(requests, routes, jsonMapper, options.maxSseStreams(),
				new StreamableHttpStreams.Owner(this::processInbound, closing::get, this::terminateAfterSseFailure,
						error -> this.exceptionHandler.accept(error), this::answerUnreadable));
		this.inbound = new StreamableHttpInbound(routes, streams, jsonMapper);
	}

	private static HttpClientBundle createDefaultHttpClient(StreamableHttpAcpClientTransportOptions options) {
		Assert.notNull(options, "The transport options can not be null");
		return HttpClientBundle.createDefault(options);
	}

	/**
	 * {@inheritDoc}
	 * <p>It contacts nothing: it registers the handler and completes at once. The connection
	 * opens when the {@code initialize} request is sent. A second call fails with an
	 * {@link IllegalStateException}.
	 */
	@Override
	public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		Assert.notNull(handler, "The handler can not be null");
		if (!connected.compareAndSet(false, true)) {
			return Mono.error(new IllegalStateException("Already connected"));
		}
		inbound.messages().flatMap(message -> Mono.just(message).transform(handler)).subscribe();
		return Mono.empty();
	}

	/**
	 * {@inheritDoc}
	 * <p>The {@code initialize} request opens the connection: its Mono completes once the
	 * answer has been read and the connection's SSE stream is open, and a second
	 * {@code initialize} fails with an {@link IllegalStateException}. Every other message is
	 * posted on the connection, or on its ACP session's stream, and its Mono completes when
	 * the server has accepted the POST. Sent before {@code initialize}, or once the transport
	 * is closing, a message fails with an {@link AcpConnectionException}.
	 */
	@Override
	public Mono<Void> sendMessage(JSONRPCMessage message) {
		Assert.notNull(message, "The message can not be null");
		if (closing.get()) {
			return Mono.error(new AcpConnectionException("Transport is closing"));
		}

		if (message instanceof AcpSchema.JSONRPCRequest request
				&& AcpSchema.METHOD_INITIALIZE.equals(request.method())) {
			return initialize(request);
		}

		return routeAndPost(message);
	}

	private Mono<Void> initialize(AcpSchema.JSONRPCRequest request) {
		if (!initialized.compareAndSet(false, true)) {
			return Mono.error(new IllegalStateException("Transport is already initialized"));
		}

		HttpRequest httpRequest;
		try {
			httpRequest = requests.jsonPost(RouteScope.bootstrap(), jsonMapper.writeValueAsString(request));
		}
		catch (IOException e) {
			initialized.set(false);
			return Mono.error(new AcpConnectionException("Failed to serialize initialize request", e));
		}

		return requests.upgradeCleartextToHttp2()
			.then(Mono.defer(() -> requests.sendAsync(requests.pinned(httpRequest), HttpResponse.BodyHandlers.ofString())))
			.flatMap(response -> readInitializeResponse(request, response))
			.flatMap(responseMessage -> streams.openConnectionStream().then(inbound.emit(responseMessage)))
			.doOnError(error -> {
				initialized.set(false);
				exceptionHandler.accept(error);
			});
	}

	/**
	 * Checks the answer to {@code initialize} and records the connection id it assigns.
	 * Emits the JSON-RPC response it carries.
	 */
	private Mono<JSONRPCMessage> readInitializeResponse(AcpSchema.JSONRPCRequest request,
			HttpResponse<String> response) {
		return StreamableHttpRequests.expectStatus(response, 200, "for initialize")
			.then(StreamableHttpRequests.expectContentType(response, StreamableHttpRequests.CONTENT_TYPE_JSON,
					"initialize response"))
			.then(Mono.fromCallable(() -> deserializeInitializeResponse(request, response.body())))
			.doOnNext(ignored -> requests.connectionId(response.headers()
				.firstValue(StreamableHttpRequests.HEADER_CONNECTION_ID)
				.orElseThrow(() -> new AcpConnectionException(
						"Initialize response missing " + StreamableHttpRequests.HEADER_CONNECTION_ID))));
	}

	private JSONRPCMessage deserializeInitializeResponse(AcpSchema.JSONRPCRequest request, String body) {
		JSONRPCMessage responseMessage;
		try {
			responseMessage = AcpSchema.deserializeJsonRpcMessage(jsonMapper, body);
		}
		catch (Exception e) {
			throw new AcpConnectionException("Failed to deserialize initialize response", e);
		}
		if (!(responseMessage instanceof AcpSchema.JSONRPCResponse initializeResponse)) {
			throw new AcpConnectionException("ACP initialize response was not a JSON-RPC response");
		}
		if (!Objects.equals(request.id(), initializeResponse.id())) {
			throw new AcpConnectionException("ACP initialize response id did not match initialize request");
		}
		return responseMessage;
	}

	private Mono<Void> routeAndPost(JSONRPCMessage message) {
		return Mono.defer(() -> {
			RouteScope scope = routes.resolveOutbound(message);
			return streams.prepare(message, scope)
				.flatMap(postScope -> {
					routes.postedIn(message, postScope);
					return post(message, postScope);
				})
				.doOnSuccess(ignored -> routes.posted(message))
				.doOnError(error -> routes.postFailed(message));
		});
	}

	private Mono<Void> post(JSONRPCMessage message, RouteScope scope) {
		String json;
		try {
			json = jsonMapper.writeValueAsString(message);
		}
		catch (IOException e) {
			return Mono.error(new AcpConnectionException("Failed to serialize outbound message", e));
		}
		return requests.postAccepted(scope, json);
	}

	/**
	 * Answers an SSE event that is no JSON-RPC message, as JSON-RPC 2.0 says (-32700 or
	 * -32600, with the request's id when it can be read) and as the stdio and WebSocket
	 * transports do, in the scope of the stream it came on. The answer names no routed
	 * request, so it is posted directly.
	 */
	private void answerUnreadable(RouteScope scope, String data) {
		if (closing.get()) {
			return;
		}
		post(AcpSchema.unreadableMessageResponse(jsonMapper, data), scope).subscribe(ignored -> {
		}, error -> logger.debug("Could not answer an unreadable SSE event: {}", error.getMessage()));
	}

	private Mono<Void> processInbound(RouteScope actualScope, JSONRPCMessage message) {
		return inbound.process(actualScope, message);
	}

	/**
	 * {@inheritDoc}
	 * <p>Closes the SSE streams, then sends {@code DELETE} for the connection, when one was
	 * opened, and waits at most five seconds for the answer; a server that does not answer
	 * releases the connection by itself. Completes {@link #awaitTermination()} first. Only the
	 * first call has an effect.
	 */
	@Override
	public Mono<Void> closeGracefully() {
		return Mono.defer(() -> {
			if (!closing.compareAndSet(false, true)) {
				return Mono.empty();
			}
			terminationSink.tryEmitEmpty();
			streams.closeAll();
			return deleteConnection().doFinally(signal -> clearState());
		});
	}

	private Mono<Void> deleteConnection() {
		String connectionId = requests.connectionId();
		if (connectionId == null) {
			return Mono.empty();
		}
		return requests.deleteConnection(connectionId)
			// A dead or unresponsive server must not hang shutdown; the connection is
			// released server-side by its own close or idle handling.
			.timeout(StreamableHttpRequests.PROBE_TIMEOUT, AcpSchedulers.timeouts())
			.onErrorResume(error -> {
				logger.debug("DELETE of connection {} did not complete: {}", connectionId, error.getMessage());
				return Mono.empty();
			});
	}

	/**
	 * Closes the transport as {@link #closeGracefully()} does, blocking the calling thread for
	 * up to ten seconds; if the close has not finished by then, it throws an
	 * {@link IllegalStateException}.
	 */
	@Override
	public void close() {
		closeGracefully().block(CLOSE_TIMEOUT);
	}

	private void clearState() {
		streams.clear();
		routes.clear();
		inbound.complete();
		requests.shutdown();
	}

	private void terminateAfterSseFailure(Throwable error) {
		if (!closing.compareAndSet(false, true)) {
			return;
		}
		clearState();
		exceptionHandler.accept(error);
		terminationSink.tryEmitError(error);
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
		return jsonMapper.convertValue(data, typeRef);
	}

}

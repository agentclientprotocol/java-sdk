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
 * Client-side ACP transport for the Streamable HTTP profile.
 *
 * <p>
 * Streamable HTTP maps ACP's logical duplex conversation onto HTTP POST requests plus
 * long-lived Server-Sent Event (SSE) streams. The transport keeps all HTTP-specific
 * routing state internal so the higher-level ACP session can continue to operate only on
 * JSON-RPC messages.
 * </p>
 *
 * <p>
 * This class owns the transport's lifecycle (connect, initialize, send, close) and
 * delegates the rest: {@link StreamableHttpRequests} the HTTP exchanges,
 * {@link StreamableHttpRoutes} the routing of each message to an HTTP scope,
 * {@link StreamableHttpStreams} the SSE streams and their reconnection, and
 * {@link StreamableHttpInbound} the ordered delivery of what the streams read.
 * </p>
 *
 * @author Kaiser Dandangi
 */
public class StreamableHttpAcpClientTransport implements AcpClientTransport {

	private static final Logger logger = LoggerFactory.getLogger(StreamableHttpAcpClientTransport.class);

	/** Default ACP path used by the remote transport RFD. */
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
	 * Creates a new Streamable HTTP client transport using a default JDK {@link HttpClient}
	 * configured with an internal {@link CookieManager}.
	 * @param endpointUri the remote ACP endpoint URI
	 * @param jsonMapper JSON mapper used for message serialization
	 */
	public StreamableHttpAcpClientTransport(URI endpointUri, AcpJsonMapper jsonMapper) {
		this(endpointUri, jsonMapper, StreamableHttpAcpClientTransportOptions.defaults());
	}

	/**
	 * Creates a new Streamable HTTP client transport with explicit resource limits.
	 * @param endpointUri the remote ACP endpoint URI
	 * @param jsonMapper JSON mapper used for message serialization
	 * @param options resource limits for this transport
	 */
	public StreamableHttpAcpClientTransport(URI endpointUri, AcpJsonMapper jsonMapper,
			StreamableHttpAcpClientTransportOptions options) {
		this(endpointUri, jsonMapper, createDefaultHttpClient(options), options);
	}

	/**
	 * Creates a new Streamable HTTP client transport using a caller-provided
	 * {@link HttpClient}. This allows advanced callers to customize cookies, TLS,
	 * executors, or proxy behavior.
	 * @param endpointUri the remote ACP endpoint URI
	 * @param jsonMapper JSON mapper used for message serialization
	 * @param httpClient HTTP client to use for requests
	 */
	public StreamableHttpAcpClientTransport(URI endpointUri, AcpJsonMapper jsonMapper, HttpClient httpClient) {
		this(endpointUri, jsonMapper, httpClient, StreamableHttpAcpClientTransportOptions.defaults());
	}

	/**
	 * Creates a new Streamable HTTP client transport with a caller-provided HTTP client and
	 * explicit resource limits.
	 * @param endpointUri the remote ACP endpoint URI
	 * @param jsonMapper JSON mapper used for message serialization
	 * @param httpClient HTTP client to use for requests
	 * @param options resource limits for this transport
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

	@Override
	public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		Assert.notNull(handler, "The handler can not be null");
		if (!connected.compareAndSet(false, true)) {
			return Mono.error(new IllegalStateException("Already connected"));
		}
		inbound.messages().flatMap(message -> Mono.just(message).transform(handler)).subscribe();
		return Mono.empty();
	}

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

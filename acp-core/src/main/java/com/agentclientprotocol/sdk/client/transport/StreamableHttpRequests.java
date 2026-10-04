/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.io.InputStream;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.util.AcpSchedulers;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * The HTTP exchanges of a Streamable HTTP client connection: the scope headers, the HTTP
 * version settled by the cleartext probe, the application's request customizer, and the
 * POST, GET and DELETE requests with the status and content type each must answer with. Completion signals are delivered on a
 * bounded executor of their own, never on the HTTP client's.
 */
final class StreamableHttpRequests {

	private static final Logger logger = LoggerFactory.getLogger(StreamableHttpRequests.class);

	static final String HEADER_CONNECTION_ID = "Acp-Connection-Id";

	static final String HEADER_SESSION_ID = "Acp-Session-Id";

	static final String CONTENT_TYPE_JSON = "application/json";

	static final String CONTENT_TYPE_EVENT_STREAM = "text/event-stream";

	static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

	/**
	 * The headers this transport owns, lower-cased. A request customizer's values for them are
	 * dropped, including on the bootstrap {@code initialize}, which carries no connection id
	 * of the transport's own for one set by the customizer to be replaced by.
	 */
	private static final Set<String> PROTOCOL_HEADERS = Set.of("content-type", "accept",
			HEADER_CONNECTION_ID.toLowerCase(Locale.ROOT), HEADER_SESSION_ID.toLowerCase(Locale.ROOT));

	/** An HTTP client and the executor this transport created for it, if any. */
	record HttpClientBundle(HttpClient httpClient, @Nullable ExecutorService ownedExecutor) {

		/** The default JDK client: HTTP/2, an internal cookie manager, a bounded executor. */
		static HttpClientBundle createDefault(StreamableHttpAcpClientTransportOptions options) {
			ExecutorService executor = boundedExecutor(options.httpWorkerThreads(), options.httpQueueCapacity(),
					"acp-streamable-http-client");
			HttpClient client = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_2)
				.cookieHandler(new CookieManager())
				.executor(executor)
				.build();
			return new HttpClientBundle(client, executor);
		}

	}

	private final URI endpointUri;

	private final HttpClient httpClient;

	private final @Nullable ExecutorService ownedHttpExecutor;

	private final ExecutorService httpSignalExecutor;

	private volatile @Nullable String connectionId;

	private volatile Consumer<HttpRequest.Builder> requestCustomizer = builder -> {
	};

	/**
	 * HTTP version pinned for every request after the cleartext probe, or {@code null} to
	 * use the client's own setting. Set to HTTP/1.1 when an {@code http://} server does not
	 * speak h2c, so later requests stop carrying {@code Upgrade: h2c}: some servers hand any
	 * request with an Upgrade header to their WebSocket handler and answer 405 (the
	 * TypeScript SDK's example server does).
	 */
	private volatile HttpClient.@Nullable Version pinnedVersion;

	StreamableHttpRequests(URI endpointUri, HttpClientBundle bundle, StreamableHttpAcpClientTransportOptions options) {
		this.endpointUri = endpointUri;
		this.httpClient = bundle.httpClient();
		this.ownedHttpExecutor = bundle.ownedExecutor();
		this.httpSignalExecutor = boundedExecutor(options.httpSignalThreads(), options.httpQueueCapacity(),
				"acp-streamable-http-signal");
	}

	static ExecutorService boundedExecutor(int threads, int queueCapacity, String threadName) {
		return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(queueCapacity), daemonThreadFactory(threadName),
				new ThreadPoolExecutor.AbortPolicy());
	}

	static ThreadFactory daemonThreadFactory(String threadName) {
		return runnable -> {
			Thread thread = new Thread(runnable, threadName);
			thread.setDaemon(true);
			return thread;
		};
	}

	void requestCustomizer(Consumer<HttpRequest.Builder> requestCustomizer) {
		this.requestCustomizer = requestCustomizer;
	}

	@Nullable String connectionId() {
		return connectionId;
	}

	void connectionId(@Nullable String connectionId) {
		this.connectionId = connectionId;
	}

	/**
	 * The transport requires HTTP/2 (RFD). Over {@code https} ALPN negotiates it. Over
	 * cleartext {@code http} the JDK offers an h2c upgrade on every request, but servers
	 * (Jetty among them) only honour it on a request without a body, and {@code initialize}
	 * is a POST. A bodiless GET first upgrades the connection when the server speaks
	 * h2c; every later request reuses it over HTTP/2. When it does not (answer on HTTP/1.1,
	 * an error, or no answer within five seconds), every later request is pinned to HTTP/1.1.
	 * A GET rather than OPTIONS because some h2c servers (Hypercorn) upgrade a GET but answer
	 * OPTIONS with 405 on HTTP/1.1. It carries no connection id, so servers answer it with a
	 * 4xx without opening a stream; only the negotiated version is used.
	 */
	Mono<Void> upgradeCleartextToHttp2() {
		if (!"http".equalsIgnoreCase(endpointUri.getScheme()) || httpClient.version() != HttpClient.Version.HTTP_2) {
			return Mono.empty();
		}
		// Customized like every other request: a gateway in front of the agent may refuse the
		// probe without the application's credentials. Cancelling the Mono on timeout cancels
		// the HTTP exchange (see sendAsync).
		return Mono.defer(() -> sendAsync(customized().GET().build(), HttpResponse.BodyHandlers.discarding()))
			.timeout(PROBE_TIMEOUT, AcpSchedulers.timeouts())
			.doOnNext(response -> {
				logger.debug("Cleartext probe to {} negotiated {}", endpointUri, response.version());
				if (response.version() != HttpClient.Version.HTTP_2) {
					this.pinnedVersion = HttpClient.Version.HTTP_1_1;
				}
			})
			.then()
			.onErrorResume(error -> {
				logger.debug("Cleartext HTTP/2 probe to {} failed ({}); using HTTP/1.1", endpointUri,
						error.getMessage());
				this.pinnedVersion = HttpClient.Version.HTTP_1_1;
				return Mono.empty();
			});
	}

	/** A customized builder for the endpoint, with the version the probe settled on. */
	private HttpRequest.Builder newRequest() {
		HttpRequest.Builder requestBuilder = customized();
		HttpClient.Version version = this.pinnedVersion;
		if (version != null) {
			requestBuilder.version(version);
		}
		return requestBuilder;
	}

	/**
	 * A builder for the endpoint carrying what the application's customizer set, less the
	 * protocol headers and with the endpoint's URI whatever the customizer did. The
	 * customizer runs on a builder of its own and the result is copied, because a builder
	 * can replace a header but never remove one. The transport sets the method, the body and
	 * its own headers afterwards.
	 */
	private HttpRequest.Builder customized() {
		HttpRequest.Builder scratch = HttpRequest.newBuilder(endpointUri);
		requestCustomizer.accept(scratch);
		return HttpRequest
			.newBuilder(scratch.build(), (name, value) -> !PROTOCOL_HEADERS.contains(name.toLowerCase(Locale.ROOT)))
			.uri(endpointUri);
	}

	/**
	 * A JSON POST of {@code json} in {@code scope}, not yet sent. Builds the request, so it
	 * throws whatever the request customizer throws; the Monos below build inside
	 * {@code defer} and fail with it instead.
	 */
	HttpRequest jsonPost(RouteScope scope, String json) {
		HttpRequest.Builder builder = newRequest().setHeader("Content-Type", CONTENT_TYPE_JSON)
			.setHeader("Accept", CONTENT_TYPE_JSON);
		addScopeHeaders(builder, scope);
		return builder.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
	}

	/** Posts a message that the server must accept with 202. */
	Mono<Void> postAccepted(RouteScope scope, String json) {
		return Mono.defer(() -> sendAsync(jsonPost(scope, json), HttpResponse.BodyHandlers.discarding()))
			.flatMap(response -> expectStatus(response, 202, "for POST"));
	}

	/** Opens the SSE stream of {@code scope}; emits its body or an error, never completes empty. */
	Mono<InputStream> openEventStream(RouteScope scope) {
		return Mono.defer(() -> {
			HttpRequest.Builder builder = newRequest().GET().setHeader("Accept", CONTENT_TYPE_EVENT_STREAM);
			addScopeHeaders(builder, scope);
			return sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
		})
			.flatMap(response -> expectStatus(response, 200, "when opening SSE stream")
				.then(expectContentType(response, CONTENT_TYPE_EVENT_STREAM, "response"))
				.then(Mono.fromSupplier(response::body)));
	}

	/** Deletes the connection; the server must accept with 202. */
	Mono<Void> deleteConnection(String id) {
		return Mono
			.defer(() -> sendAsync(newRequest().DELETE().setHeader(HEADER_CONNECTION_ID, id).build(),
					HttpResponse.BodyHandlers.discarding()))
			.flatMap(response -> expectStatus(response, 202, "for DELETE"));
	}

	static Mono<Void> expectStatus(HttpResponse<?> response, int status, String what) {
		if (response.statusCode() != status) {
			return Mono.error(new AcpConnectionException(
					"Expected " + status + " " + what + ", got " + response.statusCode()));
		}
		return Mono.empty();
	}

	static Mono<Void> expectContentType(HttpResponse<?> response, String expected, String what) {
		String contentType = response.headers().firstValue("Content-Type").orElse("");
		if (!contentType.toLowerCase(Locale.ROOT).contains(expected)) {
			return Mono.error(new AcpConnectionException("Expected " + expected + " " + what + ", got " + contentType));
		}
		return Mono.empty();
	}

	private void addScopeHeaders(HttpRequest.Builder builder, RouteScope scope) {
		if (!scope.isBootstrap()) {
			builder.setHeader(HEADER_CONNECTION_ID, requireConnectionId());
		}
		if (scope.isSession()) {
			builder.setHeader(HEADER_SESSION_ID, scope.boundSessionId());
		}
	}

	private String requireConnectionId() {
		String currentConnectionId = this.connectionId;
		if (currentConnectionId == null || currentConnectionId.isBlank()) {
			throw new AcpConnectionException("Missing " + HEADER_CONNECTION_ID);
		}
		return currentConnectionId;
	}

	<T> Mono<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler) {
		return Mono.create(sink -> {
			CompletableFuture<HttpResponse<T>> future = httpClient.sendAsync(request, bodyHandler);
			sink.onCancel(() -> future.cancel(true));
			try {
				future.whenCompleteAsync((response, error) -> {
					if (error != null) {
						sink.error(error);
					}
					else {
						sink.success(response);
					}
				}, httpSignalExecutor).exceptionally(error -> {
					// The signal executor rejected the completion callback: the HTTP call
					// finished but nobody would have told the caller.
					sink.error(error);
					return null;
				});
			}
			catch (RejectedExecutionException e) {
				future.cancel(true);
				sink.error(e);
			}
		});
	}

	/** Releases the executors this transport owns. */
	void shutdown() {
		connectionId = null;
		httpSignalExecutor.shutdownNow();
		if (ownedHttpExecutor != null) {
			ownedHttpExecutor.shutdownNow();
		}
	}

}

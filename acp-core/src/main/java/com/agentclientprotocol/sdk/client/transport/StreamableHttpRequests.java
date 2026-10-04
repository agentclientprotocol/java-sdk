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
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.util.AcpSchedulers;
import com.agentclientprotocol.sdk.util.VirtualThreads;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * The HTTP exchanges of a Streamable HTTP client connection: the scope headers, the HTTP
 * version settled by the cleartext probe, and the POST, GET and DELETE requests with the
 * status and content type each must answer with. Completion signals are delivered on the
 * transport's work executor (the application's, or virtual threads on JDK 21 and later), else
 * on a bounded executor of their own rather than the HTTP client's.
 */
final class StreamableHttpRequests {

	private static final Logger logger = LoggerFactory.getLogger(StreamableHttpRequests.class);

	static final String HEADER_CONNECTION_ID = "Acp-Connection-Id";

	static final String HEADER_SESSION_ID = "Acp-Session-Id";

	static final String CONTENT_TYPE_JSON = "application/json";

	static final String CONTENT_TYPE_EVENT_STREAM = "text/event-stream";

	static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

	/**
	 * An HTTP client, the executor the transport's own work runs on, and the executor this
	 * transport created and so shuts down, if any.
	 * @param httpClient the client the requests are sent with
	 * @param ownedExecutor the executor the transport created, shut down when it closes
	 * @param workExecutor what hands over HTTP results and reads the SSE streams: the
	 * application's executor, or a virtual-thread executor of the transport's own; null for
	 * the transport's bounded platform pools (JDK 17)
	 */
	record HttpClientBundle(HttpClient httpClient, @Nullable ExecutorService ownedExecutor,
			@Nullable Executor workExecutor) {

		/**
		 * The default JDK client: HTTP/2, an internal cookie manager, and the options'
		 * executor; without one, a virtual thread per task on JDK 21 and later, else a
		 * bounded pool of daemon threads.
		 */
		static HttpClientBundle createDefault(StreamableHttpAcpClientTransportOptions options) {
			Executor supplied = options.executor();
			ExecutorService virtual = (supplied != null) ? null : ownVirtualExecutor(options);
			Executor work = (supplied != null) ? supplied : virtual;
			ExecutorService owned = (work != null) ? virtual : boundedExecutor(options.httpWorkerThreads(),
					options.httpQueueCapacity(), "acp-streamable-http-client");
			HttpClient client = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_2)
				.cookieHandler(new CookieManager())
				.executor((work != null) ? work : Objects.requireNonNull(owned))
				.build();
			return new HttpClientBundle(client, owned, work);
		}

		/**
		 * The application's client, with the transport's own work on the options' executor;
		 * without one, a virtual thread per task on JDK 21 and later, else bounded pools.
		 */
		static HttpClientBundle of(HttpClient httpClient, StreamableHttpAcpClientTransportOptions options) {
			Executor supplied = options.executor();
			ExecutorService virtual = (supplied != null) ? null : ownVirtualExecutor(options);
			return new HttpClientBundle(httpClient, virtual, (supplied != null) ? supplied : virtual);
		}

	}

	/** A virtual thread per task, unless the options say platform threads or the JDK has none. */
	private static @Nullable ExecutorService ownVirtualExecutor(StreamableHttpAcpClientTransportOptions options) {
		return options.virtualThreads() ? VirtualThreads.newPerTaskExecutor("acp-streamable-http") : null;
	}

	private final URI endpointUri;

	private final HttpClient httpClient;

	private final @Nullable ExecutorService ownedHttpExecutor;

	/** Delivers the results of HTTP calls: the options' executor, or a bounded pool of our own. */
	private final Executor httpSignalExecutor;

	private final @Nullable ExecutorService ownedSignalExecutor;

	private volatile @Nullable String connectionId;

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
		Executor work = bundle.workExecutor();
		this.ownedSignalExecutor = (work != null) ? null
				: boundedExecutor(options.httpSignalThreads(), options.httpQueueCapacity(), "acp-streamable-http-signal");
		this.httpSignalExecutor = (work != null) ? work : Objects.requireNonNull(this.ownedSignalExecutor);
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
		HttpRequest probe = HttpRequest.newBuilder(endpointUri).GET().build();
		// Cancelling the Mono on timeout cancels the HTTP exchange (see sendAsync).
		return sendAsync(probe, HttpResponse.BodyHandlers.discarding())
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

	/** Re-stamps a request built before the probe with the version the probe settled on. */
	HttpRequest pinned(HttpRequest request) {
		HttpClient.Version version = this.pinnedVersion;
		if (version == null) {
			return request;
		}
		return HttpRequest.newBuilder(request, (name, value) -> true).version(version).build();
	}

	private HttpRequest.Builder newRequest() {
		HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpointUri);
		HttpClient.Version version = this.pinnedVersion;
		if (version != null) {
			requestBuilder.version(version);
		}
		return requestBuilder;
	}

	/** A JSON POST of {@code json} in {@code scope}, not yet sent. */
	HttpRequest jsonPost(RouteScope scope, String json) {
		HttpRequest.Builder builder = newRequest().header("Content-Type", CONTENT_TYPE_JSON)
			.header("Accept", CONTENT_TYPE_JSON);
		addScopeHeaders(builder, scope);
		return builder.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
	}

	/** Posts a message that the server must accept with 202. */
	Mono<Void> postAccepted(RouteScope scope, String json) {
		return sendAsync(jsonPost(scope, json), HttpResponse.BodyHandlers.discarding())
			.flatMap(response -> expectStatus(response, 202, "for POST"));
	}

	/** Opens the SSE stream of {@code scope}; emits its body or an error, never completes empty. */
	Mono<InputStream> openEventStream(RouteScope scope) {
		HttpRequest.Builder builder = newRequest().GET().header("Accept", CONTENT_TYPE_EVENT_STREAM);
		addScopeHeaders(builder, scope);
		return sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
			.flatMap(response -> expectStatus(response, 200, "when opening SSE stream")
				.then(expectContentType(response, CONTENT_TYPE_EVENT_STREAM, "response"))
				.then(Mono.fromSupplier(response::body)));
	}

	/** Deletes the connection; the server must accept with 202. */
	Mono<Void> deleteConnection(String id) {
		HttpRequest request = newRequest().DELETE().header(HEADER_CONNECTION_ID, id).build();
		return sendAsync(request, HttpResponse.BodyHandlers.discarding())
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
			builder.header(HEADER_CONNECTION_ID, requireConnectionId());
		}
		if (scope.isSession()) {
			builder.header(HEADER_SESSION_ID, scope.boundSessionId());
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

	/** Releases the executors this transport owns; never the application's. */
	void shutdown() {
		connectionId = null;
		if (ownedSignalExecutor != null) {
			ownedSignalExecutor.shutdownNow();
		}
		if (ownedHttpExecutor != null) {
			ownedHttpExecutor.shutdownNow();
		}
	}

}

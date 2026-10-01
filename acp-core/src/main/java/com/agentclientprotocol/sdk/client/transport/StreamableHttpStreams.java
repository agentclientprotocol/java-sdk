/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.agentclientprotocol.sdk.error.AcpConnectionException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import com.agentclientprotocol.sdk.util.AcpSchedulers;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * The SSE streams of a Streamable HTTP client connection: one connection stream and one
 * stream per session. Opens each at most once at a time, reconnects a stream the server
 * or the network closed, and gives up on one that keeps failing.
 */
final class StreamableHttpStreams {

	private static final Logger logger = LoggerFactory.getLogger(StreamableHttpStreams.class);

	/** Reconnects in a row that delivered nothing before the transport gives up on a stream. */
	private static final int MAX_BARREN_RECONNECTS = 3;

	private static final Duration RECONNECT_BACKOFF = Duration.ofMillis(200);

	private final StreamableHttpRequests requests;

	private final StreamableHttpRoutes routes;

	private final AcpJsonMapper jsonMapper;

	private final ExecutorService sseExecutor;

	private final int maxSseStreams;

	private final SseStream.Listener listener;

	private final Consumer<Throwable> onFailure;

	private final Map<String, SseStream> sessionStreams = new ConcurrentHashMap<>();

	// Session id -> shared open operation so callers reuse one GET while opening.
	private final Map<String, Mono<Void>> sessionStreamOpenOperations = new ConcurrentHashMap<>();

	private final AtomicReference<@Nullable SseStream> connectionStream = new AtomicReference<>();

	/**
	 * What the streams need from the transport that owns them.
	 * @param inbound delivers each message read from a stream
	 * @param closing whether the transport is closing
	 * @param onFailure ends the transport after a stream failure it cannot recover from
	 * @param onError reports an error the streams recover from, such as a skipped event
	 */
	record Owner(BiFunction<RouteScope, JSONRPCMessage, Mono<Void>> inbound, BooleanSupplier closing,
			Consumer<Throwable> onFailure, Consumer<Throwable> onError) {
	}

	StreamableHttpStreams(StreamableHttpRequests requests, StreamableHttpRoutes routes, AcpJsonMapper jsonMapper,
			int maxSseStreams, Owner owner) {
		this.requests = requests;
		this.routes = routes;
		this.jsonMapper = jsonMapper;
		this.maxSseStreams = maxSseStreams;
		this.sseExecutor = new ThreadPoolExecutor(maxSseStreams, maxSseStreams, 0, TimeUnit.MILLISECONDS,
				new SynchronousQueue<>(), StreamableHttpRequests.daemonThreadFactory("acp-streamable-http-sse"),
				new ThreadPoolExecutor.AbortPolicy());
		this.onFailure = owner.onFailure();
		this.listener = new SseStream.Listener() {

			@Override
			public Mono<Void> onMessage(RouteScope scope, JSONRPCMessage message) {
				return owner.inbound().apply(scope, message);
			}

			@Override
			public void onUnreadable(Throwable error) {
				owner.onError().accept(error);
			}

			@Override
			public void onUnexpectedClose(SseStream stream, Throwable error) {
				handleUnexpectedClosure(stream, error);
			}

			@Override
			public boolean transportClosing() {
				return owner.closing().getAsBoolean();
			}

		};
	}

	Mono<Void> openConnectionStream() {
		return open(RouteScope.connection()).doOnNext(stream -> {
			this.connectionStream.set(stream);
			start(stream);
		}).then();
	}

	/**
	 * Opens the session stream a message posted in {@code scope} needs, unless it is open.
	 */
	Mono<Void> prepare(JSONRPCMessage message, RouteScope scope) {
		if (message instanceof AcpSchema.JSONRPCRequest request
				&& AcpSchema.METHOD_SESSION_LOAD.equals(request.method())) {
			// Open the session stream first (the RFD's reconnect order) unless it is already
			// open: servers allow one receiver per stream, and the TypeScript and Rust servers
			// answer a second GET with 409.
			SseStream existing = sessionStreams.get(scope.boundSessionId());
			if (existing != null && !existing.isClosed()) {
				return Mono.empty();
			}
			return openSessionStream(scope.boundSessionId());
		}
		if (scope.isSession() && !sessionStreams.containsKey(scope.boundSessionId())) {
			return openSessionStream(scope.boundSessionId());
		}
		return Mono.empty();
	}

	Mono<Void> openSessionStream(String sessionId) {
		return sessionStreamOpenOperations.computeIfAbsent(sessionId, this::createSessionStreamOpenMono);
	}

	private Mono<Void> createSessionStreamOpenMono(String sessionId) {
		AtomicReference<@Nullable Mono<Void>> operation = new AtomicReference<>();
		Mono<Void> openOperation = open(RouteScope.session(sessionId))
			.doOnNext(stream -> register(sessionId, stream))
			.then()
			.doFinally(signal -> sessionStreamOpenOperations.remove(sessionId, operation.get()))
			.cache();
		operation.set(openOperation);
		return openOperation;
	}

	/** Starts a newly opened session stream, or closes it when another one won the race. */
	private void register(String sessionId, SseStream stream) {
		SseStream existing = sessionStreams.putIfAbsent(sessionId, stream);
		if (existing != null) {
			stream.close();
			return;
		}
		try {
			start(stream);
		}
		catch (RuntimeException e) {
			// Otherwise a later request for this session would skip the reopen and post into
			// a stream that never reads.
			sessionStreams.remove(sessionId, stream);
			throw e;
		}
	}

	/** Emits the opened stream or an error; it never completes empty. */
	private Mono<SseStream> open(RouteScope scope) {
		return requests.openEventStream(scope).map(body -> new SseStream(scope, body, jsonMapper, listener));
	}

	private void start(SseStream stream) {
		stream.start(sseExecutor, maxSseStreams);
	}

	/**
	 * A stream closed that this client did not close: the server detached it (backpressure,
	 * a restart of the proxy in between), or the network dropped it. The server keeps what
	 * it has not delivered in the stream's mailbox, so reopening loses nothing: reconnect
	 * with a short backoff. Give up, as before, when the server answers that the connection
	 * is gone, or after {@value #MAX_BARREN_RECONNECTS} reconnects in a row that delivered no
	 * event (a server that keeps closing must not cause an endless loop).
	 */
	private void handleUnexpectedClosure(SseStream stream, Throwable error) {
		if (listener.transportClosing()) {
			return;
		}
		RouteScope scope = stream.scope();
		int barren = stream.delivered() ? 0 : stream.barrenReconnects() + 1;
		if (barren > MAX_BARREN_RECONNECTS) {
			giveUpOn(stream, error);
			return;
		}
		logger.info("SSE stream closed unexpectedly; reconnecting: {}", scope);
		Mono.defer(() -> open(scope))
			.retryWhen(Retry.backoff(2, RECONNECT_BACKOFF)
				.scheduler(AcpSchedulers.timeouts())
				.filter(e -> !isConnectionGone(e)))
			.subscribe(reopened -> {
				reopened.barrenReconnects(barren);
				if (listener.transportClosing() || !replace(stream, reopened)) {
					reopened.close();
					return;
				}
				start(reopened);
				logger.info("SSE stream reconnected: {}", scope);
			}, reconnectError -> giveUpOn(stream, reconnectError));
	}

	private boolean replace(SseStream old, SseStream reopened) {
		if (old.scope().isSession()) {
			return sessionStreams.replace(old.scope().boundSessionId(), old, reopened);
		}
		return connectionStream.compareAndSet(old, reopened);
	}

	/** 404 on reconnect: the server no longer knows this connection or session. */
	private static boolean isConnectionGone(Throwable error) {
		// AcpException.getMessage() is non-null, unlike Throwable's.
		return error instanceof AcpConnectionException connectionError
				&& connectionError.getMessage().contains("got 404");
	}

	/**
	 * A dead connection stream, or a session stream owing a response, ends the transport; any
	 * other session stream is dropped and reopened before the next session request.
	 */
	private void giveUpOn(SseStream stream, Throwable error) {
		if (listener.transportClosing()) {
			return;
		}
		RouteScope scope = stream.scope();
		if (!scope.isSession() || routes.hasPendingResponseFor(scope)) {
			onFailure.accept(error);
			return;
		}
		if (sessionStreams.remove(scope.boundSessionId(), stream)) {
			sessionStreamOpenOperations.remove(scope.boundSessionId());
			logger.info("Session SSE stream closed; it will be reopened before the next session request: {}", scope);
		}
	}

	/** Closes every open stream; they stay registered until {@link #clear()}. */
	void closeAll() {
		Optional.ofNullable(connectionStream.get()).ifPresent(SseStream::close);
		sessionStreams.values().forEach(SseStream::close);
	}

	/** Closes and forgets every stream and stops the reader threads. */
	void clear() {
		Optional.ofNullable(connectionStream.getAndSet(null)).ifPresent(SseStream::close);
		sessionStreams.values().forEach(SseStream::close);
		sessionStreams.clear();
		sessionStreamOpenOperations.clear();
		sseExecutor.shutdownNow();
	}

}

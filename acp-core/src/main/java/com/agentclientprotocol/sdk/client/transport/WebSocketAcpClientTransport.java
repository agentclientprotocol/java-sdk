/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import com.agentclientprotocol.sdk.util.Assert;
import com.agentclientprotocol.sdk.util.OutboundSinks;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Implementation of the ACP WebSocket transport for clients that communicates with an
 * agent using WebSocket connections. Uses the JDK 11+ {@link java.net.http.WebSocket} API.
 *
 * <p>
 * Messages are exchanged as JSON-RPC messages over WebSocket text frames.
 * </p>
 *
 * <p>
 * Key features:
 * <ul>
 * <li>Zero external dependencies (uses JDK built-in WebSocket)</li>
 * <li>Thread-safe message processing with dedicated schedulers</li>
 * <li>Proper resource management and graceful shutdown</li>
 * <li>Backpressure support via Reactor Sinks</li>
 * </ul>
 *
 * @author Mark Pollack
 */
public class WebSocketAcpClientTransport implements AcpClientTransport {

	private static final Logger logger = LoggerFactory.getLogger(WebSocketAcpClientTransport.class);

	/** Default path for ACP WebSocket endpoints */
	public static final String DEFAULT_ACP_PATH = "/acp";

	private final URI serverUri;

	private final AcpJsonMapper jsonMapper;

	private final HttpClient httpClient;

	private final Sinks.Many<JSONRPCMessage> inboundSink;

	private final Sinks.Many<JSONRPCMessage> outboundSink;

	private final Sinks.One<Void> connectionReady = Sinks.one();

	private final Sinks.One<Void> terminationSink = Sinks.one();

	/** Set once the WebSocket opens; null before connect. */
	private volatile @Nullable WebSocket webSocket;

	private final Scheduler outboundScheduler;

	private final AtomicBoolean isClosing = new AtomicBoolean(false);

	private final AtomicBoolean isConnected = new AtomicBoolean(false);

	/** Set by the first {@link #closeGracefully()}: only that call closes. */
	private final AtomicBoolean closeStarted = new AtomicBoolean(false);

	/** Completes once the first close has finished. */
	private final Sinks.Empty<Void> closed = Sinks.empty();

	private Consumer<Throwable> exceptionHandler = t -> logger.error("Transport error", t);

	private Duration connectTimeout = Duration.ofSeconds(30);

	/**
	 * Creates a new WebSocketAcpClientTransport with the specified server URI and JsonMapper.
	 * @param serverUri The WebSocket URI to connect to (e.g., "ws://localhost:8080/acp")
	 * @param jsonMapper The JsonMapper to use for JSON serialization/deserialization
	 */
	public WebSocketAcpClientTransport(URI serverUri, AcpJsonMapper jsonMapper) {
		this(serverUri, jsonMapper, HttpClient.newBuilder()
			.executor(Executors.newCachedThreadPool(r -> {
				Thread t = new Thread(r, "acp-ws-client");
				t.setDaemon(true);
				return t;
			}))
			.build());
	}

	/**
	 * Creates a new WebSocketAcpClientTransport with custom HttpClient.
	 * @param serverUri The WebSocket URI to connect to
	 * @param jsonMapper The JsonMapper to use for JSON serialization/deserialization
	 * @param httpClient The HttpClient to use for WebSocket connections
	 */
	public WebSocketAcpClientTransport(URI serverUri, AcpJsonMapper jsonMapper, HttpClient httpClient) {
		Assert.notNull(serverUri, "The serverUri can not be null");
		Assert.notNull(jsonMapper, "The JsonMapper can not be null");
		Assert.notNull(httpClient, "The HttpClient can not be null");

		this.serverUri = serverUri;
		this.jsonMapper = jsonMapper;
		this.httpClient = httpClient;

		this.inboundSink = Sinks.many().unicast().onBackpressureBuffer();
		this.outboundSink = Sinks.many().unicast().onBackpressureBuffer();
		// Use daemon thread so JVM can exit if closeGracefully() isn't called
		this.outboundScheduler = Schedulers.fromExecutorService(
			Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "acp-ws-client-outbound");
				t.setDaemon(true);
				return t;
			}), "ws-client-outbound");
	}

	/**
	 * Sets the connection timeout for WebSocket establishment.
	 * @param timeout The connection timeout
	 * @return This transport for chaining
	 */
	public WebSocketAcpClientTransport connectTimeout(Duration timeout) {
		this.connectTimeout = timeout;
		return this;
	}

	@Override
	public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		if (!isConnected.compareAndSet(false, true)) {
			return Mono.error(new IllegalStateException("Already connected"));
		}

		return Mono.fromFuture(() -> {
			logger.info("Connecting to WebSocket server at {}", serverUri);

			// Set up inbound message handling
			handleIncomingMessages(handler);

			// Build WebSocket connection with listener
			return httpClient.newWebSocketBuilder()
				.connectTimeout(connectTimeout)
				.buildAsync(serverUri, new AcpWebSocketListener());
		}).doOnSuccess(ws -> {
			this.webSocket = ws;
			startOutboundProcessing();
			connectionReady.tryEmitValue(null);
			logger.info("Connected to WebSocket server at {}", serverUri);
		}).doOnError(e -> {
			logger.error("Failed to connect to WebSocket server at {}", serverUri, e);
			isConnected.set(false);
			exceptionHandler.accept(e);
		}).doOnCancel(() -> {
			logger.debug("WebSocket connection cancelled");
			isConnected.set(false);
		}).then();
	}

	private void handleIncomingMessages(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		OutboundSinks.replyThrough(this.inboundSink, handler, this.outboundSink, () -> {
		});
	}

	private void startOutboundProcessing() {
		this.outboundSink.asFlux()
			.publishOn(outboundScheduler)
			.subscribe(message -> {
				WebSocket webSocket = this.webSocket;
				if (!isClosing.get() && webSocket != null) {
					try {
						String jsonMessage = jsonMapper.writeValueAsString(message);
						logger.debug("Sending WebSocket message ({} characters)", jsonMessage.length());
						webSocket.sendText(jsonMessage, true).join();
					}
					catch (Exception e) {
						if (!isClosing.get()) {
							logger.error("Error sending WebSocket message", e);
							exceptionHandler.accept(e);
						}
					}
				}
			});
	}

	@Override
	public Mono<Void> sendMessage(JSONRPCMessage message) {
		return connectionReady.asMono().then(Mono.defer(() -> {
			OutboundSinks.emit(outboundSink, message);
			return Mono.empty();
		}));
	}

	/**
	 * Closes the transport: completes the message streams, sends the WebSocket close frame
	 * and releases the outbound thread. Only the first call closes; every call, also one
	 * made while that close is still under way, completes when it has finished. A close
	 * frame that cannot be sent because the connection's output is already closed (the
	 * agent closed the connection first) does not fail the close.
	 * @return a Mono that completes when the transport is closed
	 */
	@Override
	public Mono<Void> closeGracefully() {
		return Mono.defer(() -> {
			if (this.closeStarted.compareAndSet(false, true)) {
				close(Mono.fromRunnable(this::stopMessages).then(Mono.defer(this::sendClose)));
			}
			return this.closed.asMono();
		});
	}

	private void close(Mono<Void> closing) {
		closing.doFinally(signal -> {
			try {
				outboundScheduler.dispose();
				logger.debug("WebSocket transport closed");
			}
			catch (Exception e) {
				logger.error("Error during graceful shutdown", e);
			}
			this.closed.tryEmitEmpty();
		}).subscribe(ignored -> {
		}, error -> logger.debug("WebSocket close frame not sent: {}", error.getMessage()));
	}

	private void stopMessages() {
		logger.debug("WebSocket transport closing gracefully");
		isClosing.set(true);
		inboundSink.tryEmitComplete();
		outboundSink.tryEmitComplete();
		terminationSink.tryEmitEmpty();
	}

	private Mono<Void> sendClose() {
		WebSocket webSocket = this.webSocket;
		if (webSocket == null || webSocket.isOutputClosed()) {
			return Mono.empty();
		}
		return Mono.fromFuture(webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Client closing")).then();
	}

	@Override
	public void setExceptionHandler(Consumer<Throwable> handler) {
		this.exceptionHandler = handler;
	}

	@Override
	public Mono<Void> awaitTermination() {
		return terminationSink.asMono();
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
		return jsonMapper.convertValue(data, typeRef);
	}

	/**
	 * WebSocket.Listener implementation for handling incoming messages.
	 */
	private class AcpWebSocketListener implements WebSocket.Listener {

		private final StringBuilder messageBuffer = new StringBuilder();

		@Override
		public void onOpen(WebSocket webSocket) {
			logger.debug("WebSocket connection opened");
			webSocket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			messageBuffer.append(data);

			if (last) {
				String message = messageBuffer.toString();
				messageBuffer.setLength(0);

				logger.debug("Received WebSocket message ({} characters)", message.length());

				try {
					JSONRPCMessage jsonRpcMessage = AcpSchema.deserializeJsonRpcMessage(jsonMapper, message);
					if (!inboundSink.tryEmitNext(jsonRpcMessage).isSuccess()) {
						if (!isClosing.get()) {
							logger.error("Failed to enqueue inbound message");
						}
					}
				}
				catch (Exception e) {
					if (!isClosing.get()) {
						logger.error("Skipped an inbound message that is not a JSON-RPC message", e);
						exceptionHandler.accept(e);
						answerUnreadable(message);
					}
				}
			}

			webSocket.request(1);
			return CompletableFuture.completedFuture(null);
		}

		/**
		 * Answers a message that could not be read with the JSON-RPC error for it (its id
		 * is unknown, so null): the agent may send requests, so this side is the server
		 * for them.
		 */
		private void answerUnreadable(String message) {
			try {
				OutboundSinks.emit(outboundSink, AcpSchema.unreadableMessageResponse(jsonMapper, message));
			}
			catch (Sinks.EmissionException emission) {
				logger.error("Failed to answer an unreadable inbound message", emission);
			}
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			logger.info("WebSocket connection closed: {} - {}", statusCode, reason);
			isClosing.set(true);
			inboundSink.tryEmitComplete();
			terminationSink.tryEmitEmpty();
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			if (!isClosing.get()) {
				logger.error("WebSocket error", error);
				exceptionHandler.accept(error);
				terminationSink.tryEmitError(error);
			}
			isClosing.set(true);
			inboundSink.tryEmitComplete();
			terminationSink.tryEmitEmpty();
		}

	}

}

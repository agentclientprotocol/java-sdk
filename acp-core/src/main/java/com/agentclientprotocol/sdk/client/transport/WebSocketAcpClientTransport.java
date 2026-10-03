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
 * The client side of the WebSocket transport: talks to an agent that is already running behind
 * a WebSocket endpoint, one ACP message per text frame. Use it to reach a remote agent that
 * accepts WebSocket upgrades, such as the {@code acp-streamable-http-jetty} module's listener
 * at {@code ws://host:port/acp}; for an agent served by a servlet in a container, which does
 * not accept WebSocket upgrades, use {@link StreamableHttpAcpClientTransport}, and to start the
 * agent as a child process, {@link StdioAcpClientTransport}. Pass it to
 * {@code AcpClient.sync(transport)} or {@code AcpClient.async(transport)}:
 *
 * <pre>{@code
 * var transport = new WebSocketAcpClientTransport(URI.create("ws://localhost:8080/acp"),
 *         AcpJsonMapper.createDefault());
 * AcpSyncClient client = AcpClient.sync(transport).build();
 * }</pre>
 *
 * <p>Unlike the stdio transport, it opens a network connection with the JDK's
 * {@link java.net.http.WebSocket} when {@link #connect} runs (building the client does it), and
 * it has no process to stop: {@link #closeGracefully()} sends a normal close frame without
 * waiting for the agent's. If the connection cannot be opened, the client's requests fail with
 * that error. Messages sent before the connection is open wait for it. A frame that is not a
 * JSON-RPC message is reported to the exception handler, answered with a JSON-RPC error and
 * skipped. When the agent closes the connection, or it fails, {@link #awaitTermination()} ends
 * and the client's pending requests fail.
 *
 * <p>The transport is thread-safe: messages may be sent from any thread, and one daemon thread
 * of its own ({@code acp-ws-client-outbound}) sends them one frame at a time. The default
 * HTTP client runs on a pool of daemon threads named {@code acp-ws-client}.
 *
 * @author Mark Pollack
 */
public class WebSocketAcpClientTransport implements AcpClientTransport {

	private static final Logger logger = LoggerFactory.getLogger(WebSocketAcpClientTransport.class);

	/**
	 * The endpoint path on which the SDK's listener accepts WebSocket upgrades by default:
	 * {@value}. The transport does not add it; the URI must include the path.
	 */
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
	 * Creates a transport for the WebSocket endpoint at {@code serverUri}, with an HTTP client
	 * of its own.
	 * @param serverUri the agent's endpoint: a {@code ws} or {@code wss} URI including its
	 * path, such as {@code ws://localhost:8080/acp}
	 * @param jsonMapper the mapper that reads and writes the messages
	 * @throws IllegalArgumentException if an argument is null
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
	 * Creates a transport for the WebSocket endpoint at {@code serverUri} that opens its
	 * connection with {@code httpClient}, for TLS, proxy or authentication settings of your
	 * own.
	 * @param serverUri the agent's endpoint: a {@code ws} or {@code wss} URI including its
	 * path
	 * @param jsonMapper the mapper that reads and writes the messages
	 * @param httpClient the client the WebSocket connection is opened with
	 * @throws IllegalArgumentException if an argument is null
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
	 * Sets how long {@link #connect} waits for the WebSocket handshake to finish; default 30
	 * seconds. Call it before connecting.
	 * @param timeout the handshake timeout; positive
	 * @return this transport
	 */
	public WebSocketAcpClientTransport connectTimeout(Duration timeout) {
		this.connectTimeout = timeout;
		return this;
	}

	/**
	 * {@inheritDoc}
	 * <p>Opens the WebSocket connection when the returned Mono is subscribed, and completes
	 * once the handshake has finished. A second call fails with an
	 * {@link IllegalStateException} unless the first one failed. A connect that failed may be
	 * tried again, but the agent's messages then no longer reach the handler; create a new
	 * transport instead.
	 */
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
			}, error -> logger.debug("Outbound processing ended: {}", error.toString()));
	}

	/**
	 * {@inheritDoc}
	 * <p>Waits until the connection is open, then queues the message for the writer thread;
	 * the Mono completes once it is queued. A frame that cannot be sent is reported to the
	 * exception handler, not to the Mono. Once the transport is closed, messages are dropped
	 * and the Mono still completes.
	 */
	@Override
	public Mono<Void> sendMessage(JSONRPCMessage message) {
		return connectionReady.asMono().then(Mono.defer(() -> {
			OutboundSinks.emit(outboundSink, message);
			return Mono.empty();
		}));
	}

	/**
	 * {@inheritDoc}
	 * <p>Stops delivering and sending messages, completes {@link #awaitTermination()}, sends a
	 * normal close frame (1000) when the connection is open, and stops the writer thread. It
	 * does not wait for the agent's close frame. Only the first call closes; a later call
	 * completes when that close has finished.
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

	/**
	 * {@inheritDoc}
	 * <p>The default logs each error at ERROR. Call it before {@link #connect}.
	 */
	@Override
	public void setExceptionHandler(Consumer<Throwable> handler) {
		this.exceptionHandler = handler;
	}

	/**
	 * {@inheritDoc}
	 * <p>It completes when either side closes the connection, and errors with the cause when
	 * the connection fails.
	 */
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

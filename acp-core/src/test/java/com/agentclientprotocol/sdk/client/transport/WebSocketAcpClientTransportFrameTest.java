/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import com.agentclientprotocol.sdk.CapturedLogs;
import com.agentclientprotocol.sdk.QuietLoggers;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.JSONRPCMessage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Frame-level tests for {@link WebSocketAcpClientTransport}: the WebSocket listener and the
 * outbound path, driven through a fake {@link HttpClient} so that each frame, close and
 * error can be delivered exactly.
 */
class WebSocketAcpClientTransportFrameTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final String REQUEST = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"session/request_permission\",\"params\":{}}";

	private final FakeWebSocket webSocket = new FakeWebSocket();

	private final FakeHttpClient httpClient = new FakeHttpClient(webSocket);

	private final WebSocketAcpClientTransport transport = new WebSocketAcpClientTransport(
			URI.create("ws://localhost:1/acp"), AcpJsonMapper.createDefault(), httpClient);

	private final List<JSONRPCMessage> received = new CopyOnWriteArrayList<>();

	private final AtomicReference<@Nullable Throwable> reported = new AtomicReference<>();

	@AfterEach
	void tearDown() {
		transport.closeGracefully().block(TIMEOUT);
	}

	private void connect() {
		transport.setExceptionHandler(reported::set);
		transport.connect(message -> message.doOnNext(received::add)
			.map(request -> new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION,
					((AcpSchema.JSONRPCRequest) request).id(), "ok", null)))
			.block(TIMEOUT);
	}

	@Test
	void openRequestsTheFirstFrame() {
		connect();

		assertThat(webSocket.requested).isEqualTo(1);
	}

	@Test
	void aFragmentedMessageIsReassembledAndAnswered() {
		connect();
		WebSocket.Listener listener = httpClient.listener();

		listener.onText(webSocket, REQUEST.substring(0, 20), false);
		assertThat(received).isEmpty();
		listener.onText(webSocket, REQUEST.substring(20), true);

		awaitSentFrames(1);
		assertThat(received).singleElement()
			.isInstanceOfSatisfying(AcpSchema.JSONRPCRequest.class, request -> assertThat(request.id()).isEqualTo(7));
		assertThat(webSocket.sent.get(0)).contains("\"id\":7").contains("\"result\":\"ok\"");
		assertThat(webSocket.requested).as("one more frame requested after each fragment").isEqualTo(3);
	}

	@Test
	void aMalformedFrameIsReportedAnsweredAndTheNextFrameStillArrives() {
		connect();
		WebSocket.Listener listener = httpClient.listener();

		try (QuietLoggers quiet = QuietLoggers.of(WebSocketAcpClientTransport.class)) {
			listener.onText(webSocket, "{not json", true);
		}
		listener.onText(webSocket, REQUEST, true);

		assertThat(reported.get()).isNotNull();
		awaitSentFrames(2);
		assertThat(received).hasSize(1);
		assertThat(webSocket.sent).anySatisfy(frame -> assertThat(frame).contains("\"id\":null").contains("-32700"));
		assertThat(webSocket.sent).anySatisfy(frame -> assertThat(frame).contains("\"id\":7"));
	}

	@Test
	void aCloseFromTheServerTerminatesTheTransport() {
		connect();

		httpClient.listener().onClose(webSocket, WebSocket.NORMAL_CLOSURE, "server closing");

		transport.awaitTermination().block(TIMEOUT);
		assertThat(reported.get()).isNull();
	}

	@Test
	void aWebSocketErrorIsReportedAndTerminatesTheTransport() {
		connect();
		IOException failure = new IOException("connection reset");

		try (QuietLoggers quiet = QuietLoggers.of(WebSocketAcpClientTransport.class)) {
			httpClient.listener().onError(webSocket, failure);
		}

		assertThat(reported.get()).isSameAs(failure);
		assertThatThrownBy(() -> transport.awaitTermination().block(TIMEOUT)).hasCause(failure);
	}

	@Test
	void anErrorAfterCloseIsNotReported() {
		connect();
		WebSocket.Listener listener = httpClient.listener();
		listener.onClose(webSocket, WebSocket.NORMAL_CLOSURE, "server closing");

		listener.onError(webSocket, new IOException("late"));

		assertThat(reported.get()).isNull();
		transport.awaitTermination().block(TIMEOUT);
	}

	@Test
	void aMessageSentBeforeTheConnectionOpensIsDeliveredOnceItDoes() {
		Mono<Void> early = transport
			.sendMessage(new AcpSchema.JSONRPCNotification("session/cancel", java.util.Map.of("sessionId", "s")));
		early.subscribe();

		connect();

		awaitSentFrames(1);
		assertThat(webSocket.sent.get(0)).contains("session/cancel");
	}

	@Test
	void aFailedSendIsReported() {
		webSocket.failSends = true;
		connect();

		try (QuietLoggers quiet = QuietLoggers.of(WebSocketAcpClientTransport.class)) {
			transport.sendMessage(new AcpSchema.JSONRPCNotification("session/cancel", null)).block(TIMEOUT);
			long deadline = System.nanoTime() + TIMEOUT.toNanos();
			while (reported.get() == null && System.nanoTime() < deadline) {
				Thread.onSpinWait();
			}
		}

		assertThat(reported.get()).hasRootCauseMessage("send failed");
	}

	@Test
	void aFailedConnectIsReportedAndMayBeRetried() {
		httpClient.failConnect = true;
		transport.setExceptionHandler(reported::set);

		try (QuietLoggers quiet = QuietLoggers.of(WebSocketAcpClientTransport.class)) {
			assertThatThrownBy(() -> transport.connect(message -> message).block(TIMEOUT))
				.hasRootCauseMessage("refused");
		}
		assertThat(reported.get()).hasMessage("refused");

		httpClient.failConnect = false;
		connect();
		assertThat(webSocket.requested).isEqualTo(1);

		// The retried connection works: the agent's request reaches the handler and is answered.
		httpClient.listener().onText(webSocket, REQUEST, true);
		awaitSentFrames(1);
		assertThat(received).singleElement().isInstanceOf(AcpSchema.JSONRPCRequest.class);
		assertThat(webSocket.sent.get(0)).contains("\"id\":7").contains("\"result\":\"ok\"");
	}

	@Test
	void connectIsRefusedWhileConnected() {
		connect();

		assertThatThrownBy(() -> transport.connect(message -> message).block(TIMEOUT))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Already connected");
	}

	@Test
	void closeGracefullySendsANormalClosure() {
		connect();

		transport.closeGracefully().block(TIMEOUT);

		assertThat(webSocket.closeStatus).isEqualTo(WebSocket.NORMAL_CLOSURE);
		transport.awaitTermination().block(TIMEOUT);
	}

	/**
	 * An invalid request from the agent is its payload and can carry the user's data: it is
	 * answered -32600, and no log at INFO or above, the default exception handler's included,
	 * quotes it.
	 */
	@Test
	void anInvalidRequestIsNotLoggedAboveDebug() {
		try (CapturedLogs logs = CapturedLogs.open()) {
			transport.connect(message -> message).block(TIMEOUT);

			httpClient.listener()
				.onText(webSocket, "{\"id\":1,\"method\":\"session/request_permission\","
						+ "\"params\":{\"sessionId\":\"s\",\"text\":\"SECRET-123\"}}", true);

			awaitSentFrames(1);
			assertThat(logs.events()).as("the refusal is logged").isNotEmpty();
			logs.assertNoneAtInfoOrAboveContains("SECRET-123");
		}
		assertThat(webSocket.sent.get(0)).contains("-32600");
	}

	/**
	 * The endpoint's query string can carry an access token, and its user information a
	 * password: neither is logged when connecting, nor when the connect fails.
	 */
	@Test
	void theEndpointIsLoggedWithoutItsQueryOrUserInformation() {
		URI endpoint = URI.create("ws://user:PASSWORD-123@localhost:1/acp?token=SECRET-123");
		WebSocketAcpClientTransport withToken = new WebSocketAcpClientTransport(endpoint,
				AcpJsonMapper.createDefault(), httpClient);
		try (CapturedLogs logs = CapturedLogs.open()) {
			httpClient.failConnect = true;
			withToken.setExceptionHandler(error -> {
			});
			assertThatThrownBy(() -> withToken.connect(message -> message).block(TIMEOUT)).isNotNull();
			httpClient.failConnect = false;
			withToken.connect(message -> message).block(TIMEOUT);

			assertThat(logs.events()).extracting(event -> event.getFormattedMessage())
				.anySatisfy(message -> assertThat(message).contains("Connected").contains("ws://localhost:1/acp"));
			logs.assertNoneAtInfoOrAboveContains("SECRET-123");
			logs.assertNoneAtInfoOrAboveContains("PASSWORD-123");
		}
		finally {
			withToken.closeGracefully().block(TIMEOUT);
		}
	}

	private void awaitSentFrames(int count) {
		long deadline = System.nanoTime() + TIMEOUT.toNanos();
		while (webSocket.sent.size() < count && System.nanoTime() < deadline) {
			Thread.onSpinWait();
		}
		assertThat(webSocket.sent).hasSizeGreaterThanOrEqualTo(count);
	}

	/** Records what the transport sends; requests and closes are counted, not acted on. */
	static final class FakeWebSocket implements WebSocket {

		final List<String> sent = new CopyOnWriteArrayList<>();

		volatile long requested;

		volatile int closeStatus = -1;

		volatile boolean failSends;

		@Override
		public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
			if (failSends) {
				return CompletableFuture.failedFuture(new IOException("send failed"));
			}
			sent.add(data.toString());
			return CompletableFuture.completedFuture(this);
		}

		@Override
		public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
			throw new UnsupportedOperationException();
		}

		@Override
		public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
			throw new UnsupportedOperationException();
		}

		@Override
		public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
			throw new UnsupportedOperationException();
		}

		@Override
		public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
			closeStatus = statusCode;
			return CompletableFuture.completedFuture(this);
		}

		@Override
		public void request(long n) {
			requested += n;
		}

		@Override
		public String getSubprotocol() {
			return "";
		}

		@Override
		public boolean isOutputClosed() {
			return closeStatus != -1;
		}

		@Override
		public boolean isInputClosed() {
			return false;
		}

		@Override
		public void abort() {
		}

	}

	/** An HttpClient whose WebSocket builder opens the fake socket and keeps the listener. */
	static final class FakeHttpClient extends HttpClient {

		private final FakeWebSocket webSocket;

		private final AtomicReference<WebSocket.@Nullable Listener> listener = new AtomicReference<>();

		volatile boolean failConnect;

		FakeHttpClient(FakeWebSocket webSocket) {
			this.webSocket = webSocket;
		}

		WebSocket.Listener listener() {
			WebSocket.Listener current = listener.get();
			assertThat(current).as("connected").isNotNull();
			return current;
		}

		@Override
		public WebSocket.Builder newWebSocketBuilder() {
			return new WebSocket.Builder() {

				@Override
				public WebSocket.Builder header(String name, String value) {
					return this;
				}

				@Override
				public WebSocket.Builder connectTimeout(Duration timeout) {
					return this;
				}

				@Override
				public WebSocket.Builder subprotocols(String mostPreferred, String... lesserPreferred) {
					return this;
				}

				@Override
				public CompletableFuture<WebSocket> buildAsync(URI uri, WebSocket.Listener webSocketListener) {
					if (failConnect) {
						return CompletableFuture.failedFuture(new IOException("refused"));
					}
					listener.set(webSocketListener);
					webSocketListener.onOpen(webSocket);
					return CompletableFuture.completedFuture(webSocket);
				}

			};
		}

		@Override
		public Optional<CookieHandler> cookieHandler() {
			return Optional.empty();
		}

		@Override
		public Optional<Duration> connectTimeout() {
			return Optional.empty();
		}

		@Override
		public Redirect followRedirects() {
			return Redirect.NEVER;
		}

		@Override
		public Optional<ProxySelector> proxy() {
			return Optional.empty();
		}

		@Override
		public SSLContext sslContext() {
			throw new UnsupportedOperationException();
		}

		@Override
		public SSLParameters sslParameters() {
			throw new UnsupportedOperationException();
		}

		@Override
		public Optional<Authenticator> authenticator() {
			return Optional.empty();
		}

		@Override
		public Version version() {
			return Version.HTTP_1_1;
		}

		@Override
		public Optional<Executor> executor() {
			return Optional.empty();
		}

		@Override
		public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
				HttpResponse.BodyHandler<T> responseBodyHandler) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
				HttpResponse.BodyHandler<T> responseBodyHandler,
				HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
			throw new UnsupportedOperationException();
		}

	}

	/** A message sent once the transport is closed fails; it used to be dropped silently. */
	@Test
	void aMessageSentAfterCloseFails() {
		connect();
		transport.closeGracefully().block(TIMEOUT);

		assertThatThrownBy(() -> transport
			.sendMessage(new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION, "session/cancel", null))
			.block(TIMEOUT)).isInstanceOf(com.agentclientprotocol.sdk.error.AcpConnectionException.class);
		assertThat(webSocket.sent).isEmpty();
	}

}

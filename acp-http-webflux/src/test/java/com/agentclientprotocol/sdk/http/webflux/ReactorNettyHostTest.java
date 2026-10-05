/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.http.webflux;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import com.agentclientprotocol.sdk.http.server.AcpHttpEndpoint;
import com.agentclientprotocol.sdk.http.server.AcpHttpExchange;
import com.agentclientprotocol.sdk.http.server.AcpHttpReply;
import com.agentclientprotocol.sdk.http.server.AcpWsHandshake;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.test.http.AcpHttpTransportTck;
import com.agentclientprotocol.sdk.test.http.HttpProbes;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.server.WebFilter;
import org.springframework.web.reactive.handler.WebFluxResponseStatusExceptionHandler;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The WebFlux host on Reactor Netty, beyond the TCK: the principal a {@code WebFilter} resolved
 * (as Spring Security's does) reaches the endpoint over HTTP and on the handshake; a message far
 * over the limit closes with 1009 too; the route serves its own path only.
 */
class ReactorNettyHostTest {

	private static final long LIMIT = 16 * 1024;

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	private final List<String> principals = new CopyOnWriteArrayList<>();

	private final AcpHttpEndpoint endpoint = AcpHttpEndpoint.create(AcpJsonMapper.createDefault(),
			AcpHttpTransportTck.agentFactory(), StreamableHttpAcpAgentTransportOptions.builder().maxPostBodyBytes(LIMIT).build());

	private @Nullable DisposableServer server;

	private URI uri = URI.create("http://127.0.0.1/acp");

	@BeforeEach
	void start() {
		// The principal as Spring Security's WebFilter chain hands it over, for requests that
		// carry the test's header.
		WebFilter authentication = (exchange, chain) -> {
			String user = exchange.getRequest().getHeaders().getFirst("X-User");
			return user == null ? chain.filter(exchange)
					: chain.filter(exchange.mutate().principal(Mono.just(() -> user)).build());
		};
		var handler = WebHttpHandlerBuilder
			.webHandler(RouterFunctions.toWebHandler(new AcpWebFluxHost(new Recording(endpoint)).routerFunction("acp")))
			.filter(authentication)
			.exceptionHandler(new WebFluxResponseStatusExceptionHandler())
			.build();
		DisposableServer started = HttpServer.create()
			.host("127.0.0.1")
			.port(0)
			.handle(new ReactorHttpHandlerAdapter(handler))
			.bindNow();
		this.server = started;
		this.uri = URI.create("http://127.0.0.1:" + started.port() + "/acp");
	}

	@AfterEach
	void stop() {
		endpoint.closeGracefully().block(Duration.ofSeconds(10));
		DisposableServer current = server;
		if (current != null) {
			current.disposeNow(Duration.ofSeconds(10));
		}
	}

	@Test
	void thePrincipalAWebFilterResolvedReachesTheEndpoint() throws Exception {
		assertThat(HttpProbes.initialize(uri, null, Map.of("X-User", "alice")).statusCode()).isEqualTo(200);
		assertThat(HttpProbes.webSocketHandshake(uri, null, Map.of("X-User", "alice")))
			.isEqualTo(HttpProbes.SWITCHING_PROTOCOLS);
		assertThat(HttpProbes.initialize(uri, null).statusCode()).isEqualTo(200);
		assertThat(principals).containsExactly("alice", "alice", "<none>");
	}

	@Test
	void aMessageFarOverTheLimitClosesWith1009() throws Exception {
		Socket socket = Socket.open(uri);
		socket.send(HttpProbes.INITIALIZE);
		socket.awaitMessage();
		socket.send("x".repeat((int) LIMIT * 4));
		assertThat(socket.closeCode()).isEqualTo(1009);
	}

	@Test
	void aMessageAtTheLimitIsTheEndpointsToRead() throws Exception {
		Socket socket = Socket.open(uri);
		socket.send(HttpProbes.INITIALIZE);
		socket.awaitMessage();
		// Not JSON-RPC, so answered with a parse error; the socket stays open.
		socket.send("x".repeat((int) LIMIT));
		assertThat(socket.awaitMessage()).contains("-32700");
	}

	@Test
	void theRouteServesItsOwnPathOnly() throws Exception {
		HttpResponse<String> other = HTTP.send(HttpRequest.newBuilder(uri.resolve("/other")).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(other.statusCode()).isEqualTo(404);
		assertThat(principals).isEmpty();
	}

	@Test
	void aHostNeedsAnEndpointAndARouteAPath() {
		assertThatIllegalArgumentException().isThrownBy(() -> new AcpWebFluxHost(null));
		AcpWebFluxHost host = new AcpWebFluxHost(endpoint);
		assertThat(host.endpoint()).isSameAs(endpoint);
		assertThatIllegalArgumentException().isThrownBy(() -> host.routerFunction(""));
	}

	/** A raw WebSocket client that records the first message and the close code. */
	private static final class Socket implements WebSocket.Listener {

		private final java.util.concurrent.BlockingQueue<String> messages = new java.util.concurrent.LinkedBlockingQueue<>();

		private final CompletableFuture<Integer> closeCode = new CompletableFuture<>();

		private final StringBuilder partial = new StringBuilder();

		private @Nullable WebSocket socket;

		static Socket open(URI uri) throws Exception {
			Socket listener = new Socket();
			listener.socket = HTTP.newWebSocketBuilder()
				.buildAsync(URI.create("ws" + uri.toString().substring("http".length())), listener)
				.get(10, TimeUnit.SECONDS);
			return listener;
		}

		void send(String text) throws Exception {
			WebSocket current = socket;
			if (current != null) {
				current.sendText(text, true).get(10, TimeUnit.SECONDS);
			}
		}

		String awaitMessage() throws InterruptedException {
			String message = messages.poll(10, TimeUnit.SECONDS);
			if (message == null) {
				throw new AssertionError("no message");
			}
			return message;
		}

		int closeCode() throws Exception {
			return closeCode.get(10, TimeUnit.SECONDS);
		}

		@Override
		public @Nullable CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			partial.append(data);
			if (last) {
				messages.add(partial.toString());
				partial.setLength(0);
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public @Nullable CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			closeCode.complete(statusCode);
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			closeCode.complete(1006);
		}

	}

	/** Records the principal of every exchange the endpoint is handed. */
	private final class Recording implements AcpHttpEndpoint {

		private final AcpHttpEndpoint delegate;

		Recording(AcpHttpEndpoint delegate) {
			this.delegate = delegate;
		}

		private void record(AcpHttpExchange exchange) {
			Principal principal = exchange.principal();
			principals.add(principal != null ? principal.getName() : "<none>");
		}

		@Override
		public StreamableHttpAcpAgentTransportOptions options() {
			return delegate.options();
		}

		@Override
		public Mono<AcpHttpReply> handle(AcpHttpExchange exchange) {
			record(exchange);
			return delegate.handle(exchange);
		}

		@Override
		public AcpWsHandshake webSocketHandshake(AcpHttpExchange handshake) {
			record(handshake);
			return delegate.webSocketHandshake(handshake);
		}

		@Override
		public void start() {
			delegate.start();
		}

		@Override
		public Mono<Void> closeGracefully() {
			return delegate.closeGracefully();
		}

		@Override
		public int activeConnectionCount() {
			return delegate.activeConnectionCount();
		}

		@Override
		public void setExceptionHandler(Consumer<Throwable> handler) {
			delegate.setExceptionHandler(handler);
		}

	}

}

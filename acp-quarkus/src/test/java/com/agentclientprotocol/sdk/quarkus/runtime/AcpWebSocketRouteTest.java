/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.quarkus.AcpBuildTimeConfig;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The WebSocket route on a real Vert.x router and server, with the SDK's WebSocket client:
 * upgrades on the path become agent connections, other requests go on, and closing
 * closes every connection.
 */
class AcpWebSocketRouteTest {

	private Vertx vertx;

	private HttpServer server;

	private AcpWebSocketRoute route;

	@BeforeEach
	void start() throws Exception {
		vertx = Vertx.vertx();
		AcpHttpEndpoint endpoint = mock(AcpHttpEndpoint.class);
		when(endpoint.jsonMapper()).thenReturn(AcpJsonMapper.createDefault());
		when(endpoint.options()).thenReturn(StreamableHttpAcpAgentTransportOptions.defaults());
		when(endpoint.agentFactory()).thenReturn(AcpAgentFactory.sync(transport -> AcpAgent.sync(transport)
			.initializeHandler(request -> AcpSchema.InitializeResponse.ok())
			.newSessionHandler(request -> new AcpSchema.NewSessionResponse("s1", null, null))
			.promptHandler((request, context) -> {
				context.sendMessage("pong");
				return AcpSchema.PromptResponse.endTurn();
			})
			.build()));
		AcpBuildTimeConfig config = mock(AcpBuildTimeConfig.class, Answers.RETURNS_DEEP_STUBS);
		when(config.agent().transport().http().path()).thenReturn("/acp");
		route = new AcpWebSocketRoute(endpoint, config);
		Router router = Router.router(vertx);
		route.register(router);
		router.route("/acp").handler(context -> context.response().setStatusCode(204).end());
		server = vertx.createHttpServer().requestHandler(router).listen(0).toCompletionStage().toCompletableFuture()
			.get(10, TimeUnit.SECONDS);
	}

	@AfterEach
	void stop() throws Exception {
		vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
	}

	@Test
	void webSocketClientPromptsThroughTheRoute() {
		List<String> messages = new java.util.concurrent.CopyOnWriteArrayList<>();
		AcpSyncClient client = AcpClient.sync(new WebSocketAcpClientTransport(
				URI.create("ws://localhost:" + server.actualPort() + "/acp"), AcpJsonMapper.createDefault()))
			.requestTimeout(Duration.ofSeconds(10))
			.sessionUpdateHandler(notification -> {
				if (notification.update() instanceof AcpSchema.AgentMessageChunk chunk
						&& chunk.content() instanceof AcpSchema.TextContent text) {
					messages.add(text.text());
				}
			})
			.build();
		client.initialize();
		String session = client.newSession(new AcpSchema.NewSessionRequest("/", List.of())).sessionId();
		assertThat(client.prompt(new AcpSchema.PromptRequest(session, List.of(new AcpSchema.TextContent("ping"))))
			.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
		assertThat(messages).containsExactly("pong");
		assertThat(route.activeConnectionCount()).isEqualTo(1);

		route.closeGracefully().block(Duration.ofSeconds(10));
		assertThat(route.activeConnectionCount()).isZero();
		client.close();
	}

	@Test
	void plainRequestsGoOnToTheNextHandler() throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.actualPort() + "/acp")).GET().build(),
					HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(204);
	}

}

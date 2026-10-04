/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAgentFactory;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.ServerWebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Vert.x WebSocket connection's protocol rules, on a mocked socket and a real agent. */
class VertxWebSocketConnectionTest {

	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1}}";

	private static final AcpAgentFactory AGENT = AcpAgentFactory.sync(transport -> AcpAgent.sync(transport)
		.initializeHandler(request -> AcpSchema.InitializeResponse.ok())
		.promptHandler((request, context) -> AcpSchema.PromptResponse.endTurn())
		.build());

	private final ServerWebSocket socket = mock(ServerWebSocket.class);

	private final List<VertxWebSocketConnection> deregistered = new ArrayList<>();

	private VertxWebSocketConnection connection;

	@AfterEach
	void close() {
		if (connection != null) {
			connection.closeNow();
		}
	}

	private VertxWebSocketConnection connect(StreamableHttpAcpAgentTransportOptions options) {
		when(socket.writeTextMessage(anyString())).thenReturn(Future.succeededFuture());
		connection = new VertxWebSocketConnection("c1", socket, AcpJsonMapper.createDefault(), options,
				deregistered::add, error -> {
				});
		connection.start(AGENT).block(Duration.ofSeconds(5));
		return connection;
	}

	@Test
	void initializeIsAnsweredOnTheSocket() {
		connect(StreamableHttpAcpAgentTransportOptions.defaults()).receive(INITIALIZE);
		verify(socket, timeout(5000)).writeTextMessage(contains("\"protocolVersion\":1"));
		assertThat(connection.id()).isEqualTo("c1");
	}

	@Test
	void firstMessageMustBeInitialize() {
		connect(StreamableHttpAcpAgentTransportOptions.defaults())
			.receive("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"session/new\",\"params\":{\"cwd\":\"/\",\"mcpServers\":[]}}");
		verify(socket).close(eq(VertxWebSocketConnection.PROTOCOL_ERROR), contains("must be initialize"));
		assertThat(deregistered).containsExactly(connection);
	}

	@Test
	void secondInitializeIsRefused() {
		connect(StreamableHttpAcpAgentTransportOptions.defaults()).receive(INITIALIZE);
		connection.receive(INITIALIZE.replace("\"id\":1", "\"id\":2"));
		verify(socket, timeout(5000)).writeTextMessage(contains("Initialize not allowed on existing connection"));
	}

	@Test
	void unreadableMessageIsAnsweredAndSkipped() {
		connect(StreamableHttpAcpAgentTransportOptions.defaults()).receive("not json");
		verify(socket).writeTextMessage(contains("-32700"));
		verify(socket, never()).close(org.mockito.ArgumentMatchers.anyShort(), anyString());
	}

	@Test
	void oversizedMessageClosesTheSocket() {
		connect(StreamableHttpAcpAgentTransportOptions.builder().maxPostBodyBytes(16).build()).receive(INITIALIZE);
		verify(socket).close(eq(VertxWebSocketConnection.MESSAGE_TOO_BIG), anyString());
	}

	@Test
	void tooManyPendingFramesCloseTheConnection() {
		VertxWebSocketConnection connection = connect(
				StreamableHttpAcpAgentTransportOptions.builder().maxWebSocketPendingFrames(1).build());
		when(socket.writeTextMessage(anyString())).thenReturn(Promise.<Void>promise().future());
		connection.sendToClient(AcpSchema.unreadableMessageResponse(AcpJsonMapper.createDefault(), "x"));
		connection.sendToClient(AcpSchema.unreadableMessageResponse(AcpJsonMapper.createDefault(), "y"));
		verify(socket).close(eq(VertxWebSocketConnection.SERVER_ERROR), anyString());
	}

	@Test
	void failedWriteClosesTheConnection() {
		VertxWebSocketConnection connection = connect(StreamableHttpAcpAgentTransportOptions.defaults());
		when(socket.writeTextMessage(anyString())).thenReturn(Future.failedFuture("reset"));
		connection.sendToClient(AcpSchema.unreadableMessageResponse(AcpJsonMapper.createDefault(), "x"));
		verify(socket).close(eq(VertxWebSocketConnection.SERVER_ERROR), anyString());
	}

	@Test
	void closesOnceAndThenSendsNothing() {
		VertxWebSocketConnection connection = connect(StreamableHttpAcpAgentTransportOptions.defaults());
		connection.closeGracefully().block(Duration.ofSeconds(5));
		connection.closed();
		connection.sendToClient(AcpSchema.unreadableMessageResponse(AcpJsonMapper.createDefault(), "x"));
		verify(socket).close(eq(VertxWebSocketConnection.NORMAL), anyString());
		verify(socket, never()).writeTextMessage(anyString());
		assertThat(deregistered).containsExactly(connection);
	}

	@Test
	void socketErrorsCloseTheConnectionButAPeerGoneAwayIsLeftToTheCloseHandler() {
		VertxWebSocketConnection connection = connect(StreamableHttpAcpAgentTransportOptions.defaults());
		AcpWebSocketRoute.socketFailed(connection, new io.vertx.core.http.HttpClosedException("gone"));
		verify(socket, never()).close(org.mockito.ArgumentMatchers.anyShort(), anyString());
		AcpWebSocketRoute.socketFailed(connection, new IllegalStateException("frame too big"));
		verify(socket).close(eq(VertxWebSocketConnection.SERVER_ERROR), anyString());
	}

	@Test
	void upgradeIsRecognisedAndPathsJoin() {
		HttpServerRequest upgrade = mock(HttpServerRequest.class);
		when(upgrade.getHeader(io.vertx.core.http.HttpHeaders.UPGRADE)).thenReturn("WebSocket");
		HttpServerRequest plain = mock(HttpServerRequest.class);
		assertThat(AcpWebSocketRoute.isUpgrade(upgrade)).isTrue();
		assertThat(AcpWebSocketRoute.isUpgrade(plain)).isFalse();
		assertThat(AcpWebSocketRoute.join("", "/acp")).isEqualTo("/acp");
		assertThat(AcpWebSocketRoute.join("/app/", "acp")).isEqualTo("/app/acp");
		assertThat(AcpWebSocketRoute.join("/app", "/acp")).isEqualTo("/app/acp");
	}

}

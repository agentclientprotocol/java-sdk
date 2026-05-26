/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.agentclientprotocol.sdk.agent.transport.WebSocketAcpAgentTransport;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpAgentSession;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the WebSocket client transport echo bug on main.
 *
 * <p>
 * This test isolates the agent-request path:
 * </p>
 * <ol>
 * <li>The agent receives {@code session/prompt} from the client.</li>
 * <li>While handling that prompt, the agent sends {@code fs/read_text_file} to the
 * client.</li>
 * <li>The client has registered {@code readTextFileHandler}, so it should handle the
 * request locally and send only a JSON-RPC response with the same id.</li>
 * <li>The original {@code fs/read_text_file} request must not be sent back to the
 * agent.</li>
 * </ol>
 *
 * <p>
 * On main, {@code AcpClientSession} wires the transport handler with
 * {@code mono -> mono.doOnNext(this::handle)}. Reactor {@code doOnNext} preserves the
 * original message downstream, and the WebSocket transport forwards handler-emitted
 * messages back onto the socket. The result is that an inbound agent request can be
 * echoed back to the agent after being handled by the client.
 * </p>
 */
class WebSocketClientInboundEchoReproTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	@Test
	void clientSessionShouldNotEchoAgentRequestsBackToAgent() throws Exception {
		AcpJsonMapper jsonMapper = AcpJsonMapper.createDefault();
		int port = findFreePort();

		WebSocketAcpAgentTransport agentTransport = new WebSocketAcpAgentTransport(port, jsonMapper);
		AtomicReference<AcpAgentSession> agentSessionRef = new AtomicReference<>();
		CountDownLatch echoedRequestReceived = new CountDownLatch(1);

		AcpAgentSession agentSession = null;
		AcpAsyncClient client = null;

		try {
			Map<String, AcpAgentSession.RequestHandler<?>> requestHandlers = new HashMap<>();
			requestHandlers.put(AcpSchema.METHOD_INITIALIZE,
					params -> Mono.just(new AcpSchema.InitializeResponse(1, new AcpSchema.AgentCapabilities(), List.of())));
			requestHandlers.put(AcpSchema.METHOD_SESSION_NEW,
					params -> Mono.just(new AcpSchema.NewSessionResponse("echo-session", null, null)));

			// The prompt handler deliberately sends an agent->client request. The expected
			// protocol flow is:
			//
			// agent -> client: request id=N, method=fs/read_text_file
			// client -> agent: response id=N, result={ content: "client content" }
			//
			// The original request is not a client->agent message and should never be
			// observed by the agent's inbound request router.
			requestHandlers.put(AcpSchema.METHOD_SESSION_PROMPT, params -> agentSessionRef.get()
				.sendRequest(AcpSchema.METHOD_FS_READ_TEXT_FILE,
						new AcpSchema.ReadTextFileRequest("echo-session", "/tmp/input.txt", null, null),
						new TypeRef<AcpSchema.ReadTextFileResponse>() {
						})
				.thenReturn(AcpSchema.PromptResponse.endTurn()));

			// Trap the agent->client method on the agent side. This handler should never run:
			// fs/read_text_file is a client method, so if the agent receives it here, the
			// client has echoed the inbound agent request back over the WebSocket transport.
			// Returning "unexpected echo" makes the trap harmless to the rest of the prompt
			// flow while the latch records that the invalid path happened.
			requestHandlers.put(AcpSchema.METHOD_FS_READ_TEXT_FILE, params -> {
				echoedRequestReceived.countDown();
				return Mono.just(new AcpSchema.ReadTextFileResponse("unexpected echo"));
			});

			agentSession = new AcpAgentSession(TIMEOUT, agentTransport, requestHandlers, Map.of());
			agentSessionRef.set(agentSession);
			Thread.sleep(300);

			WebSocketAcpClientTransport clientTransport = new WebSocketAcpClientTransport(
					URI.create("ws://localhost:" + port + "/acp"), jsonMapper);
			client = AcpClient.async(clientTransport)
				.requestTimeout(TIMEOUT)
				// Registering this handler means the client can satisfy fs/read_text_file
				// locally. It has no reason to route the request back to the agent.
				.readTextFileHandler(params -> Mono.just(new AcpSchema.ReadTextFileResponse("client content")))
				.build();

			// Advertise the matching client capability so the agent is allowed to make the
			// fs/read_text_file request during prompt handling.
			client.initialize(new AcpSchema.InitializeRequest(1,
					new AcpSchema.ClientCapabilities(new AcpSchema.FileSystemCapability(true, false), false)))
				.block(TIMEOUT);
			client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of())).block(TIMEOUT);

			AcpSchema.PromptResponse response = client
				.prompt(new AcpSchema.PromptRequest("echo-session", List.of(new AcpSchema.TextContent("read file"))))
				.block(TIMEOUT);

			assertThat(response).isNotNull();
			assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);

			// This should remain false. On main it becomes true, proving that the
			// WebSocket client transport echoed the inbound fs/read_text_file request back
			// to the agent.
			assertThat(echoedRequestReceived.await(1, TimeUnit.SECONDS))
				.as("WebSocket client session must not send inbound agent requests back to the agent")
				.isFalse();
		}
		finally {
			if (client != null) {
				client.closeGracefully().block(TIMEOUT);
			}
			if (agentSession != null) {
				agentSession.closeGracefully().block(TIMEOUT);
			}
		}
	}

	private static int findFreePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

}

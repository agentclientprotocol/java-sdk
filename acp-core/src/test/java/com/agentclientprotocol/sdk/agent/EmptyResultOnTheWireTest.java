/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A handler that returns an empty response type is answered with {@code "result": {}}, the
 * exact bytes on the stdio wire. Java must never send {@code "result": null} (which peers
 * without a default-on-null rule reject) nor drop the result.
 */
class EmptyResultOnTheWireTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	@Test
	void emptyResponsesAreWrittenAsAnEmptyObjectResult() throws Exception {
		AcpJsonMapper mapper = AcpJsonMapper.createDefault();
		PipedOutputStream clientOut = new PipedOutputStream();
		PipedInputStream agentIn = new PipedInputStream(clientOut, 65536);
		PipedOutputStream agentOut = new PipedOutputStream();
		PipedInputStream clientIn = new PipedInputStream(agentOut, 65536);

		AcpAsyncAgent agent = AcpAgent.async(new StdioAcpAgentTransport(mapper, agentIn, agentOut))
			.initializeHandler(request -> Mono.just(AcpSchema.InitializeResponse.ok()))
			.setSessionModeHandler(request -> Mono.just(new AcpSchema.SetSessionModeResponse()))
			.closeSessionHandler(request -> Mono.just(new AcpSchema.CloseSessionResponse()))
			.build();
		agent.start().block(TIMEOUT);

		// Read the agent's output lines by id until the three responses are in.
		CompletableFuture<Map<Object, String>> lines = CompletableFuture.supplyAsync(() -> {
			Map<Object, String> byId = new HashMap<>();
			try {
				BufferedReader reader = new BufferedReader(new InputStreamReader(clientIn, StandardCharsets.UTF_8));
				String line;
				while (byId.size() < 3 && (line = reader.readLine()) != null) {
					Map<?, ?> message = mapper.readValue(line, Map.class);
					if (message != null && message.containsKey("id")) {
						byId.put(message.get("id"), line);
					}
				}
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
			return byId;
		});

		try {
			for (String request : List.of(
					"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1}}",
					"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/set_mode\",\"params\":{\"sessionId\":\"s\",\"modeId\":\"m\"}}",
					"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"session/close\",\"params\":{\"sessionId\":\"s\"}}")) {
				clientOut.write((request + "\n").getBytes(StandardCharsets.UTF_8));
				clientOut.flush();
			}

			Map<Object, String> responses = lines.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			assertThat(responses.get(2)).isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}");
			assertThat(responses.get(3)).isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}");
		}
		finally {
			agent.closeGracefully().block(TIMEOUT);
			clientOut.close();
			clientIn.close();
		}
	}

}

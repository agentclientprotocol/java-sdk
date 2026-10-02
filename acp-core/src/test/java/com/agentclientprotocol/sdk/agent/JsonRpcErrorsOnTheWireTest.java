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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exact JSON-RPC errors a stdio agent writes, line by line, for requests a handler
 * refuses or that are not valid requests at all.
 */
class JsonRpcErrorsOnTheWireTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1}}";

	/**
	 * JSON-RPC 2.0 section 5.1: {@code message} is a short description and {@code code} the
	 * number. The SDK used to write {@code "[-32602] unknown directive: #nope"}, the Java
	 * exception's log form, as the message.
	 */
	@Test
	void anErrorMessageIsWrittenWithoutItsCode() throws Exception {
		List<String> lines = exchange(
				"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/prompt\",\"params\":{\"sessionId\":\"s\",\"prompt\":[]}}");

		assertThat(lines).contains(
				"{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32602,\"message\":\"unknown directive: #nope\"}}");
	}

	/** Sends {@code initialize} and then each line, and returns every line the agent wrote, one per request. */
	static List<String> exchange(String... requests) throws Exception {
		AcpJsonMapper mapper = AcpJsonMapper.createDefault();
		PipedOutputStream clientOut = new PipedOutputStream();
		PipedInputStream agentIn = new PipedInputStream(clientOut, 65536);
		PipedOutputStream agentOut = new PipedOutputStream();
		PipedInputStream clientIn = new PipedInputStream(agentOut, 65536);

		AcpAsyncAgent agent = AcpAgent.async(new StdioAcpAgentTransport(mapper, agentIn, agentOut))
			.initializeHandler(request -> Mono.just(AcpSchema.InitializeResponse.ok()))
			.promptHandler((request, context) -> Mono
				.error(new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS, "unknown directive: #nope")))
			.build();
		agent.start().block(TIMEOUT);

		int expected = requests.length + 1;
		CompletableFuture<List<String>> lines = CompletableFuture.supplyAsync(() -> {
			List<String> read = new ArrayList<>();
			try {
				BufferedReader reader = new BufferedReader(new InputStreamReader(clientIn, StandardCharsets.UTF_8));
				String line;
				while (read.size() < expected && (line = reader.readLine()) != null) {
					read.add(line);
				}
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
			return read;
		});

		try {
			List<String> all = new ArrayList<>();
			all.add(INITIALIZE);
			all.addAll(List.of(requests));
			for (String request : all) {
				clientOut.write((request + "\n").getBytes(StandardCharsets.UTF_8));
				clientOut.flush();
			}
			return lines.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		}
		finally {
			agent.closeGracefully().block(TIMEOUT);
			clientOut.close();
			clientIn.close();
		}
	}

}

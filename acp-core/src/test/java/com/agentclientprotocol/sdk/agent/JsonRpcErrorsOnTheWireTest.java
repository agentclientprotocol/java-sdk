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

	/**
	 * JSON-RPC 2.0 section 4: a request's method is a string, its id a string, number or
	 * null, and its jsonrpc exactly "2.0"; anything else is -32600 Invalid Request (section
	 * 5.1), answered with the request's id when that id is readable, else null. Before, a
	 * numeric method was answered -32601 and the other two were executed.
	 */
	@Test
	void anInvalidRequestIsAnsweredInvalidRequest() throws Exception {
		List<String> lines = exchange("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":5}",
				"{\"jsonrpc\":\"2.0\",\"id\":{\"a\":1},\"method\":\"session/prompt\",\"params\":{\"sessionId\":\"s\",\"prompt\":[]}}",
				"{\"jsonrpc\":\"1.0\",\"id\":4,\"method\":\"session/prompt\",\"params\":{\"sessionId\":\"s\",\"prompt\":[]}}");

		assertThat(lines).contains(
				"{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32600,\"message\":\"Invalid Request\"}}",
				"{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"Invalid Request\"}}",
				"{\"jsonrpc\":\"2.0\",\"id\":4,\"error\":{\"code\":-32600,\"message\":\"Invalid Request\"}}");
	}

	/** Params the method cannot read are -32602 Invalid params (section 5.1), not -32603. */
	@Test
	void paramsOfTheWrongTypeAreInvalidParams() throws Exception {
		List<String> lines = exchange(
				"{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"session/prompt\",\"params\":{\"sessionId\":\"s\",\"prompt\":\"not a list\"}}");

		assertThat(lines).anySatisfy(line -> assertThat(line)
			.startsWith("{\"jsonrpc\":\"2.0\",\"id\":5,\"error\":{\"code\":-32602,\"message\":\"Invalid params\""));
	}

	/**
	 * A required field missing (session/new without cwd) or of the wrong JSON type (cwd: 42)
	 * is -32602 too. Before, the first reached the handler as null and the second was
	 * coerced to the string "42", and both succeeded.
	 */
	@Test
	void aMissingOrMistypedRequiredFieldIsInvalidParams() throws Exception {
		List<String> lines = exchange("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"session/new\",\"params\":{\"mcpServers\":[]}}",
				"{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"session/new\",\"params\":{\"cwd\":42,\"mcpServers\":[]}}",
				"{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"session/new\",\"params\":{\"cwd\":\"/w\",\"mcpServers\":[]}}");

		assertThat(lines).contains(
				"{\"jsonrpc\":\"2.0\",\"id\":6,\"error\":{\"code\":-32602,\"message\":\"Invalid params\",\"data\":\"missing required field: cwd\"}}",
				"{\"jsonrpc\":\"2.0\",\"id\":8,\"result\":{\"sessionId\":\"s\"}}");
		assertThat(lines).anySatisfy(line -> assertThat(line)
			.startsWith("{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":{\"code\":-32602,\"message\":\"Invalid params\""));
	}

	/**
	 * A notification whose params cannot be read gets no answer (it has no id) and must not
	 * end the connection: the request after it is still answered. Before, the handler's
	 * error ended the agent's inbound stream.
	 */
	@Test
	void aNotificationWithUnreadableParamsIsSkipped() throws Exception {
		List<String> lines = exchange(
				"{\"jsonrpc\":\"2.0\",\"method\":\"session/cancel\",\"params\":{\"sessionId\":42}}",
				"{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"session/new\",\"params\":{\"cwd\":\"/w\",\"mcpServers\":[]}}");

		assertThat(lines).contains("{\"jsonrpc\":\"2.0\",\"id\":9,\"result\":{\"sessionId\":\"s\"}}");
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
			.newSessionHandler(request -> Mono.just(new AcpSchema.NewSessionResponse("s", null)))
			.cancelHandler(notification -> Mono.empty())
			.promptHandler((request, context) -> Mono
				.error(new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS, "unknown directive: #nope")))
			.build();
		agent.start().block(TIMEOUT);

		// One answer per request; a notification gets none.
		int expected = 1 + (int) java.util.Arrays.stream(requests).filter(r -> r.contains("\"id\"")).count();
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

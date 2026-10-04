/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.MockAcpClientTransport;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.spec.AcpError;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * A caller catches one type for a request that failed with an error code: {@link AcpError}, for
 * the peer's error response and for a response the SDK rejected itself alike. {@code data} tells
 * the two apart.
 */
class RejectedResponseErrorTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	@Test
	void aResultWithoutARequiredFieldFailsWithAcpError() {
		AcpSyncClient client = clientAnswering(Map.of(AcpSchema.METHOD_SESSION_PROMPT, Map.of()));

		AcpError error = catchThrowableOfType(AcpError.class,
				() -> client.prompt(new AcpSchema.PromptRequest("s-1", List.of(new AcpSchema.TextContent("hi")))));

		assertThat(error).isNotNull();
		assertThat(error.getCode()).isEqualTo(AcpErrorCodes.INTERNAL_ERROR);
		assertThat(error.getMessage()).isEqualTo("The response to session/prompt lacks the required field stopReason");
		assertThat(error.getData()).isEqualTo(
				Map.of("reason", "missing-required-field", "method", "session/prompt", "field", "stopReason"));
		client.close();
	}

	@Test
	void aNestedMissingFieldIsNamedByItsPath() {
		AcpSyncClient client = clientAnswering(Map.of(AcpSchema.METHOD_SESSION_NEW,
				Map.of("sessionId", "s-1", "modes", Map.of("availableModes", List.of()))));

		AcpError error = catchThrowableOfType(AcpError.class,
				() -> client.newSession(new AcpSchema.NewSessionRequest("/", List.of())));

		assertThat(error).isNotNull();
		assertThat(error.getMessage()).doesNotContain("-32603");
		assertThat(error.getData()).isEqualTo(
				Map.of("reason", "missing-required-field", "method", "session/new", "field", "modes.currentModeId"));
		client.close();
	}

	@Test
	void aResponseWithoutAResultFailsWithAcpError() {
		AcpSyncClient client = clientAnswering(Map.of(AcpSchema.METHOD_SESSION_NEW, NO_RESULT));

		AcpError error = catchThrowableOfType(AcpError.class,
				() -> client.newSession(new AcpSchema.NewSessionRequest("/", List.of())));

		assertThat(error).isNotNull();
		assertThat(error.getCode()).isEqualTo(AcpErrorCodes.INTERNAL_ERROR);
		assertThat(error.getMessage()).isEqualTo("The response to session/new carried no result");
		assertThat(error.getData()).isEqualTo(Map.of("reason", "missing-result", "method", "session/new"));
		client.close();
	}

	@Test
	void aResultOfTheWrongShapeFailsWithAcpError() {
		AcpSyncClient client = clientAnswering(
				Map.of(AcpSchema.METHOD_SESSION_NEW, Map.of("sessionId", Map.of("not", "a string"))));

		AcpError error = catchThrowableOfType(AcpError.class,
				() -> client.newSession(new AcpSchema.NewSessionRequest("/", List.of())));

		assertThat(error).isNotNull();
		assertThat(error.getCode()).isEqualTo(AcpErrorCodes.INTERNAL_ERROR);
		assertThat(error.getMessage()).startsWith("The response to session/new could not be read");
		assertThat(error.getData()).isInstanceOfSatisfying(Map.class, data -> {
			assertThat(data.get("reason")).isEqualTo("unreadable-result");
			assertThat(data.get("method")).isEqualTo("session/new");
		});
		client.close();
	}

	@Test
	void aPeersErrorResponseIsStillAcpErrorWithThePeersData() {
		AcpSyncClient client = clientAnswering(Map.of(AcpSchema.METHOD_SESSION_NEW,
				new AcpSchema.JSONRPCError(AcpErrorCodes.INTERNAL_ERROR, "boom", Map.of("details", "disk full"))));

		AcpError error = catchThrowableOfType(AcpError.class,
				() -> client.newSession(new AcpSchema.NewSessionRequest("/", List.of())));

		assertThat(error).isNotNull();
		assertThat(error.getData()).isEqualTo(Map.of("details", "disk full"));
		client.close();
	}

	private static final Object NO_RESULT = new Object();

	/** A client whose agent answers initialize, and each given method with the given result. */
	private static AcpSyncClient clientAnswering(Map<String, Object> results) {
		Map<String, Object> answers = new HashMap<>(results);
		answers.put(AcpSchema.METHOD_INITIALIZE, Map.of("protocolVersion", 1, "agentCapabilities", Map.of()));
		MockAcpClientTransport transport = new MockAcpClientTransport((t, message) -> {
			if (message instanceof AcpSchema.JSONRPCRequest request && answers.containsKey(request.method())) {
				Object answer = answers.get(request.method());
				AcpSchema.JSONRPCResponse response = answer instanceof AcpSchema.JSONRPCError error
						? new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(), null, error)
						: new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, request.id(),
								answer == NO_RESULT ? null : answer, null);
				t.simulateIncomingMessage(response);
			}
		});
		AcpSyncClient client = AcpClient.sync(transport).requestTimeout(TIMEOUT).build();
		client.initialize();
		return client;
	}

}

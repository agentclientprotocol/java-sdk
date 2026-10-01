/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.io.IOException;

import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The answer to a message that cannot be read: JSON-RPC 2.0 prescribes -32700 for text that
 * is not JSON and -32600 for JSON that is no JSON-RPC message, both with {@code "id": null}.
 */
class UnreadableMessageResponseTest {

	private final AcpJsonMapper jsonMapper = AcpJsonMapper.createDefault();

	@ParameterizedTest
	@ValueSource(strings = { "{not json", "{\"jsonrpc\":\"2.0\",", "" })
	void textThatIsNotJsonIsAParseError(String text) throws IOException {
		AcpSchema.JSONRPCResponse response = AcpSchema.unreadableMessageResponse(jsonMapper, text);

		assertThat(response.id()).isNull();
		assertThat(response.result()).isNull();
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(AcpErrorCodes.PARSE_ERROR);
		assertThat(response.error().message()).isEqualTo("Parse error");
	}

	@ParameterizedTest
	@ValueSource(strings = { "42", "\"text\"", "null", "[1,2]", "{\"jsonrpc\":\"2.0\"}" })
	void jsonThatIsNoJsonRpcMessageIsAnInvalidRequest(String text) throws IOException {
		AcpSchema.JSONRPCResponse response = AcpSchema.unreadableMessageResponse(jsonMapper, text);

		assertThat(response.id()).isNull();
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(AcpErrorCodes.INVALID_REQUEST);
		assertThat(response.error().message()).isEqualTo("Invalid Request");
	}

	@ParameterizedTest
	@ValueSource(strings = { "{not json", "42" })
	void theAnswerCarriesANullIdOnTheWire(String text) throws IOException {
		String json = jsonMapper.writeValueAsString(AcpSchema.unreadableMessageResponse(jsonMapper, text));

		assertThat(json).contains("\"id\":null").doesNotContain("\"result\"");
		assertThat(AcpSchema.deserializeJsonRpcMessage(jsonMapper, json))
			.isInstanceOfSatisfying(AcpSchema.JSONRPCResponse.class, response -> assertThat(response.id()).isNull());
	}

}

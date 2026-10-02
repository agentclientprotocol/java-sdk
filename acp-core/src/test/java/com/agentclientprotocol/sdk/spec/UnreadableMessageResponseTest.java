/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.io.IOException;

import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

	@ParameterizedTest
	@ValueSource(strings = { "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":5}",
			"{\"jsonrpc\":\"2.0\",\"method\":true}",
			"{\"jsonrpc\":\"2.0\",\"id\":{\"a\":1},\"method\":\"session/list\"}",
			"{\"jsonrpc\":\"2.0\",\"id\":[1],\"method\":\"session/list\"}",
			"{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"session/list\"}",
			"{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"session/list\"}",
			"{\"id\":1,\"method\":\"session/list\"}", "{\"jsonrpc\":2.0,\"method\":\"session/cancel\"}" })
	void anInvalidRequestIsRefused(String text) {
		assertThatThrownBy(() -> AcpSchema.deserializeJsonRpcMessage(jsonMapper, text))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(AcpSchema.unreadableMessageResponse(jsonMapper, text).error().code())
			.isEqualTo(AcpErrorCodes.INVALID_REQUEST);
	}

	/** JSON-RPC 2.0 section 5: the id is null only when it could not be read. */
	@Test
	void anInvalidRequestWithAReadableIdIsAnsweredWithIt() {
		assertThat(AcpSchema.unreadableMessageResponse(jsonMapper, "{\"jsonrpc\":\"2.0\",\"id\":\"r1\",\"method\":5}").id())
			.isEqualTo("r1");
		assertThat(AcpSchema.unreadableMessageResponse(jsonMapper, "{\"jsonrpc\":\"1.0\",\"id\":7,\"method\":\"x\"}").id())
			.isEqualTo(7);
		assertThat(AcpSchema.unreadableMessageResponse(jsonMapper, "{\"jsonrpc\":\"2.0\",\"id\":{},\"method\":\"x\"}").id())
			.isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"session/list\"}",
			"{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"session/list\",\"params\":{}}",
			"{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"session/list\"}",
			"{\"jsonrpc\":\"2.0\",\"id\":1.5,\"method\":\"session/list\"}",
			"{\"jsonrpc\":\"2.0\",\"method\":\"session/cancel\",\"params\":{}}",
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}" })
	void aValidMessageIsRead(String text) throws IOException {
		assertThat(AcpSchema.deserializeJsonRpcMessage(jsonMapper, text)).isNotNull();
	}

}

/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.io.IOException;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for JSON-RPC message deserialization.
 *
 * <p>
 * Verifies that the ACP schema can correctly deserialize JSON-RPC messages and
 * distinguish between requests, responses, and notifications.
 * </p>
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 */
class AcpJsonRpcMessageTest {

	private final AcpJsonMapper jsonMapper = AcpJsonMapper.createDefault();

	@Test
	void deserializeJsonRpcRequest() throws IOException {
		String json = """
				{
					"jsonrpc": "2.0",
					"id": 1,
					"method": "initialize",
					"params": {"protocolVersion": 1}
				}
				""";

		AcpSchema.JSONRPCMessage message = AcpSchema.deserializeJsonRpcMessage(jsonMapper, json);

		assertThat(message).isInstanceOf(AcpSchema.JSONRPCRequest.class);
		AcpSchema.JSONRPCRequest request = (AcpSchema.JSONRPCRequest) message;
		assertThat(request.jsonrpc()).isEqualTo("2.0");
		assertThat(request.id()).isEqualTo(1);
		assertThat(request.method()).isEqualTo("initialize");
		assertThat(request.params()).isNotNull();
	}

	@Test
	void deserializeJsonRpcRequestWithStringId() throws IOException {
		String json = """
				{
					"jsonrpc": "2.0",
					"id": "request-123",
					"method": "session/prompt",
					"params": {}
				}
				""";

		AcpSchema.JSONRPCMessage message = AcpSchema.deserializeJsonRpcMessage(jsonMapper, json);

		assertThat(message).isInstanceOf(AcpSchema.JSONRPCRequest.class);
		AcpSchema.JSONRPCRequest request = (AcpSchema.JSONRPCRequest) message;
		assertThat(request.id()).isEqualTo("request-123");
	}

	@Test
	void deserializeJsonRpcNotification() throws IOException {
		String json = """
				{
					"jsonrpc": "2.0",
					"method": "session/update",
					"params": {"sessionId": "test"}
				}
				""";

		AcpSchema.JSONRPCMessage message = AcpSchema.deserializeJsonRpcMessage(jsonMapper, json);

		assertThat(message).isInstanceOf(AcpSchema.JSONRPCNotification.class);
		AcpSchema.JSONRPCNotification notification = (AcpSchema.JSONRPCNotification) message;
		assertThat(notification.jsonrpc()).isEqualTo("2.0");
		assertThat(notification.method()).isEqualTo("session/update");
		assertThat(notification.params()).isNotNull();
	}

	@Test
	void deserializeJsonRpcResponse() throws IOException {
		String json = """
				{
					"jsonrpc": "2.0",
					"id": 1,
					"result": {"protocolVersion": 1}
				}
				""";

		AcpSchema.JSONRPCMessage message = AcpSchema.deserializeJsonRpcMessage(jsonMapper, json);

		assertThat(message).isInstanceOf(AcpSchema.JSONRPCResponse.class);
		AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) message;
		assertThat(response.jsonrpc()).isEqualTo("2.0");
		assertThat(response.id()).isEqualTo(1);
		assertThat(response.result()).isNotNull();
		assertThat(response.error()).isNull();
	}

	@Test
	void deserializeJsonRpcErrorResponse() throws IOException {
		String json = """
				{
					"jsonrpc": "2.0",
					"id": 1,
					"error": {
						"code": -32600,
						"message": "Invalid Request"
					}
				}
				""";

		AcpSchema.JSONRPCMessage message = AcpSchema.deserializeJsonRpcMessage(jsonMapper, json);

		assertThat(message).isInstanceOf(AcpSchema.JSONRPCResponse.class);
		AcpSchema.JSONRPCResponse response = (AcpSchema.JSONRPCResponse) message;
		assertThat(response.jsonrpc()).isEqualTo("2.0");
		assertThat(response.id()).isEqualTo(1);
		assertThat(response.result()).isNull();
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(-32600);
		assertThat(response.error().message()).isEqualTo("Invalid Request");
	}

	@Test
	void deserializeInvalidJsonThrowsException() {
		String json = "not valid json";

		assertThatThrownBy(() -> AcpSchema.deserializeJsonRpcMessage(jsonMapper, json)).isInstanceOf(IOException.class);
	}

	@Test
	void deserializeUnknownMessageTypeThrowsException() {
		String json = """
				{
					"jsonrpc": "2.0",
					"unknownField": "someone@example.com"
				}
				""";

		// The transports log this exception: it must not carry the peer's payload.
		assertThatThrownBy(() -> AcpSchema.deserializeJsonRpcMessage(jsonMapper, json))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Cannot deserialize JSONRPCMessage")
			.hasMessageNotContaining("someone@example.com");
	}

	@Test
	void deserializeJsonNullLiteralThrowsException() {
		// "null" is valid JSON but no JSON-RPC message; it must be rejected like any other
		// unrecognized structure, not fail with a NullPointerException.
		assertThatThrownBy(() -> AcpSchema.deserializeJsonRpcMessage(jsonMapper, "null"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Cannot deserialize JSONRPCMessage");
	}

	/**
	 * A request or notification JSON-RPC 2.0 does not allow is refused with an exception that
	 * every transport logs at WARN or ERROR: its message names the field at fault and never
	 * quotes the text, which is the peer's payload.
	 */
	@Test
	void anInvalidRequestIsRefusedWithoutQuotingItsText() {
		String params = "\"params\":{\"sessionId\":\"s\",\"prompt\":[{\"type\":\"text\",\"text\":\"SECRET-123\"}]}";
		java.util.Map<String, String> invalid = java.util.Map.of(
				"jsonrpc must be", "{\"id\":1,\"method\":\"session/prompt\"," + params + "}",
				"jsonrpc must be \"2.0\"", "{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"session/prompt\"," + params + "}",
				"method must be a string", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":42," + params + "}",
				"id must be a string, number or null",
				"{\"jsonrpc\":\"2.0\",\"id\":{\"x\":\"SECRET-123\"},\"method\":\"session/prompt\"," + params + "}");

		invalid.forEach((fault, json) -> assertThatThrownBy(() -> AcpSchema.deserializeJsonRpcMessage(jsonMapper, json))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Invalid Request")
			.hasMessageContaining(fault)
			.hasMessageNotContaining("SECRET-123"));
	}

}

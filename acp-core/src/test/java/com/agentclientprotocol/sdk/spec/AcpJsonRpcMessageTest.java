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
					"unknownField": "value"
				}
				""";

		assertThatThrownBy(() -> AcpSchema.deserializeJsonRpcMessage(jsonMapper, json))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Cannot deserialize JSONRPCMessage");
	}

}

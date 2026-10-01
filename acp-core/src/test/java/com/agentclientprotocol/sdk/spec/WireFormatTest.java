/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exact bytes the SDK writes for representative messages. Every JSON module runs this
 * class, so it pins the wire format across JSON libraries: a Jackson upgrade or a second
 * implementation that reorders properties, writes a discriminator twice, drops the
 * {@code NON_NULL} policy, renames an enum or loses {@code _meta} fails here, not in a
 * peer.
 *
 * <p>
 * Property order is record-component order, with a {@code @JsonTypeInfo} discriminator
 * first and a property from an accessor method (the MCP server {@code type}) last.
 * </p>
 */
class WireFormatTest {

	private final AcpJsonMapper mapper = AcpJsonMapper.createDefault();

	@Test
	void contentBlockWritesItsDiscriminatorOnceAndFirst() throws IOException {
		assertThat(mapper.writeValueAsString(new AcpSchema.TextContent("hi"))).isEqualTo("{\"type\":\"text\",\"text\":\"hi\"}");
	}

	@Test
	void sessionUpdateNotification() throws IOException {
		var notification = new AcpSchema.SessionNotification("s1",
				new AcpSchema.AgentMessageChunk("agent_message_chunk", new AcpSchema.TextContent("hi")));

		assertThat(mapper.writeValueAsString(notification)).isEqualTo(
				"{\"sessionId\":\"s1\",\"update\":{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"hi\"}}}");
	}

	@Test
	void requestEnvelopeOmitsNullsAndKeepsComponentOrder() throws IOException {
		var request = new AcpSchema.JSONRPCRequest(AcpSchema.METHOD_INITIALIZE, 1,
				new AcpSchema.InitializeRequest(1, new AcpSchema.ClientCapabilities()));

		assertThat(mapper.writeValueAsString(request)).isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
				+ "\"params\":{\"protocolVersion\":1,\"clientCapabilities\":{\"fs\":{\"readTextFile\":false,"
				+ "\"writeTextFile\":false},\"terminal\":false}}}");
	}

	@Test
	void responseEnvelopeWithEnumResult() throws IOException {
		var response = new AcpSchema.JSONRPCResponse(AcpSchema.JSONRPC_VERSION, "abc",
				new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN), null);

		assertThat(mapper.writeValueAsString(response))
			.isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"result\":{\"stopReason\":\"end_turn\"}}");
	}

	@Test
	void metaIsWrittenAsGivenIncludingNullValues() throws IOException {
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("trace", "t1");
		meta.put("absent", null);
		meta.put("nested", Map.of("a", List.of(1, 2)));

		assertThat(mapper.writeValueAsString(new AcpSchema.PromptResponse(AcpSchema.StopReason.CANCELLED, meta)))
			.isEqualTo("{\"stopReason\":\"cancelled\",\"_meta\":{\"trace\":\"t1\",\"absent\":null,\"nested\":{\"a\":[1,2]}}}");
	}

	@Test
	void toolCallWithEnums() throws IOException {
		var toolCall = new AcpSchema.ToolCall("tool_call", "t1", "Read file", AcpSchema.ToolKind.READ,
				AcpSchema.ToolCallStatus.IN_PROGRESS, List.of(), List.of(), null, null, null);

		assertThat(mapper.writeValueAsString(toolCall)).isEqualTo("{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"t1\","
				+ "\"title\":\"Read file\",\"kind\":\"read\",\"status\":\"in_progress\",\"content\":[],\"locations\":[]}");
	}

	@Test
	void mcpServersWriteTheirTransportTypeOnlyWhenTheSchemaHasOne() throws IOException {
		assertThat(mapper.writeValueAsString(new AcpSchema.McpServerStdio("fs", "/bin/mcp", List.of(), List.of())))
			.isEqualTo("{\"name\":\"fs\",\"command\":\"/bin/mcp\",\"args\":[],\"env\":[]}");
		assertThat(mapper.writeValueAsString(new AcpSchema.McpServerHttp("api", "https://x", List.of())))
			.isEqualTo("{\"name\":\"api\",\"url\":\"https://x\",\"headers\":[],\"type\":\"http\"}");
	}

	@Test
	void incomingMessageRoundTripsByteForByte() throws IOException {
		String[] lines = {
				"{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"stopReason\":\"end_turn\",\"_meta\":{\"x\":1,\"y\":[true,null]}}}",
				"{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"s\",\"update\":"
						+ "{\"sessionUpdate\":\"plan\",\"entries\":[{\"content\":\"c\",\"priority\":\"high\",\"status\":\"pending\"}]}}}",
				"{\"jsonrpc\":\"2.0\",\"id\":\"r-1\",\"error\":{\"code\":-32602,\"message\":\"Invalid params\",\"data\":{\"why\":\"x\"}}}" };
		for (String line : lines) {
			AcpSchema.JSONRPCMessage message = AcpSchema.deserializeJsonRpcMessage(mapper, line);
			assertThat(mapper.writeValueAsString(message)).isEqualTo(line);
		}
	}

}

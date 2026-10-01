/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.Map;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tool call {@code name} (stable in ACP v1, schema v1.9.1): the programmatic name of
 * the tool, on tool calls, tool call updates and the tool call of a permission request.
 */
class ToolCallNameTest {

	private static final TypeRef<Map<String, Object>> MAP = new TypeRef<>() {
	};

	private final AcpJsonMapper mapper = AcpJsonMapper.createDefault();

	private void assertWritesBack(Object value, String json) throws Exception {
		assertThat(mapper.readValue(mapper.writeValueAsString(value), MAP)).isEqualTo(mapper.readValue(json, MAP));
	}

	@Test
	void toolCallCarriesName() throws Exception {
		String json = """
				{"sessionUpdate":"tool_call","toolCallId":"t1","title":"Reading pom.xml","name":"read_file"}""";

		var call = (AcpSchema.ToolCall) mapper.readValue(json, new TypeRef<AcpSchema.SessionUpdate>() {
		});

		assertThat(call.name()).isEqualTo("read_file");
		assertWritesBack(call, json);
	}

	@Test
	void toolCallUpdateNotificationCarriesName() throws Exception {
		String json = """
				{"sessionUpdate":"tool_call_update","toolCallId":"t1","name":"read_file","status":"completed"}""";

		var update = (AcpSchema.ToolCallUpdateNotification) mapper.readValue(json,
				new TypeRef<AcpSchema.SessionUpdate>() {
				});

		assertThat(update.name()).isEqualTo("read_file");
		assertWritesBack(update, json);
	}

	@Test
	void permissionRequestToolCallCarriesName() throws Exception {
		String json = """
				{"sessionId":"s","toolCall":{"toolCallId":"t1","name":"write_file"},\
				"options":[{"optionId":"allow","name":"Allow","kind":"allow_once"}]}""";

		var request = mapper.readValue(json, AcpSchema.RequestPermissionRequest.class);

		assertThat(request.toolCall().name()).isEqualTo("write_file");
		assertWritesBack(request, json);
	}

	@Test
	void absentAndNullNameBothMeanNoName() throws Exception {
		var absent = (AcpSchema.ToolCall) mapper.readValue("{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"t\",\"title\":\"x\"}",
				new TypeRef<AcpSchema.SessionUpdate>() {
				});
		var explicitNull = (AcpSchema.ToolCall) mapper.readValue(
				"{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"t\",\"title\":\"x\",\"name\":null}",
				new TypeRef<AcpSchema.SessionUpdate>() {
				});

		assertThat(absent.name()).isNull();
		assertThat(explicitNull.name()).isNull();
		assertThat(mapper.writeValueAsString(absent)).doesNotContain("name");
	}

}

/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A variant this SDK does not know never fails the message that carries it: it reads as
 * the union's {@code Unknown*} record and writes back unchanged (see {@link AcpSchema}).
 * The JSON modules run this class against Jackson 2 and Jackson 3.
 */
class ForwardCompatibilityTest {

	private static final TypeRef<Map<String, Object>> MAP = new TypeRef<>() {
	};

	private final AcpJsonMapper mapper = AcpJsonMapper.createDefault();

	private <T> T read(String json, Class<T> type) throws Exception {
		return mapper.readValue(json, type);
	}

	/** Writes {@code value} and reads both texts as maps, so key order does not matter. */
	private void assertWritesBack(Object value, String json) throws Exception {
		assertThat(mapper.readValue(mapper.writeValueAsString(value), MAP)).isEqualTo(mapper.readValue(json, MAP));
	}

	@Test
	void unknownSessionUpdateReadsAsAValueAndWritesBackUnchanged() throws Exception {
		String json = """
				{"sessionId":"s1","update":{"sessionUpdate":"future_update","n":1,"nested":{"a":[1,2]},"_meta":{"k":"v"}}}""";

		var notification = read(json, AcpSchema.SessionNotification.class);

		assertThat(notification.sessionId()).isEqualTo("s1");
		assertThat(notification.update()).isInstanceOf(AcpSchema.UnknownSessionUpdate.class);
		var unknown = (AcpSchema.UnknownSessionUpdate) notification.update();
		assertThat(unknown.sessionUpdate()).isEqualTo("future_update");
		assertThat(unknown.fields()).containsEntry("n", 1)
			.containsEntry("nested", Map.of("a", List.of(1, 2)))
			.containsEntry("_meta", Map.of("k", "v"))
			.doesNotContainKey("sessionUpdate");
		assertWritesBack(notification, json);
	}

	@Test
	void updateWithoutDiscriminatorReadsAsUnknownWithNullType() throws Exception {
		var update = mapper.readValue("{\"title\":\"x\"}", new TypeRef<AcpSchema.SessionUpdate>() {
		});

		assertThat(update).isEqualTo(new AcpSchema.UnknownSessionUpdate(null, Map.of("title", "x")));
		assertThat(mapper.writeValueAsString(update)).isEqualTo("{\"title\":\"x\"}");
	}

	@Test
	void unknownFieldsAreUnmodifiable() {
		var unknown = new AcpSchema.UnknownSessionUpdate("x", new java.util.HashMap<>(Map.of("a", 1)));

		assertThatThrownBy(() -> unknown.fields().put("b", 2)).isInstanceOf(UnsupportedOperationException.class);
		assertThat(new AcpSchema.UnknownContentBlock("x", null).fields()).isEmpty();
	}

	@Test
	void sessionInfoUpdateRoundTrips() throws Exception {
		String json = """
				{"sessionUpdate":"session_info_update","title":"Fix the build","updatedAt":"2026-10-01T10:00:00Z","_meta":{"k":"v"}}""";

		var update = mapper.readValue(json, new TypeRef<AcpSchema.SessionUpdate>() {
		});

		assertThat(update).isEqualTo(new AcpSchema.SessionInfoUpdate("session_info_update", "Fix the build",
				"2026-10-01T10:00:00Z", Map.of("k", "v")));
		assertWritesBack(update, json);
		assertThat(mapper.writeValueAsString(new AcpSchema.SessionInfoUpdate("T", null)))
			.isEqualTo("{\"sessionUpdate\":\"session_info_update\",\"title\":\"T\"}");
	}

	@Test
	void emptySessionInfoUpdateIsLegal() throws Exception {
		var update = read("{\"sessionId\":\"s\",\"update\":{\"sessionUpdate\":\"session_info_update\"}}",
				AcpSchema.SessionNotification.class)
			.update();

		assertThat(update).isEqualTo(new AcpSchema.SessionInfoUpdate(null, null));
	}

	@Test
	void unknownContentBlockInsideAKnownUpdate() throws Exception {
		String json = """
				{"sessionUpdate":"agent_message_chunk","content":{"type":"hologram","frames":3}}""";

		var update = (AcpSchema.AgentMessageChunk) mapper.readValue(json, new TypeRef<AcpSchema.SessionUpdate>() {
		});

		assertThat(update.content()).isEqualTo(new AcpSchema.UnknownContentBlock("hologram", Map.of("frames", 3)));
		assertWritesBack(update, json);
	}

	@Test
	void unknownContentBlockInAPromptKeepsTheKnownOnes() throws Exception {
		String json = """
				{"sessionId":"s","prompt":[{"type":"text","text":"hi"},{"type":"hologram"}]}""";

		var request = read(json, AcpSchema.PromptRequest.class);

		assertThat(request.prompt()).containsExactly(new AcpSchema.TextContent("hi"),
				new AcpSchema.UnknownContentBlock("hologram", Map.of()));
		assertThat(request.text()).isEqualTo("hi");
		assertWritesBack(request, json);
	}

	@Test
	void unknownToolCallContentKeepsTheKnownItems() throws Exception {
		String json = """
				{"sessionUpdate":"tool_call","toolCallId":"t1","title":"Edit",
				 "content":[{"type":"terminal","terminalId":"term"},{"type":"patch","hunks":[]}]}""";

		var call = (AcpSchema.ToolCall) mapper.readValue(json, new TypeRef<AcpSchema.SessionUpdate>() {
		});

		assertThat(call.content()).containsExactly(new AcpSchema.ToolCallTerminal("terminal", "term"),
				new AcpSchema.UnknownToolCallContent("patch", Map.of("hunks", List.of())));
		assertWritesBack(call, json);
	}

	@Test
	void unknownConfigOptionTypeKeepsTheKnownOptions() throws Exception {
		String json = """
				{"sessionUpdate":"config_option_update","configOptions":[
				 {"type":"select","id":"model","name":"Model","currentValue":"a","options":[{"value":"a","name":"A"}]},
				 {"type":"slider","id":"temp","name":"Temperature","min":0,"max":2}]}""";

		var update = (AcpSchema.ConfigOptionUpdate) mapper.readValue(json, new TypeRef<AcpSchema.SessionUpdate>() {
		});

		assertThat(update.configOptions()).hasSize(2);
		assertThat(update.configOptions().get(0)).isInstanceOf(AcpSchema.SessionConfigSelect.class);
		assertThat(update.configOptions().get(1)).isEqualTo(new AcpSchema.UnknownSessionConfigOption("slider",
				Map.of("id", "temp", "name", "Temperature", "min", 0, "max", 2)));
		assertWritesBack(update, json);
	}

	@Test
	void unknownPermissionOutcomeReadsAsAValue() throws Exception {
		String json = """
				{"outcome":{"outcome":"deferred","until":"later"}}""";

		var response = read(json, AcpSchema.RequestPermissionResponse.class);

		assertThat(response.outcome())
			.isEqualTo(new AcpSchema.UnknownPermissionOutcome("deferred", Map.of("until", "later")));
		assertWritesBack(response, json);
	}

	@Test
	void knownVariantsWriteTheirDiscriminatorOnce() throws Exception {
		assertThat(mapper.writeValueAsString(new AcpSchema.TextContent("hi")))
			.isEqualTo("{\"type\":\"text\",\"text\":\"hi\"}");
		assertThat(mapper.writeValueAsString(new AcpSchema.PermissionSelected("allow")))
			.isEqualTo("{\"outcome\":\"selected\",\"optionId\":\"allow\"}");
	}

	@Test
	void aKnownVariantNamesItselfWhenGivenNoDiscriminator() throws Exception {
		var chunk = new AcpSchema.AgentMessageChunk(null, new AcpSchema.TextContent(null, "hi", null, null), null, null);

		assertThat(chunk.sessionUpdate()).isEqualTo("agent_message_chunk");
		assertThat(mapper.writeValueAsString(chunk))
			.isEqualTo("{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"hi\"}}");
	}

	@Test
	void aKnownVariantRejectsAnotherVariantsDiscriminator() {
		assertThatThrownBy(() -> new AcpSchema.AgentMessageChunk("agentMessage", new AcpSchema.TextContent("hi"), null, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("agentMessage")
			.hasMessageContaining("agent_message_chunk");
	}

}

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
 * A value of a closed schema enum that this SDK does not know never fails the message that
 * carries it (see {@link AcpSchema} on forward compatibility). {@code ToolKind} reads it as
 * the schema's catch-all {@code other}; every other enum keeps the wire value. The JSON
 * modules run this class against Jackson 2 and Jackson 3.
 */
class EnumForwardCompatibilityTest {

	private static final TypeRef<Map<String, Object>> MAP = new TypeRef<>() {
	};

	private final AcpJsonMapper mapper = AcpJsonMapper.createDefault();

	private void assertWritesBack(Object value, String json) throws Exception {
		assertThat(mapper.readValue(mapper.writeValueAsString(value), MAP)).isEqualTo(mapper.readValue(json, MAP));
	}

	private AcpSchema.SessionUpdate update(String json) throws Exception {
		return mapper.readValue(json, new TypeRef<AcpSchema.SessionUpdate>() {
		});
	}

	@Test
	void unknownStopReasonIsKeptAndKnownOnesAreTheConstants() throws Exception {
		var unknown = mapper.readValue("{\"stopReason\":\"paused\"}", AcpSchema.PromptResponse.class);
		var known = mapper.readValue("{\"stopReason\":\"end_turn\"}", AcpSchema.PromptResponse.class);

		assertThat(unknown.stopReason().value()).isEqualTo("paused");
		assertThat(unknown.stopReason().isKnown()).isFalse();
		assertWritesBack(unknown, "{\"stopReason\":\"paused\"}");
		assertThat(known.stopReason()).isSameAs(AcpSchema.StopReason.END_TURN);
		assertThat(known.stopReason().isKnown()).isTrue();
		assertThat(AcpSchema.StopReason.known()).extracting(AcpSchema.StopReason::value)
			.containsExactly("end_turn", "max_tokens", "max_turn_requests", "refusal", "cancelled");
	}

	@Test
	void unknownToolKindReadsAsOther() throws Exception {
		var call = (AcpSchema.ToolCall) update(
				"{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"t\",\"title\":\"x\",\"kind\":\"teleport\"}");

		assertThat(call.kind()).isEqualTo(AcpSchema.ToolKind.OTHER);
		assertThat(mapper.writeValueAsString(AcpSchema.ToolKind.SWITCH_MODE)).isEqualTo("\"switch_mode\"");
		assertThat(mapper.readValue("\"switch_mode\"", AcpSchema.ToolKind.class)).isEqualTo(AcpSchema.ToolKind.SWITCH_MODE);
	}

	@Test
	void unknownToolCallStatusIsKept() throws Exception {
		String json = "{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"t\",\"status\":\"paused\"}";

		var update = (AcpSchema.ToolCallUpdateNotification) update(json);

		assertThat(update.status()).isEqualTo(AcpSchema.ToolCallStatus.of("paused"));
		assertThat(update.status().isKnown()).isFalse();
		assertWritesBack(update, json);
		assertThat(AcpSchema.ToolCallStatus.of("in_progress")).isSameAs(AcpSchema.ToolCallStatus.IN_PROGRESS);
	}

	@Test
	void unknownPermissionOptionKindIsKept() throws Exception {
		String json = """
				{"sessionId":"s","toolCall":{"toolCallId":"t"},\
				"options":[{"optionId":"a","name":"A","kind":"allow_once"},{"optionId":"b","name":"B","kind":"allow_session"}]}""";

		var request = mapper.readValue(json, AcpSchema.RequestPermissionRequest.class);

		assertThat(request.options()).extracting(AcpSchema.PermissionOption::kind)
			.containsExactly(AcpSchema.PermissionOptionKind.ALLOW_ONCE, AcpSchema.PermissionOptionKind.of("allow_session"));
		assertWritesBack(request, json);
	}

	@Test
	void unknownPlanEntryStatusAndPriorityAreKept() throws Exception {
		String json = """
				{"sessionUpdate":"plan","entries":[{"content":"c","priority":"urgent","status":"blocked"},\
				{"content":"d","priority":"low","status":"completed"}]}""";

		var plan = (AcpSchema.Plan) update(json);

		assertThat(plan.entries().get(0).priority().value()).isEqualTo("urgent");
		assertThat(plan.entries().get(0).status().value()).isEqualTo("blocked");
		assertThat(plan.entries().get(1).priority()).isSameAs(AcpSchema.PlanEntryPriority.LOW);
		assertThat(plan.entries().get(1).status()).isSameAs(AcpSchema.PlanEntryStatus.COMPLETED);
		assertWritesBack(plan, json);
	}

	@Test
	void unknownRoleIsKept() throws Exception {
		String json = "{\"audience\":[\"user\",\"critic\"]}";

		var annotations = mapper.readValue(json, AcpSchema.Annotations.class);

		assertThat(annotations.audience()).containsExactly(AcpSchema.Role.USER, AcpSchema.Role.of("critic"));
		assertWritesBack(annotations, json);
	}

	@Test
	void valuesPrintAsTheirWireNameAndRejectNull() {
		assertThat(AcpSchema.StopReason.CANCELLED).hasToString("cancelled");
		assertThat(List.of(AcpSchema.PlanEntryPriority.HIGH, AcpSchema.Role.ASSISTANT, AcpSchema.PermissionOptionKind.REJECT_ALWAYS,
				AcpSchema.PlanEntryStatus.IN_PROGRESS, AcpSchema.ToolCallStatus.FAILED))
			.allMatch(v -> v.toString().equals(v.toString().toLowerCase()));
		assertThatThrownBy(() -> AcpSchema.StopReason.of(null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new AcpSchema.Role(null)).isInstanceOf(NullPointerException.class);
	}

}

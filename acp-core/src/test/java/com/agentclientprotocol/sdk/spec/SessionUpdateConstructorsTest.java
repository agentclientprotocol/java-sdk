/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The short constructors of the session updates leave the discriminator out: it can only
 * be the variant's own name, so only the canonical constructor takes it.
 */
class SessionUpdateConstructorsTest {

	private static final AcpSchema.TextContent TEXT = new AcpSchema.TextContent("hi");

	@Test
	void configOptionUpdateTakesTheFullList() {
		List<AcpSchema.SessionConfigOption> options = List
			.of(new AcpSchema.SessionConfigBoolean("web", "Web search", true));

		assertThat(new AcpSchema.ConfigOptionUpdate(options))
			.isEqualTo(new AcpSchema.ConfigOptionUpdate("config_option_update", options, null));
	}

	@Test
	void chunksTakeContentAndOptionalMessageId() {
		assertThat(new AcpSchema.UserMessageChunk(TEXT))
			.isEqualTo(new AcpSchema.UserMessageChunk("user_message_chunk", TEXT, null, null));
		assertThat(new AcpSchema.UserMessageChunk(TEXT, "m1"))
			.isEqualTo(new AcpSchema.UserMessageChunk("user_message_chunk", TEXT, "m1", null));
		assertThat(new AcpSchema.AgentMessageChunk(TEXT))
			.isEqualTo(new AcpSchema.AgentMessageChunk("agent_message_chunk", TEXT, null, null));
		assertThat(new AcpSchema.AgentMessageChunk(TEXT, "m1"))
			.isEqualTo(new AcpSchema.AgentMessageChunk("agent_message_chunk", TEXT, "m1", null));
		assertThat(new AcpSchema.AgentThoughtChunk(TEXT))
			.isEqualTo(new AcpSchema.AgentThoughtChunk("agent_thought_chunk", TEXT, null, null));
		assertThat(new AcpSchema.AgentThoughtChunk(TEXT, "m1"))
			.isEqualTo(new AcpSchema.AgentThoughtChunk("agent_thought_chunk", TEXT, "m1", null));
	}

	@Test
	void otherUpdatesTakeTheirFieldsOnly() {
		var entry = new AcpSchema.PlanEntry("step", AcpSchema.PlanEntryPriority.HIGH,
				AcpSchema.PlanEntryStatus.PENDING);
		assertThat(new AcpSchema.Plan(List.of(entry))).isEqualTo(new AcpSchema.Plan("plan", List.of(entry), null));

		var command = new AcpSchema.AvailableCommand("test", "Run the tests", null);
		assertThat(new AcpSchema.AvailableCommandsUpdate(List.of(command)))
			.isEqualTo(new AcpSchema.AvailableCommandsUpdate("available_commands_update", List.of(command), null));

		assertThat(new AcpSchema.CurrentModeUpdate("code"))
			.isEqualTo(new AcpSchema.CurrentModeUpdate("current_mode_update", "code", null));

		assertThat(new AcpSchema.UsageUpdate(10L, 100L))
			.isEqualTo(new AcpSchema.UsageUpdate("usage_update", 10L, 100L, null, null));
	}

}

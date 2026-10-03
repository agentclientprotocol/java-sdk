/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.List;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The short forms real code writes for the most common messages are the same values, and the
 * same JSON, as the long forms.
 */
class SchemaShortcutsTest {

	private final AcpJsonMapper mapper = AcpJsonMapper.createDefault();

	@Test
	void newSessionRequestWithOnlyACwdHasNoMcpServers() throws Exception {
		var shortForm = new AcpSchema.NewSessionRequest("/workspace");
		var longForm = new AcpSchema.NewSessionRequest("/workspace", List.of());

		assertThat(shortForm).isEqualTo(longForm);
		assertThat(mapper.writeValueAsString(shortForm)).isEqualTo(mapper.writeValueAsString(longForm))
			.isEqualTo("{\"cwd\":\"/workspace\",\"mcpServers\":[]}");
		assertThat(mapper.readValue(mapper.writeValueAsString(shortForm), AcpSchema.NewSessionRequest.class))
			.isEqualTo(shortForm);
	}

	@Test
	void newSessionResponseWithOnlyAnIdHasNoModesOrOptions() throws Exception {
		var shortForm = new AcpSchema.NewSessionResponse("s-1");
		var longForm = new AcpSchema.NewSessionResponse("s-1", null, null);

		assertThat(shortForm).isEqualTo(longForm);
		assertThat(mapper.writeValueAsString(shortForm)).isEqualTo(mapper.writeValueAsString(longForm))
			.isEqualTo("{\"sessionId\":\"s-1\"}");
		assertThat(mapper.readValue("{\"sessionId\":\"s-1\"}", AcpSchema.NewSessionResponse.class))
			.isEqualTo(shortForm);
	}

	@Test
	void aTextPromptIsOneTextBlock() throws Exception {
		var shortForm = AcpSchema.PromptRequest.text("s-1", "Fix the failing test");
		var longForm = new AcpSchema.PromptRequest("s-1", List.of(new AcpSchema.TextContent("Fix the failing test")));

		assertThat(shortForm).isEqualTo(longForm);
		assertThat(shortForm.text()).isEqualTo("Fix the failing test");
		assertThat(mapper.writeValueAsString(shortForm)).isEqualTo(mapper.writeValueAsString(longForm));
		assertThat(mapper.readValue(mapper.writeValueAsString(shortForm), AcpSchema.PromptRequest.class))
			.isEqualTo(shortForm);
	}

}

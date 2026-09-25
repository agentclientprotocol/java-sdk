/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

import java.util.List;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unknown-field policy (#10): the SDK is lenient by default so that a peer newer than this
 * SDK keeps working, and that leniency lives in the mapper, not in the schema records.
 *
 * <p>
 * This class checks the default mapper, whichever JSON module supplies it; each JSON
 * module adds its own test that a strict mapper supplied by the consumer is honoured,
 * since building one is library-specific.
 * </p>
 */
class UnknownPropertyPolicyTest {

	// The reporter's reproducer: an agent that advertises a capability this SDK does not know.
	private static final String CAPABILITIES_WITH_DRIFT = """
			{"loadSession": true, "fabricatedFeature": true}
			""";

	private final AcpJsonMapper mapper = AcpJsonMapper.createDefault();

	@Test
	void defaultMapperToleratesUnknownFieldsAndKeepsKnownOnes() throws Exception {
		AcpSchema.AgentCapabilities caps = mapper.readValue(CAPABILITIES_WITH_DRIFT, AcpSchema.AgentCapabilities.class);

		assertThat(caps.loadSession()).isTrue();
		assertThat(caps.meta()).as("unknown fields are not smuggled into _meta").isNull();
	}

	@Test
	void defaultMapperSkipsUnknownStructuredValues() throws Exception {
		String json = """
				{"sessionId": "s1", "futureObject": {"a": [1, {"b": 2}]}, "futureArray": [[1], {}],
				 "update": {"sessionUpdate": "agent_message_chunk", "futureField": {"x": 1},
				            "content": {"type": "text", "text": "hi", "futureFlag": true}}}
				""";

		AcpSchema.SessionNotification notification = mapper.readValue(json, AcpSchema.SessionNotification.class);

		assertThat(notification.sessionId()).isEqualTo("s1");
		AcpSchema.AgentMessageChunk chunk = (AcpSchema.AgentMessageChunk) notification.update();
		assertThat(((AcpSchema.TextContent) chunk.content()).text()).isEqualTo("hi");
	}

	@Test
	void defaultMapperToleratesUnknownFieldsInsideListsOfPolymorphicValues() throws Exception {
		String json = """
				{"sessionId": "s1", "prompt": [{"type": "text", "text": "a", "extra": 1},
				                               {"type": "text", "text": "b"}]}
				""";

		AcpSchema.PromptRequest request = mapper.readValue(json, AcpSchema.PromptRequest.class);

		assertThat(request.prompt()).hasSize(2);
		assertThat(request.prompt()).extracting(block -> ((AcpSchema.TextContent) block).text())
			.isEqualTo(List.of("a", "b"));
	}

}

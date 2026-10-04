/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.io.IOException;
import java.util.Map;

import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A session capability is an object on the wire ({@code {}}) or absent. The components are typed
 * {@code Object}, so the record takes care of what a caller passes, and of what a peer sends.
 */
class SessionCapabilitiesWireTest {

	private final AcpJsonMapper mapper = AcpJsonMapper.createDefault();

	@Test
	void trueIsWrittenAsAnEmptyObject() throws IOException {
		String json = mapper.writeValueAsString(new AcpSchema.SessionCapabilities(Boolean.TRUE, null, Boolean.TRUE));

		assertThat(json).contains("\"list\":{}").contains("\"resume\":{}").doesNotContain("true");
	}

	@Test
	void falseIsLeftOut() throws IOException {
		AcpSchema.SessionCapabilities capabilities = new AcpSchema.SessionCapabilities(Boolean.FALSE, Map.of(),
				Boolean.FALSE);

		assertThat(capabilities.list()).isNull();
		assertThat(mapper.writeValueAsString(capabilities)).isEqualTo("{\"close\":{}}");
	}

	@Test
	void onlyAnObjectFromThePeerCountsAsAdvertised() throws IOException {
		AcpSchema.AgentCapabilities agent = mapper.readValue(
				"{\"sessionCapabilities\":{\"list\":true,\"close\":false,\"resume\":{},\"delete\":null,"
						+ "\"fork\":\"yes\",\"additionalDirectories\":{\"x\":1}}}",
				AcpSchema.AgentCapabilities.class);

		NegotiatedCapabilities negotiated = NegotiatedCapabilities.fromAgent(agent);

		assertThat(negotiated.supportsListSessions()).isFalse();
		assertThat(negotiated.supportsCloseSession()).isFalse();
		assertThat(negotiated.supportsResumeSession()).isTrue();
		assertThat(negotiated.supportsDeleteSession()).isFalse();
		assertThat(negotiated.supportsForkSession()).isFalse();
		assertThat(negotiated.supportsAdditionalDirectories()).isTrue();
	}

}

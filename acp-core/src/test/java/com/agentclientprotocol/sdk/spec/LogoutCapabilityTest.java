/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.Map;

import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.error.AcpCapabilityException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The agent capability {@code auth.logout} (ACP v1, schema v1.9.1): the agent supports the
 * {@code logout} method.
 */
class LogoutCapabilityTest {

	private static final TypeRef<Map<String, Object>> MAP = new TypeRef<>() {
	};

	private final AcpJsonMapper mapper = AcpJsonMapper.createDefault();

	@Test
	void agentAdvertisesLogout() throws Exception {
		String json = """
				{"loadSession":true,"auth":{"logout":{"_meta":{"k":"v"}}}}""";

		var caps = mapper.readValue(json, AcpSchema.AgentCapabilities.class);

		assertThat(caps.auth()).isEqualTo(
				new AcpSchema.AgentAuthCapabilities(new AcpSchema.LogoutCapabilities(Map.of("k", "v"))));
		assertThat(mapper.readValue(mapper.writeValueAsString(caps), MAP)).isEqualTo(mapper.readValue(json, MAP));
		var negotiated = NegotiatedCapabilities.fromAgent(caps);
		assertThat(negotiated.supportsLogout()).isTrue();
		negotiated.requireLogout();
	}

	@Test
	void emptyLogoutObjectMeansSupported() throws Exception {
		var caps = new AcpSchema.AgentCapabilities(null, null, null, null, AcpSchema.AgentAuthCapabilities.withLogout(),
				null, null);

		assertThat(mapper.writeValueAsString(caps)).isEqualTo("{\"auth\":{\"logout\":{}}}");
		assertThat(NegotiatedCapabilities.fromAgent(caps).supportsLogout()).isTrue();
	}

	@Test
	void logoutIsOffUnlessAdvertised() throws Exception {
		for (String json : new String[] { "{}", "{\"auth\":{}}", "{\"auth\":{\"logout\":null}}" }) {
			var negotiated = NegotiatedCapabilities.fromAgent(mapper.readValue(json, AcpSchema.AgentCapabilities.class));

			assertThat(negotiated.supportsLogout()).as(json).isFalse();
			assertThatThrownBy(negotiated::requireLogout).isInstanceOf(AcpCapabilityException.class)
				.hasMessageContaining("auth.logout");
		}
	}

}

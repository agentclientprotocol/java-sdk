/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.Map;

import com.agentclientprotocol.sdk.annotation.UnstableAcpApi;
import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.error.AcpCapabilityException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boolean config options (stable in ACP v1 since 2026-07-06) and the client capability
 * {@code session.configOptions.boolean} that allows an agent to send them.
 */
class BooleanConfigOptionTest {

	private static final TypeRef<Map<String, Object>> MAP = new TypeRef<>() {
	};

	private final AcpJsonMapper mapper = AcpJsonMapper.createDefault();

	private void assertWritesBack(Object value, String json) throws Exception {
		assertThat(mapper.readValue(mapper.writeValueAsString(value), MAP)).isEqualTo(mapper.readValue(json, MAP));
	}

	@Test
	void booleanOptionIsStable() {
		assertThat(AcpSchema.SessionConfigBoolean.class.isAnnotationPresent(UnstableAcpApi.class)).isFalse();
	}

	@Test
	void booleanOptionRoundTrips() throws Exception {
		String json = """
				{"type":"boolean","id":"web","name":"Web search","description":"Search the web",\
				"category":"_tools","currentValue":true,"_meta":{"k":"v"}}""";

		var option = mapper.readValue(json, AcpSchema.SessionConfigOption.class);

		assertThat(option).isEqualTo(new AcpSchema.SessionConfigBoolean("boolean", "web", "Web search",
				"Search the web", "_tools", true, Map.of("k", "v")));
		assertWritesBack(option, json);
	}

	@Test
	void setBooleanOptionRequestRoundTrips() throws Exception {
		String json = """
				{"sessionId":"s","configId":"web","value":false,"type":"boolean"}""";

		assertWritesBack(AcpSchema.SetSessionConfigOptionRequest.bool("s", "web", false), json);
		assertThat(mapper.readValue(json, AcpSchema.SetSessionConfigOptionRequest.class))
			.isEqualTo(AcpSchema.SetSessionConfigOptionRequest.bool("s", "web", false));
	}

	@Test
	void clientAdvertisesBooleanConfigOptions() throws Exception {
		String json = """
				{"session":{"configOptions":{"boolean":{}}}}""";

		var caps = mapper.readValue(json, AcpSchema.ClientCapabilities.class);

		assertThat(caps.session()).isEqualTo(
				new AcpSchema.ClientSessionCapabilities(AcpSchema.SessionConfigOptionsCapabilities.withBoolean()));
		assertWritesBack(caps, json);
		var negotiated = NegotiatedCapabilities.fromClient(caps);
		assertThat(negotiated.supportsBooleanConfigOptions()).isTrue();
		negotiated.requireBooleanConfigOptions();
	}

	@Test
	void booleanConfigOptionsAreOffUnlessAdvertised() throws Exception {
		for (String json : new String[] { "{}", "{\"session\":{}}", "{\"session\":{\"configOptions\":{}}}" }) {
			var negotiated = NegotiatedCapabilities.fromClient(mapper.readValue(json, AcpSchema.ClientCapabilities.class));

			assertThat(negotiated.supportsBooleanConfigOptions()).as(json).isFalse();
			assertThatThrownBy(negotiated::requireBooleanConfigOptions).isInstanceOf(AcpCapabilityException.class)
				.hasMessageContaining("session.configOptions.boolean");
		}
	}

}

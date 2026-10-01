/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.error.AcpCapabilityException;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.TypeRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Terminal authentication (stable in ACP v1, schema v1.9.1): the {@code terminal} auth
 * method and the client's {@code auth.terminal} capability.
 */
class TerminalAuthTest {

	private static final TypeRef<Map<String, Object>> MAP = new TypeRef<>() {
	};

	private final AcpJsonMapper mapper = AcpJsonMapper.createDefault();

	private void assertWritesBack(Object value, String json) throws Exception {
		assertThat(mapper.readValue(mapper.writeValueAsString(value), MAP)).isEqualTo(mapper.readValue(json, MAP));
	}

	@Test
	void initializeResponseReadsBothMethodKinds() throws Exception {
		String json = """
				{"protocolVersion":1,"authMethods":[
				 {"id":"api-key","name":"API key","description":"Paste a key"},
				 {"type":"terminal","id":"login","name":"Log in","args":["--login"],"env":{"MODE":"tui"},"_meta":{"k":1}}]}""";

		var response = mapper.readValue(json, AcpSchema.InitializeResponse.class);

		assertThat(response.authMethods()).containsExactly(
				new AcpSchema.AuthMethodAgent("api-key", "API key", "Paste a key"),
				new AcpSchema.AuthMethodTerminal("login", "Log in", null, List.of("--login"), Map.of("MODE", "tui"),
						Map.of("k", 1)));
		assertWritesBack(response, json);
	}

	@Test
	void terminalMethodWritesItsTypeOnceAndAgentMethodNone() throws Exception {
		assertThat(mapper.writeValueAsString(new AcpSchema.AuthMethodTerminal("login", "Log in", null, null)))
			.isEqualTo("{\"id\":\"login\",\"name\":\"Log in\",\"type\":\"terminal\"}");
		assertThat(mapper.writeValueAsString(new AcpSchema.AuthMethodAgent("k", "Key", null)))
			.isEqualTo("{\"id\":\"k\",\"name\":\"Key\"}");
	}

	@Test
	void agentTypeAndUnknownTypesReadAsAgentMethods() throws Exception {
		var methods = mapper.readValue("""
				[{"type":"agent","id":"a","name":"A"},{"type":"env_var","id":"e","name":"E","vars":[]}]""",
				new TypeRef<List<AcpSchema.AuthMethod>>() {
				});

		assertThat(methods).containsExactly(new AcpSchema.AuthMethodAgent("a", "A", null),
				new AcpSchema.AuthMethodAgent("e", "E", null));
		assertThat(methods.get(1).id()).isEqualTo("e");
		assertThat(methods.get(1).name()).isEqualTo("E");
		assertThat(methods.get(1).description()).isNull();
	}

	@Test
	void clientAdvertisesTerminalAuth() throws Exception {
		String json = """
				{"fs":{"readTextFile":true,"writeTextFile":false},"terminal":true,"auth":{"terminal":true}}""";

		var caps = mapper.readValue(json, AcpSchema.ClientCapabilities.class);

		assertThat(caps.auth()).isEqualTo(new AcpSchema.AuthCapabilities(true));
		assertWritesBack(caps, json);
		assertThat(NegotiatedCapabilities.fromClient(caps).supportsTerminalAuth()).isTrue();
		NegotiatedCapabilities.fromClient(caps).requireTerminalAuth();
	}

	@Test
	void terminalAuthIsOffUnlessAdvertised() {
		var none = NegotiatedCapabilities.fromClient(new AcpSchema.ClientCapabilities());
		var off = NegotiatedCapabilities.fromClient(new AcpSchema.ClientCapabilities(null, null, null,
				new AcpSchema.AuthCapabilities(false), null, null));

		assertThat(none.supportsTerminalAuth()).isFalse();
		assertThat(off.supportsTerminalAuth()).isFalse();
		assertThatThrownBy(none::requireTerminalAuth).isInstanceOf(AcpCapabilityException.class)
			.hasMessageContaining("auth.terminal");
	}

}

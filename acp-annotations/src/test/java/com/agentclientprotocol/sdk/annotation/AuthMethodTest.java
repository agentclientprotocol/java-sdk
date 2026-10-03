/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.annotation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuthMethod} declares the two kinds of authentication method ACP v1 defines, and a
 * method is an agent method unless it says otherwise, as on the wire, where a method without a
 * {@code type} is an agent method.
 */
class AuthMethodTest {

	@AcpAgent(authMethods = @AuthMethod(id = "api-key", name = "API key"))
	static class Declared {

	}

	@Test
	void theKindsAreTheSchemas() {
		assertThat(AuthMethod.Type.values()).containsExactly(AuthMethod.Type.AGENT, AuthMethod.Type.TERMINAL);
		assertThat(AuthMethod.Type.valueOf("TERMINAL")).isSameAs(AuthMethod.Type.TERMINAL);
	}

	@Test
	void aMethodIsAnAgentMethodByDefault() {
		AuthMethod method = Declared.class.getAnnotation(AcpAgent.class).authMethods()[0];
		assertThat(method.type()).isEqualTo(AuthMethod.Type.AGENT);
		assertThat(method.description()).isEmpty();
		assertThat(method.args()).isEmpty();
		assertThat(method.env()).isEmpty();
	}

}

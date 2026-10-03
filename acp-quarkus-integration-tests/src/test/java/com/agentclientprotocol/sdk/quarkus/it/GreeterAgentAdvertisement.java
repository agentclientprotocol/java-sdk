/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.it;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the sample agent's annotations advertise on the wire: {@code agentInfo} from
 * {@code @AcpAgent(name, version)}, and {@code sessionCapabilities.close} from its
 * {@code @CloseSession} handler, and nothing it does not handle.
 */
final class GreeterAgentAdvertisement {

	private GreeterAgentAdvertisement() {
	}

	static void assertAdvertised(AcpSchema.InitializeResponse initialized) {
		assertThat(initialized.agentInfo()).isNotNull();
		assertThat(initialized.agentInfo().name()).isEqualTo("quarkus-greeter");
		assertThat(initialized.agentInfo().version()).isEqualTo("1.0.0");
		AcpSchema.AgentCapabilities capabilities = initialized.agentCapabilities();
		assertThat(capabilities).isNotNull();
		assertThat(capabilities.loadSession()).isNotEqualTo(Boolean.TRUE);
		assertThat(capabilities.sessionCapabilities()).isNotNull();
		assertThat(capabilities.sessionCapabilities().close()).isNotNull();
		assertThat(capabilities.sessionCapabilities().list()).isNull();
	}

}

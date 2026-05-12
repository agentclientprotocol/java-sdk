/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.time.Duration;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WebSocketAcpAgentTransport}.
 */
class WebSocketAcpAgentTransportTest {

	private AcpJsonMapper jsonMapper;

	@BeforeEach
	void setUp() {
		jsonMapper = AcpJsonMapper.createDefault();
	}

	@Test
	void constructorValidatesPort() {
		assertThatThrownBy(() -> new WebSocketAcpAgentTransport(0, jsonMapper))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Port");
	}

	@Test
	void constructorValidatesNegativePort() {
		assertThatThrownBy(() -> new WebSocketAcpAgentTransport(-1, jsonMapper))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Port");
	}

	@Test
	void constructorValidatesPath() {
		assertThatThrownBy(() -> new WebSocketAcpAgentTransport(8080, "", jsonMapper))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Path");
	}

	@Test
	void constructorValidatesJsonMapper() {
		assertThatThrownBy(() -> new WebSocketAcpAgentTransport(8080, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("JsonMapper");
	}

	@Test
	void idleTimeoutIsConfigurable() {
		WebSocketAcpAgentTransport transport = new WebSocketAcpAgentTransport(8080, jsonMapper)
			.idleTimeout(Duration.ofMinutes(60));

		assertThat(transport).isNotNull();
		assertThat(transport.getPort()).isEqualTo(8080);
	}

	@Test
	void defaultAcpPathIsCorrect() {
		assertThat(WebSocketAcpAgentTransport.DEFAULT_ACP_PATH).isEqualTo("/acp");
	}

	@Test
	void getPortReturnsConfiguredPort() {
		WebSocketAcpAgentTransport transport = new WebSocketAcpAgentTransport(9999, jsonMapper);
		assertThat(transport.getPort()).isEqualTo(9999);
	}

	@Test
	void startReturnsErrorOnDoubleStart() throws Exception {
		// Use a high port to avoid conflicts
		WebSocketAcpAgentTransport transport = new WebSocketAcpAgentTransport(19999, "/test", jsonMapper);

		try {
			// First start - should succeed
			transport.start(msg -> Mono.empty()).block(Duration.ofSeconds(5));

			// Second start should fail with IllegalStateException
			assertThatThrownBy(() -> transport.start(msg -> Mono.empty()).block(Duration.ofSeconds(5)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Already started");
		}
		finally {
			// Cleanup
			transport.closeGracefully().block(Duration.ofSeconds(5));
		}
	}

	@Test
	void closeGracefullyCompletesWithoutStart() {
		WebSocketAcpAgentTransport transport = new WebSocketAcpAgentTransport(8080, jsonMapper);

		// Should complete without error even when not started
		transport.closeGracefully().block(Duration.ofSeconds(5));
	}

}

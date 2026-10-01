/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.json.jackson3;

import java.util.List;
import java.util.ServiceLoader;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.AcpJsonMapperSupplier;
import com.agentclientprotocol.sdk.json.JacksonAcpJsonMapper;
import com.agentclientprotocol.sdk.json.JacksonAcpJsonMapperSupplier;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With {@code acp-json-jackson2} and {@code acp-json-jackson3} both on the classpath (the
 * transport modules bring the first, the application adds the second), the choice is
 * made by priority, not classpath order, and the system property can still pick either.
 * Runs in its own surefire execution, the only one with Jackson 2 on the classpath.
 */
class BothJsonModulesOnClasspathTest {

	private static final String PROPERTY = "acp.json.mapper.supplier";

	@AfterEach
	void clearProperty() {
		System.clearProperty(PROPERTY);
	}

	@Test
	void bothSuppliersAreDiscovered() {
		List<String> types = ServiceLoader.load(AcpJsonMapperSupplier.class)
			.stream()
			.map(provider -> provider.type().getName())
			.toList();

		assertThat(types).contains(JacksonAcpJsonMapperSupplier.class.getName(),
				Jackson3AcpJsonMapperSupplier.class.getName());
	}

	@Test
	void jackson3WinsByPriority() {
		assertThat(Jackson3AcpJsonMapperSupplier.PRIORITY).isGreaterThan(JacksonAcpJsonMapperSupplier.PRIORITY);
		assertThat(AcpJsonMapper.createDefault()).isInstanceOf(Jackson3AcpJsonMapper.class);
	}

	@Test
	void systemPropertySelectsJackson2() {
		System.setProperty(PROPERTY, JacksonAcpJsonMapperSupplier.class.getName());

		assertThat(AcpJsonMapper.createDefault()).isInstanceOf(JacksonAcpJsonMapper.class);
	}

	@Test
	void bothWriteTheSameBytes() throws Exception {
		var message = new AcpSchema.JSONRPCRequest(AcpSchema.METHOD_SESSION_PROMPT, 3,
				new AcpSchema.PromptRequest("s1", List.of(new AcpSchema.TextContent("hi"))));

		String jackson2 = new JacksonAcpJsonMapperSupplier().get().writeValueAsString(message);
		String jackson3 = new Jackson3AcpJsonMapperSupplier().get().writeValueAsString(message);

		assertThat(jackson3).isEqualTo(jackson2);
	}

}

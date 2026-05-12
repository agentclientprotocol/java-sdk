/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

import java.io.IOException;
import java.util.HashMap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that AcpJsonMapper.createDefault() discovers the Jackson implementation
 * via ServiceLoader and returns a functional mapper.
 */
class AcpJsonMapperServiceLoaderTest {

	@Test
	void createDefaultFindsJacksonSupplier() {
		AcpJsonMapper mapper = AcpJsonMapper.createDefault();
		assertThat(mapper).isNotNull();
		assertThat(mapper).isInstanceOf(JacksonAcpJsonMapper.class);
	}

	@Test
	void createDefaultReturnsNewInstanceEachCall() {
		AcpJsonMapper mapper1 = AcpJsonMapper.createDefault();
		AcpJsonMapper mapper2 = AcpJsonMapper.createDefault();
		assertThat(mapper1).isNotSameAs(mapper2);
	}

	@Test
	void createDefaultMapperIsFunctional() throws IOException {
		AcpJsonMapper mapper = AcpJsonMapper.createDefault();

		String json = "{\"key\":\"value\"}";
		HashMap<String, String> result = mapper.readValue(json, new TypeRef<HashMap<String, String>>() {
		});
		assertThat(result).containsEntry("key", "value");

		String serialized = mapper.writeValueAsString(result);
		assertThat(serialized).contains("\"key\"").contains("\"value\"");
	}

}

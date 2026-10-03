/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

import com.fasterxml.jackson.core.Version;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * acp-json-jackson2 states its Jackson floor and checks it when a mapper is created: an older
 * jackson-core (a framework's BOM pinning 2.16.1 under databind 2.22.3, say) fails at once with
 * a message naming both versions, instead of a {@code NoSuchMethodError} on the first message.
 */
class JacksonVersionsTest {

	@Test
	void aJacksonOlderThanTheFloorIsRefusedNamingFoundAndRequired() {
		assertThatThrownBy(() -> JacksonVersions.require("jackson-core",
				new Version(2, 16, 1, null, "com.fasterxml.jackson.core", "jackson-core")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("jackson-core 2.16.1")
			.hasMessageContaining("needs Jackson " + JacksonVersions.FLOOR + " or later");
	}

	@Test
	void theFloorAndTheJacksonOfQuarkusAreAccepted() {
		assertThatCode(() -> JacksonVersions.require("jackson-databind",
				new Version(2, 18, 1, null, "com.fasterxml.jackson.core", "jackson-databind")))
			.doesNotThrowAnyException();
		assertThatThrownBy(() -> JacksonVersions.require("jackson-databind",
				new Version(2, 18, 0, null, "com.fasterxml.jackson.core", "jackson-databind")))
			.isInstanceOf(IllegalStateException.class);
		// Quarkus 3.40 manages Jackson 2.21.7.
		assertThatCode(() -> JacksonVersions.require("jackson-databind",
				new Version(2, 21, 7, null, "com.fasterxml.jackson.core", "jackson-databind")))
			.doesNotThrowAnyException();
	}

	@Test
	void theJacksonOnTheClasspathIsSupportedAndTheSupplierChecksIt() {
		assertThatCode(JacksonVersions::requireSupported).doesNotThrowAnyException();
		assertThatCode(() -> new JacksonAcpJsonMapperSupplier().get()).doesNotThrowAnyException();
	}

}

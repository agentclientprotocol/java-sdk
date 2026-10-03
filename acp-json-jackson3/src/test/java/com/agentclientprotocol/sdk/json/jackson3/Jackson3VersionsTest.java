/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.json.jackson3;

import tools.jackson.core.Version;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * acp-json-jackson3 states its Jackson 3 floor and checks it when a mapper is created: a Jackson
 * outside it fails at once with a message naming the versions found and required.
 */
class Jackson3VersionsTest {

	@Test
	void aJacksonOutsideTheFloorIsRefusedNamingFoundAndRequired() {
		assertThatThrownBy(() -> Jackson3Versions.require("jackson-core",
				new Version(2, 22, 3, null, "tools.jackson.core", "jackson-core")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("jackson-core 2.22.3")
			.hasMessageContaining("needs Jackson " + Jackson3Versions.FLOOR + " or later");
	}

	@Test
	void theFloorAndTheJacksonOfSpringBootAreAccepted() {
		String[] floor = Jackson3Versions.FLOOR.split("\\.");
		assertThatCode(() -> Jackson3Versions.require("jackson-databind", new Version(3, Integer.parseInt(floor[1]),
				0, null, "tools.jackson.core", "jackson-databind")))
			.doesNotThrowAnyException();
		// Spring Boot 4.1 manages Jackson 3.1.7.
		assertThatCode(() -> Jackson3Versions.require("jackson-databind",
				new Version(3, 1, 7, null, "tools.jackson.core", "jackson-databind")))
			.doesNotThrowAnyException();
	}

	@Test
	void theJacksonOnTheClasspathIsSupportedAndTheSupplierChecksIt() {
		assertThatCode(Jackson3Versions::requireSupported).doesNotThrowAnyException();
		assertThatCode(() -> new Jackson3AcpJsonMapperSupplier().get()).doesNotThrowAnyException();
	}

}

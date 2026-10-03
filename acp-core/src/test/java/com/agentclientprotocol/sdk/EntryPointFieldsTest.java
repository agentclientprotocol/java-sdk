/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk;

import java.lang.reflect.Field;
import java.util.List;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.client.AcpClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The entry-point interfaces expose no logging API: a field of an interface is public, so a
 * {@code Logger} there was API (and a hard SLF4J type on the SDK's front door).
 */
class EntryPointFieldsTest {

	@Test
	void theEntryPointInterfacesHaveNoLoggerField() {
		for (Class<?> type : List.of(AcpAgent.class, AcpClient.class)) {
			for (Field field : type.getDeclaredFields()) {
				assertThat(field.getType().getName()).as(type.getSimpleName() + "." + field.getName())
					.isNotEqualTo("org.slf4j.Logger");
			}
		}
	}

}

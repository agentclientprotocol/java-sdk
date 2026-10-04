/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.client;

import java.util.List;

import com.agentclientprotocol.sdk.integration.AcpClientSettings;
import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;

/**
 * Whether the application asks for a client: {@code acp.client.transport.type},
 * {@code .stdio.command}, {@code .websocket.uri} or {@code .http.uri} is set. The same test as
 * {@link AcpClientSettings#hasTransport()} and the Spring Boot autoconfiguration's; another
 * transport property alone, such as {@code .websocket.connect-timeout}, names no agent.
 */
final class AcpClientTransportCondition implements Condition {

	private static final List<String> KEYS = List.of("type", "stdio.command", "websocket.uri", "http.uri");

	@Override
	public boolean matches(ConditionContext context) {
		for (String key : KEYS) {
			if (context.containsProperty(AcpClientConfiguration.PREFIX + ".transport." + key)) {
				return true;
			}
		}
		context.fail("No ACP client transport is set: " + AcpClientConfiguration.PREFIX
				+ ".transport.type, .stdio.command, .websocket.uri or .http.uri");
		return false;
	}

}

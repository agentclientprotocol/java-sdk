/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import com.agentclientprotocol.sdk.integration.AcpTransportType;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches {@code spring.acp.agent.transport.type=http} or {@code websocket}, in any case: both
 * mean the Streamable HTTP endpoint, which takes WebSocket upgrades on its path.
 */
class OnHttpAgentTransportCondition extends SpringBootCondition {

	static final String PROPERTY = "spring.acp.agent.transport.type";

	@Override
	public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
		String type = context.getEnvironment().getProperty(PROPERTY);
		if (type == null || type.isBlank()) {
			return ConditionOutcome.noMatch(PROPERTY + " is not set");
		}
		boolean http;
		try {
			http = AcpTransportType.parse(type) != AcpTransportType.STDIO;
		}
		catch (IllegalArgumentException ex) {
			// The properties binding reports the value.
			return ConditionOutcome.noMatch(PROPERTY + "=" + type + " is no transport type");
		}
		return http ? ConditionOutcome.match(PROPERTY + "=" + type)
				: ConditionOutcome.noMatch(PROPERTY + "=" + type);
	}

}

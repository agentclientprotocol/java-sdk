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
		return outcome(context, type -> type == AcpTransportType.HTTP || type == AcpTransportType.WEBSOCKET);
	}

	/**
	 * Matches when {@value #PROPERTY} names a type the test accepts; an unset or unknown value
	 * never matches (the properties binding reports an unknown one).
	 */
	static ConditionOutcome outcome(ConditionContext context, java.util.function.Predicate<AcpTransportType> test) {
		String type = context.getEnvironment().getProperty(PROPERTY);
		if (type == null || type.isBlank()) {
			return ConditionOutcome.noMatch(PROPERTY + " is not set");
		}
		boolean matches;
		try {
			matches = test.test(AcpTransportType.parse(type));
		}
		catch (IllegalArgumentException ex) {
			return ConditionOutcome.noMatch(PROPERTY + "=" + type + " is no transport type");
		}
		return matches ? ConditionOutcome.match(PROPERTY + "=" + type) : ConditionOutcome.noMatch(PROPERTY + "=" + type);
	}

}

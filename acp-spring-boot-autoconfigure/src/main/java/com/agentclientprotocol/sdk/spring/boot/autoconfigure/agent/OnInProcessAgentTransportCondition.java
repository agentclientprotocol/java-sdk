/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import com.agentclientprotocol.sdk.integration.AcpTransportType;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches {@code spring.acp.agent.transport.type=in-process}, in any case and with {@code -} or
 * {@code _}: the agent is served to the application's own client.
 */
class OnInProcessAgentTransportCondition extends SpringBootCondition {

	@Override
	public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
		return OnHttpAgentTransportCondition.outcome(context, type -> type == AcpTransportType.IN_PROCESS);
	}

}

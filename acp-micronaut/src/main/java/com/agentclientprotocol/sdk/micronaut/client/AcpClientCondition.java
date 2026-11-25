/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.client;

import com.agentclientprotocol.sdk.micronaut.inprocess.AcpInProcessCondition;
import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;

/**
 * Whether the application gets a client: a client transport is configured
 * ({@link AcpClientTransportCondition}), or the agent is served in process to the application's
 * own client ({@link AcpInProcessCondition}).
 */
final class AcpClientCondition implements Condition {

	private final AcpClientTransportCondition transport = new AcpClientTransportCondition();

	@Override
	public boolean matches(ConditionContext context) {
		return AcpInProcessCondition.inProcess(context) || this.transport.matches(context);
	}

}

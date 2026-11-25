/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.inprocess;

import com.agentclientprotocol.sdk.integration.AcpTransportType;
import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;

/**
 * Whether {@code acp.agent.transport.type} is {@code in-process}, in any case and with {@code -}
 * or {@code _}, and the agent is enabled: the agent is then served to the application's own
 * client. The client beans of {@code acp-micronaut} use it too.
 */
public final class AcpInProcessCondition implements Condition {

	/** The agent's transport type property. */
	static final String TYPE = "acp.agent.transport.type";

	@Override
	public boolean matches(ConditionContext context) {
		if (!inProcess(context)) {
			context.fail(TYPE + " is not in-process");
			return false;
		}
		return true;
	}

	/**
	 * Whether the context's configuration serves the agent in process.
	 * @param context the condition context
	 * @return true for {@code acp.agent.transport.type=in-process} with the agent enabled
	 */
	public static boolean inProcess(ConditionContext context) {
		if (!context.getProperty("acp.agent.enabled", Boolean.class).orElse(true)) {
			return false;
		}
		return context.getProperty(TYPE, String.class).map(value -> {
			try {
				return AcpTransportType.parse(value) == AcpTransportType.IN_PROCESS;
			}
			catch (IllegalArgumentException ex) {
				return false; // the configuration binding reports the value
			}
		}).orElse(false);
	}

}

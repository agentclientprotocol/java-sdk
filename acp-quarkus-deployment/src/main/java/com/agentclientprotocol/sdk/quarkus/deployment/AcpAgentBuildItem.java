/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.deployment;

import io.quarkus.builder.item.SimpleBuildItem;
import org.jboss.jandex.DotName;

/**
 * The application's one {@code @AcpAgent} class, present only when there is one and the
 * agent is enabled.
 *
 * @author Mark Pollack
 */
public final class AcpAgentBuildItem extends SimpleBuildItem {

	private final DotName agentClass;

	AcpAgentBuildItem(DotName agentClass) {
		this.agentClass = agentClass;
	}

	/**
	 * The annotated class.
	 * @return the class name
	 */
	public DotName agentClass() {
		return agentClass;
	}

}

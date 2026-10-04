/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.deployment;

import io.quarkus.builder.item.SimpleBuildItem;
import org.jboss.jandex.DotName;

/**
 * The application's one {@code @AcpAgent} class, as the extension's build steps pass it between
 * them: present only when there is one and {@code quarkus.acp.agent.enabled} is true. Its presence
 * makes the build add the agent host for the configured transport; without it the application is a
 * client only. A second {@code @AcpAgent} class fails the build, naming both. An application does
 * not use it.
 *
 * @author Mark Pollack
 */
public final class AcpAgentBuildItem extends SimpleBuildItem {

	private final DotName agentClass;

	AcpAgentBuildItem(DotName agentClass) {
		this.agentClass = agentClass;
	}

	/**
	 * Returns the name of the annotated class.
	 * @return the class name
	 */
	public DotName agentClass() {
		return agentClass;
	}

}

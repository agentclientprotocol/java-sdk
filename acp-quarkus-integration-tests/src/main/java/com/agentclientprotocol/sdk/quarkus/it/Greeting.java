/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.it;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** A collaborator injected into the agent, configured from application.properties. */
@ApplicationScoped
public class Greeting {

	private final String prefix;

	Greeting(@ConfigProperty(name = "greeting.prefix", defaultValue = "Hello") String prefix) {
		this.prefix = prefix;
	}

	String greet(String name) {
		return prefix + ", " + name + "!";
	}

}

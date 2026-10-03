/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.sample;

import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;

/** An ordinary application bean, injected into the agent. */
@Singleton
public class Greeter {

	private final String prefix;

	/**
	 * Creates the greeter.
	 * @param prefix what every answer starts with, from {@code sample.prefix}
	 */
	public Greeter(@Property(name = "sample.prefix", defaultValue = "echo: ") String prefix) {
		this.prefix = prefix;
	}

	/**
	 * The answer's first chunk.
	 * @return the prefix
	 */
	public String prefix() {
		return prefix;
	}

}

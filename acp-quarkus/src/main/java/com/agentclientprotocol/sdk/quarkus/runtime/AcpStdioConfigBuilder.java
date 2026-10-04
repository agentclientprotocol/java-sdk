/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.util.Map;

import io.quarkus.runtime.configuration.ConfigBuilder;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;

/**
 * Configuration defaults for an application that serves its agent over stdio, where standard output
 * carries the protocol: {@code quarkus.log.console.stderr=true} (the console log goes to standard
 * error), {@code quarkus.banner.enabled=false} and {@code quarkus.http.host-enabled=false} (no HTTP
 * listener). They are a low-ordinal source (50), so the application's own configuration still wins.
 * The extension adds it for a stdio agent, for static and runtime initialization alike, because the
 * console log handler is created during static initialization. Part of the extension's wiring; an
 * application does not use it directly.
 *
 * @author Mark Pollack
 */
public class AcpStdioConfigBuilder implements ConfigBuilder {

	/**
	 * Below every application source (application.properties is 250), above the defaults
	 * the configuration roots declare, which a default-values source would not override.
	 */
	static final int ORDINAL = 50;

	static final Map<String, String> DEFAULTS = Map.of("quarkus.log.console.stderr", "true", "quarkus.banner.enabled",
			"false", "quarkus.http.host-enabled", "false");

	@Override
	public SmallRyeConfigBuilder configBuilder(SmallRyeConfigBuilder builder) {
		return builder.withSources(new PropertiesConfigSource(DEFAULTS, "ACP stdio agent defaults", ORDINAL));
	}

}

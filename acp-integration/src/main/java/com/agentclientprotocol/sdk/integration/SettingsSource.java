/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Plain key/value configuration, for a framework that has no binding of its own onto the settings
 * builders: {@link AcpAgentSettings#from} and {@link AcpClientSettings#from} read their keys
 * through it. A framework that binds typed configuration objects (Spring Boot and Micronaut
 * {@code @ConfigurationProperties}, Quarkus {@code @ConfigMapping}) does not need it; it calls
 * the builders instead.
 *
 * <p>Implementations adapt the framework's configuration: keys are full property names in kebab
 * case, such as {@code acp.client.transport.stdio.args}, and turning the framework's own key
 * forms (camel case, environment variables) into that form is the adapter's job. One string
 * lookup cannot express a list or a map, so each has its own method. Values are read once, when
 * {@code from} runs, on the caller's thread.
 */
public interface SettingsSource {

	/**
	 * Returns the value of a key as written. The reader trims it and treats a blank value as
	 * not set.
	 * @param key the full property name
	 * @return the value, or null when the key is not set
	 */
	@Nullable String get(String key);

	/**
	 * Returns the list under a key, such as the stdio agent's arguments, in order.
	 * @param key the full property name
	 * @return the values, empty when the key is not set; no element may be null
	 */
	List<String> list(String key);

	/**
	 * Returns the map under a key, such as the stdio agent's environment. Map keys keep their
	 * case, since environment variable names are case-sensitive.
	 * @param key the full property name
	 * @return the entries, empty when the key is not set; no key or value may be null
	 */
	Map<String, String> map(String key);

}

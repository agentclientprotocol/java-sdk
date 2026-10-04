/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Key/value configuration a framework offers, read by {@link AcpAgentSettings#from} and
 * {@link AcpClientSettings#from}. Keys are full, kebab-case property names, such as
 * {@code acp.client.transport.stdio.args}; normalising the framework's own key forms to kebab
 * case is the adapter's job. A single string lookup cannot express a list or a map, so each
 * has its own method.
 */
public interface SettingsSource {

	/**
	 * The value of a key.
	 * @param key the full property name
	 * @return the value, or null when the key is not set
	 */
	@Nullable String get(String key);

	/**
	 * The list under a key, such as the stdio agent's arguments.
	 * @param key the full property name
	 * @return the values, empty when the key is not set
	 */
	List<String> list(String key);

	/**
	 * The map under a key, such as the stdio agent's environment. Map keys keep their case.
	 * @param key the full property name
	 * @return the entries, empty when the key is not set
	 */
	Map<String, String> map(String key);

}

/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Flat properties as a settings source: lists comma-separated, maps as {@code key.name=value}. */
record MapSettingsSource(Map<String, String> properties) implements SettingsSource {

	static MapSettingsSource of(String... keyValues) {
		Map<String, String> properties = new LinkedHashMap<>();
		for (int i = 0; i < keyValues.length; i += 2) {
			properties.put(keyValues[i], keyValues[i + 1]);
		}
		return new MapSettingsSource(properties);
	}

	@Override
	public String get(String key) {
		return properties.get(key);
	}

	@Override
	public List<String> list(String key) {
		String value = properties.get(key);
		return (value == null) ? List.of() : Arrays.stream(value.split(",")).map(String::trim).toList();
	}

	@Override
	public Map<String, String> map(String key) {
		Map<String, String> entries = new LinkedHashMap<>();
		properties.forEach((name, value) -> {
			if (name.startsWith(key + ".")) {
				entries.put(name.substring(key.length() + 1), value);
			}
		});
		return entries;
	}

}

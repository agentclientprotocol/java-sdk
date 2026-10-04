/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Reads typed values from a {@link SettingsSource} under a prefix. A value that does not parse
 * fails with the full key in the message.
 */
final class SettingsReader {

	private static final Pattern AMOUNT = Pattern.compile("(\\d+)\\s*([a-zA-Z]*)");

	private static final Map<String, ChronoUnit> DURATION_UNITS = Map.of("", ChronoUnit.MILLIS, "ms",
			ChronoUnit.MILLIS, "s", ChronoUnit.SECONDS, "m", ChronoUnit.MINUTES, "h", ChronoUnit.HOURS, "d",
			ChronoUnit.DAYS);

	private static final Map<String, Long> SIZE_UNITS = Map.of("", 1L, "B", 1L, "K", 1024L, "KB", 1024L, "M",
			1024L * 1024, "MB", 1024L * 1024, "G", 1024L * 1024 * 1024, "GB", 1024L * 1024 * 1024);

	private final SettingsSource source;

	private final String prefix;

	SettingsReader(SettingsSource source, String prefix) {
		this.source = source;
		this.prefix = prefix.isEmpty() || prefix.endsWith(".") ? prefix : prefix + ".";
	}

	String key(String name) {
		return prefix + name;
	}

	@Nullable String string(String name) {
		String value = source.get(key(name));
		return (value == null || value.isBlank()) ? null : value.trim();
	}

	List<String> list(String name) {
		return source.list(key(name));
	}

	Map<String, String> map(String name) {
		return source.map(key(name));
	}

	boolean bool(String name, boolean defaultValue) {
		String value = string(name);
		if (value == null) {
			return defaultValue;
		}
		if ("true".equalsIgnoreCase(value)) {
			return true;
		}
		if ("false".equalsIgnoreCase(value)) {
			return false;
		}
		throw invalid(name, value, "true or false");
	}

	@Nullable Integer integer(String name) {
		String value = string(name);
		if (value == null) {
			return null;
		}
		try {
			return Integer.valueOf(value);
		}
		catch (NumberFormatException ex) {
			throw invalid(name, value, "an integer");
		}
	}

	@Nullable URI uri(String name) {
		String value = string(name);
		if (value == null) {
			return null;
		}
		try {
			return new URI(value);
		}
		catch (URISyntaxException ex) {
			throw invalid(name, value, "a URI");
		}
	}

	@Nullable AcpTransportType transportType(String name) {
		String value = string(name);
		if (value == null) {
			return null;
		}
		try {
			return AcpTransportType.parse(value);
		}
		catch (IllegalArgumentException ex) {
			throw invalid(name, value, "stdio, websocket or http");
		}
	}

	/**
	 * A duration: ISO-8601 ({@code PT30S}), or an amount with a unit {@code ms}, {@code s},
	 * {@code m}, {@code h} or {@code d} ({@code 30s}); a bare number is milliseconds.
	 */
	@Nullable Duration duration(String name) {
		String value = string(name);
		if (value == null) {
			return null;
		}
		try {
			if (value.regionMatches(true, 0, "P", 0, 1)) {
				return Duration.parse(value);
			}
			Matcher matcher = matching(value);
			ChronoUnit unit = DURATION_UNITS.get(matcher.group(2).toLowerCase(Locale.ROOT));
			if (unit != null) {
				return Duration.of(Long.parseLong(matcher.group(1)), unit);
			}
		}
		catch (DateTimeParseException | IllegalArgumentException ex) {
			// reported below
		}
		throw invalid(name, value, "a duration such as 30s or PT30S");
	}

	/**
	 * A size in bytes: a bare number, or an amount with a unit {@code B}, {@code KB}, {@code MB}
	 * or {@code GB} (powers of 1024).
	 */
	@Nullable Long bytes(String name) {
		String value = string(name);
		if (value == null) {
			return null;
		}
		try {
			Matcher matcher = matching(value);
			Long multiplier = SIZE_UNITS.get(matcher.group(2).toUpperCase(Locale.ROOT));
			if (multiplier != null) {
				return Math.multiplyExact(Long.parseLong(matcher.group(1)), multiplier);
			}
		}
		catch (IllegalArgumentException | ArithmeticException ex) {
			// reported below
		}
		throw invalid(name, value, "a size such as 16MB");
	}

	/** An amount and its unit; IllegalArgumentException when the value is neither. */
	private static Matcher matching(String value) {
		Matcher matcher = AMOUNT.matcher(value);
		if (!matcher.matches()) {
			throw new IllegalArgumentException(value);
		}
		return matcher;
	}

	private IllegalArgumentException invalid(String name, String value, String expected) {
		return new IllegalArgumentException(key(name) + "=" + value + " is not " + expected);
	}

}

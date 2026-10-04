/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

/**
 * The endpoint's {@code Origin} rule, one for every host: a request without the header (a
 * non-browser client) and one from a loopback origin is accepted; any other origin only when
 * the application lists it. A browser sends {@code Origin} on every cross-origin request and
 * on every WebSocket handshake, so this stops a web page the user visits from driving a local
 * agent, directly or through DNS rebinding.
 *
 * @author Mark Pollack
 */
final class OriginPolicy {

	/** The allowed-origins entry that accepts any origin. */
	static final String ANY = "*";

	private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

	private OriginPolicy() {
	}

	/** The listed origins as they are compared: lower case, without a trailing slash. */
	static Set<String> normalize(Set<String> origins) {
		return origins.stream().map(OriginPolicy::normalize).collect(Collectors.toUnmodifiableSet());
	}

	private static String normalize(String origin) {
		String trimmed = origin.trim().toLowerCase(Locale.ROOT);
		return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
	}

	static boolean isAllowed(@Nullable String origin, Set<String> allowedOrigins) {
		if (origin == null) {
			return true;
		}
		String normalized = normalize(origin);
		return isLoopback(normalized) || allowedOrigins.contains(ANY) || allowedOrigins.contains(normalized);
	}

	private static boolean isLoopback(String origin) {
		try {
			URI uri = new URI(origin);
			String scheme = uri.getScheme();
			String host = uri.getHost();
			return ("http".equals(scheme) || "https".equals(scheme)) && host != null
					&& LOOPBACK_HOSTS.contains(host) && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
					&& uri.getRawUserInfo() == null;
		}
		catch (URISyntaxException e) {
			return false;
		}
	}

}

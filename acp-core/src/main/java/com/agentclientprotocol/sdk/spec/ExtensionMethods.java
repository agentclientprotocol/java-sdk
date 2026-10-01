/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import org.jspecify.annotations.Nullable;

/**
 * The protocol's rule for extension methods: ACP reserves every method name that starts
 * with an underscore ({@code _}) for custom requests and notifications (ACP v1,
 * Extensibility, Extension Methods). The SDK's extension APIs accept only such names, so a
 * custom handler can never replace a protocol method, and a custom send can never
 * impersonate one.
 *
 * @author Mark Pollack
 */
public final class ExtensionMethods {

	/** The prefix the protocol reserves for extension method names. */
	public static final String PREFIX = "_";

	private ExtensionMethods() {
	}

	/**
	 * Whether a method name is an extension method name.
	 * @param method the method name
	 * @return true if it starts with {@value #PREFIX}
	 */
	public static boolean isExtension(@Nullable String method) {
		return method != null && method.startsWith(PREFIX);
	}

	/**
	 * Checks that a method name is an extension method name.
	 * @param method the method name
	 * @return the method name
	 * @throws IllegalArgumentException if it is null or does not start with {@value #PREFIX}
	 */
	public static String requireExtension(@Nullable String method) {
		if (method == null || !isExtension(method)) {
			throw new IllegalArgumentException("Extension method names must start with '" + PREFIX
					+ "' (ACP reserves the other names for the protocol): " + method);
		}
		return method;
	}

}

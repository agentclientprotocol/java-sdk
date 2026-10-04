/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import org.jspecify.annotations.Nullable;

/**
 * ACP's rule for extension methods, the custom requests and notifications two peers agree on
 * outside the protocol: the method name must start with an underscore ({@code _}), such as
 * {@code _example.com/workspace/buffers} (ACP v1, Extensibility). Every extension API of the SDK
 * checks names with {@link #requireExtension}: the builders' {@code extRequestHandler} and
 * {@code extNotificationHandler}, the facades' {@code sendExtRequest} and
 * {@code sendExtNotification}, and the {@code @ExtRequest} and {@code @ExtNotification} annotations
 * when an annotated agent is built. So a custom handler can never replace a protocol method, and a
 * custom send can never pass for one. Applications call it only to check a name themselves.
 *
 * <p>How the SDK treats extension methods, the same on both sides:
 * <ul>
 * <li>A request no handler serves is answered "Method not found" ({@code -32601}); a notification
 * no handler serves is ignored, as ACP asks, with a warning in the log.</li>
 * <li>Params the peer omitted arrive as an empty object. Params that cannot be read as the
 * handler's params type are answered "Invalid params" ({@code -32602}) for a request; for a
 * notification the failure is logged and the notification dropped.</li>
 * <li>A request handler must produce a result: one that produces none (an empty {@code Mono}, or
 * {@code null}) is answered with an internal error ({@code -32603}). Answer with an empty map when
 * there is nothing to return.</li>
 * <li>When the peer answers {@code "result": null}, the sender's {@code Mono} completes empty and
 * the sync API returns {@code null}.</li>
 * <li>They are outside ACP's lifecycle: the SDK does not wait for {@code initialize} before sending
 * or serving them, and checks no capability. ACP asks peers to advertise their extensions in the
 * {@code _meta} of their capabilities.</li>
 * </ul>
 *
 * @author Mark Pollack
 */
public final class ExtensionMethods {

	/** The prefix ACP reserves for extension method names: {@value}. */
	public static final String PREFIX = "_";

	private ExtensionMethods() {
	}

	/**
	 * Returns whether a method name is an extension method name.
	 * @param method the method name, or {@code null}
	 * @return true if it starts with {@value #PREFIX}; false for {@code null}
	 */
	public static boolean isExtension(@Nullable String method) {
		return method != null && method.startsWith(PREFIX);
	}

	/**
	 * Checks that a method name is an extension method name.
	 * @param method the method name
	 * @return the method name, unchanged
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

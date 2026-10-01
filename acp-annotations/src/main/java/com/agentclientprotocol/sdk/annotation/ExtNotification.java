/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as the handler for a custom extension notification from the client.
 *
 * <p>ACP reserves method names that start with an underscore ({@code _}) for extensions
 * (ACP v1, Extensibility). The name given here must start with one; discovery rejects any
 * other. An extension notification that no handler serves is ignored, as the protocol
 * asks.
 *
 * <p>The method takes at most one parameter, which receives the notification's params
 * read as that parameter's type: a record, a {@code Map<String, Object>} for the raw
 * object, or any other type the JSON mapper can read. It returns {@code void}.
 *
 * <p>Example usage:
 * <pre>{@code
 * @ExtNotification("_example.com/file_opened")
 * void fileOpened(FileOpened notification) {
 *     recentFiles.add(notification.path());
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @see ExtRequest
 * @see AcpAgent
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ExtNotification {

	/**
	 * The extension method name, starting with {@code _}.
	 * @return the method name
	 */
	String value();

}

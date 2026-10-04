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
 * Marks the {@link AcpAgent} method that receives a custom extension notification: the client sends
 * a notification whose method is the name given here, which starts with {@code _}, and the method
 * acts on it. A notification gets no answer, so the method returns {@code void}; a value it returns
 * is ignored. Declare one for each one-way message outside ACP that the agent and its clients agree
 * on, such as the client saying a file was opened. Without an {@code @ExtNotification} method for a
 * name, the agent ignores that notification, as ACP asks. Clients send it with
 * {@code sendExtNotification} on {@code AcpAsyncClient} or {@code AcpSyncClient}.
 *
 * <p>It differs from {@link ExtRequest} only in having no answer. The name rules are the same:
 * building the agent rejects a name that does not start with an underscore, and two methods for one
 * name, with an {@code IllegalArgumentException}. An exception the method throws, or params that
 * cannot be read as its params type, is logged and the notification dropped; the client is not
 * told.
 *
 * <p>The method can take one params parameter, which receives the notification's params read as
 * that parameter's type: a record, a {@code Map<String, Object>} for the raw object, or any other
 * type the JSON mapper can read; omitted params arrive as an empty object. It can also take the
 * connection parameters (see {@link AcpAgent}); a {@link SessionId}, {@link ConfigId} or
 * {@link ConfigValue} parameter is rejected when the agent is built.
 *
 * <p>Example usage:
 * <pre>{@code
 * record FileOpened(String path) {}
 *
 * private final Set<String> recentFiles = ConcurrentHashMap.newKeySet();
 *
 * @ExtNotification("_example.com/file_opened")
 * public void fileOpened(FileOpened notification) {
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
	 * The extension method's name, which must start with {@code _}, such as
	 * {@code "_example.com/file_opened"}.
	 * @return the method name
	 */
	String value();

}

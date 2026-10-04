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
 * Marks the {@link AcpAgent} method that answers a custom extension request: the client sends a
 * request whose method is the name given here, which starts with {@code _}, and the method returns
 * the result. Declare one for each feature outside ACP that the agent and its clients agree on,
 * such as a client asking which files the agent has open. Without an {@code @ExtRequest} method for
 * a name, the agent answers that request with "Method not found" ({@code -32601}). Clients send it
 * with {@code sendExtRequest} on {@code AcpAsyncClient} or {@code AcpSyncClient}.
 *
 * <p>ACP reserves names that start with an underscore for extensions (ACP v1, Extensibility):
 * building the agent rejects any other name with an {@code IllegalArgumentException}, and rejects
 * two methods for one name. The SDK checks no capability for it and does not wait for
 * {@code initialize}: a client may call it at any time. ACP asks agents to advertise their
 * extensions in the {@code _meta} of their capabilities, which an {@link Initialize} method can
 * add. The rules every extension method follows are on {@code ExtensionMethods} in
 * {@code acp-core}.
 *
 * <p>The method can take one params parameter, which receives the request's params read as that
 * parameter's type: a record, a {@code Map<String, Object>} for the raw object, or any other type
 * the JSON mapper can read. Omitted params arrive as an empty object, and params that cannot be
 * read as the type are answered "Invalid params" ({@code -32602}). It can also take the connection
 * parameters (see {@link AcpAgent}); a {@link SessionId}, {@link ConfigId} or {@link ConfigValue}
 * parameter is rejected when the agent is built, since an extension method has no session. It
 * returns the result, any value the JSON mapper can write, or a {@code Mono} of one. A {@code void}
 * method is rejected when the agent is built, and a {@code null} result or an empty {@code Mono} is
 * answered with an internal error ({@code -32603}): return an empty map when there is nothing to
 * return.
 *
 * <p>Example usage:
 * <pre>{@code
 * record BufferQuery(String language) {}
 * record Buffers(List<String> paths) {}
 *
 * private final Map<String, String> openBuffers = new ConcurrentHashMap<>(); // path to language
 *
 * @ExtRequest("_example.com/workspace/buffers")
 * public Buffers buffers(BufferQuery query) {
 *     List<String> paths = openBuffers.entrySet().stream()
 *         .filter(entry -> entry.getValue().equals(query.language()))
 *         .map(Map.Entry::getKey)
 *         .toList();
 *     return new Buffers(paths);
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @see ExtNotification
 * @see AcpAgent
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ExtRequest {

	/**
	 * The extension method's name, which must start with {@code _}, such as
	 * {@code "_example.com/workspace/buffers"}.
	 * @return the method name
	 */
	String value();

}

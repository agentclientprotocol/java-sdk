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
 * Marks a method as the handler for a custom extension request from the client.
 *
 * <p>ACP reserves method names that start with an underscore ({@code _}) for extensions
 * (ACP v1, Extensibility). The name given here must start with one; discovery rejects any
 * other. A request for an extension method that no handler serves is answered with
 * "Method not found" (-32601).
 *
 * <p>The method takes at most one parameter, which receives the request's params read as
 * that parameter's type: a record, a {@code Map<String, Object>} for the raw object, or
 * any other type the JSON mapper can read. An omitted params arrives as an empty object.
 *
 * <p>The method returns the result, any value the JSON mapper can write, or a
 * {@code Mono} of it. A null result (or an empty {@code Mono}) answers the request with an
 * internal error: return an empty map when there is nothing to return.
 *
 * <p>Example usage:
 * <pre>{@code
 * @ExtRequest("_example.com/workspace/buffers")
 * BuffersResponse buffers(BuffersRequest request) {
 *     return new BuffersResponse(openBuffers(request.language()));
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
	 * The extension method name, starting with {@code _}.
	 * @return the method name
	 */
	String value();

}

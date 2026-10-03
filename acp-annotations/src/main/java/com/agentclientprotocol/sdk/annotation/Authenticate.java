/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the {@link AcpAgent} method that answers {@code authenticate}: the client logs in with one
 * of the authentication methods the agent listed ({@code authMethods}) in its {@code initialize}
 * response, and the method checks the login. Declare those methods on the class, in
 * {@link AcpAgent#authMethods()}; they are advertised without an {@link Initialize} method, and an
 * agent that declares an agent-type method without an {@code @Authenticate} method fails to build. Declare one when the user must log in before the
 * agent can work. Without an {@code @Authenticate} method the agent answers {@code authenticate}
 * with "Method not found" ({@code -32601}).
 *
 * <p>The SDK does not track whether a client has logged in. A handler method that needs a login,
 * such as a {@link NewSession} method, checks for one itself and, when there is none, throws an
 * {@code AcpProtocolException} with {@code AcpErrorCodes.AUTHENTICATION_REQUIRED}
 * ({@code -32000}). This method throws the same exception to reject a failed login.
 *
 * <p>The method can take an {@code AuthenticateRequest}, which carries the chosen
 * {@code methodId}, and the connection parameters (see {@link AcpAgent}). It returns an
 * {@code AuthenticateResponse}, or a {@code Mono} of one.
 *
 * <p>Example usage:
 * <pre>{@code
 * // on the class: @AcpAgent(authMethods = @AuthMethod(id = "api-key", name = "API key"))
 * @Authenticate
 * public AuthenticateResponse authenticate(AuthenticateRequest request) {
 *     if (System.getenv("EXAMPLE_API_KEY") == null) {
 *         throw new AcpProtocolException(AcpErrorCodes.AUTHENTICATION_REQUIRED,
 *                 "Set EXAMPLE_API_KEY and log in again");
 *     }
 *     return new AuthenticateResponse();
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @since 0.80.0
 * @see AcpAgent
 * @see Logout
 * @see AuthMethod
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Authenticate {

}

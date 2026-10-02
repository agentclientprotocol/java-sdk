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
 * Marks a method as the handler for authenticate requests.
 *
 * <p>The annotated method handles the {@code authenticate} JSON-RPC method, which a
 * client calls with one of the authentication methods the agent advertised in its
 * initialize response ({@code authMethods}).
 *
 * <p>The method can have the following parameter (optional):
 * <ul>
 *   <li>{@code AuthenticateRequest} - the authenticate request, carrying the chosen
 *   {@code methodId}</li>
 * </ul>
 *
 * <p>The method should return one of:
 * <ul>
 *   <li>{@code AuthenticateResponse} - the authenticate response</li>
 *   <li>{@code Mono<AuthenticateResponse>} - for async handling</li>
 * </ul>
 * To reject the attempt, throw an {@code AcpProtocolException}, such as one with code
 * {@code AcpErrorCodes.AUTHENTICATION_REQUIRED}.
 *
 * <p>Example usage:
 * <pre>{@code
 * @Authenticate
 * public AuthenticateResponse authenticate(AuthenticateRequest req) {
 *     credentials.login(req.methodId());
 *     return new AuthenticateResponse();
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @since 0.80.0
 * @see AcpAgent
 * @see Logout
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Authenticate {

}

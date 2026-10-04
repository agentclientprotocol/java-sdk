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
 * Marks the {@link AcpAgent} method that answers {@code logout}: the client asks the agent to end
 * the current login, and the method clears the credentials the agent stored for it. Declare one,
 * together with an {@link Authenticate} method, when users can log out from the client. Without a
 * {@code @Logout} method the agent answers {@code logout} with "Method not found"
 * ({@code -32601}).
 *
 * <p>ACP lets a client call {@code logout} only when the agent advertised {@code auth.logout} in
 * its {@code initialize} response; the agent advertises it for you when it has this method.
 *
 * <p>The method can take a {@code LogoutRequest} and the connection parameters (see
 * {@link AcpAgent}). It must return a {@code LogoutResponse} (not {@code void}), or a
 * {@code Mono} of one.
 *
 * <p>Example usage:
 * <pre>{@code
 * private final AtomicReference<String> token = new AtomicReference<>();
 *
 * @Logout
 * public LogoutResponse logout(LogoutRequest request) {
 *     token.set(null);
 *     return new LogoutResponse();
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @see AcpAgent
 * @see Authenticate
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Logout {

}

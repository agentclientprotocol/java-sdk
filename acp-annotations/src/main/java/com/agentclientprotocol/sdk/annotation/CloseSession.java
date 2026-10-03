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
 * Marks the {@link AcpAgent} method that answers {@code session/close}: the client is done with an
 * active session, and the method stops the session's work and frees what it holds. Declare one when
 * the agent keeps per-session state or runs work that should end with the session. Without a
 * {@code @CloseSession} method the agent answers {@code session/close} with "Method not found"
 * ({@code -32601}).
 *
 * <p>ACP lets a client call {@code session/close} only when the agent advertised
 * {@code sessionCapabilities.close} in its {@link Initialize} response, and requires the agent to
 * cancel any running work in the session as if {@code session/cancel} had arrived. The SDK does not
 * do this for you: closing neither stops a running {@link Prompt} method nor calls the
 * {@link Cancel} method. To remove a stored session for good, the client uses
 * {@link DeleteSession}.
 *
 * <p>The method can take a {@code CloseSessionRequest}, a {@link SessionId @SessionId}
 * {@code String} and the connection parameters (see {@link AcpAgent}). It must return a
 * {@code CloseSessionResponse} (not {@code void}), or a {@code Mono} of one.
 *
 * <p>Example usage:
 * <pre>{@code
 * private final Map<String, AtomicBoolean> stopFlags = new ConcurrentHashMap<>();
 *
 * @CloseSession
 * public CloseSessionResponse close(@SessionId String sessionId) {
 *     AtomicBoolean stop = stopFlags.remove(sessionId);
 *     if (stop != null) {
 *         stop.set(true);  // the session's prompt method checks this flag
 *     }
 *     return new CloseSessionResponse();
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @since 1.0.0
 * @see AcpAgent
 * @see Cancel
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CloseSession {

}

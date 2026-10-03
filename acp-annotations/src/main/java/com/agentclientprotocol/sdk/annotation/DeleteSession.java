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
 * Marks the {@link AcpAgent} method that answers {@code session/delete}: the client removes a
 * stored session for good, and the method deletes it so that {@code session/list} no longer
 * returns it. Declare one, together with a {@link ListSessions} method, when users can clean up
 * their session history. Without a {@code @DeleteSession} method the agent answers
 * {@code session/delete} with "Method not found" ({@code -32601}).
 *
 * <p>ACP lets a client call {@code session/delete} only when the agent advertised
 * {@code sessionCapabilities.delete} in its {@code initialize} response; the agent advertises it
 * for you when it has this method. To end an active session without deleting it, the client uses
 * {@link CloseSession}.
 *
 * <p>The method can take a {@code DeleteSessionRequest}, a {@link SessionId @SessionId}
 * {@code String} and the connection parameters (see {@link AcpAgent}). It must return a
 * {@code DeleteSessionResponse} (not {@code void}), or a {@code Mono} of one.
 *
 * <p>Example usage:
 * <pre>{@code
 * private final Map<String, List<String>> history = new ConcurrentHashMap<>();
 *
 * @DeleteSession
 * public DeleteSessionResponse delete(@SessionId String sessionId) {
 *     history.remove(sessionId);
 *     return new DeleteSessionResponse();
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @since 1.0.0
 * @see AcpAgent
 * @see CloseSession
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DeleteSession {

}

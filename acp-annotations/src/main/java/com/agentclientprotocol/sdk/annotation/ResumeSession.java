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
 * Marks the {@link AcpAgent} method that answers {@code session/resume}: the client reconnects to a
 * session the agent stored earlier, and the method restores it without replaying the conversation.
 * Declare one instead of a {@link LoadSession} method when the agent can continue a session but
 * cannot send its history. Without a {@code @ResumeSession} method the agent answers
 * {@code session/resume} with "Method not found" ({@code -32601}).
 *
 * <p>ACP lets a client call {@code session/resume} only when the agent advertised
 * {@code sessionCapabilities.resume} in its {@link Initialize} response, and does not allow the
 * agent to send the earlier conversation as session updates before the method returns.
 *
 * <p>The method can take a {@code ResumeSessionRequest} (session id, {@code cwd} and
 * {@code mcpServers}), a {@link SessionId @SessionId} {@code String} and the connection parameters
 * (see {@link AcpAgent}). It returns a {@code ResumeSessionResponse} with the session's modes and
 * config options, or a {@code Mono} of one.
 *
 * <p>Example usage:
 * <pre>{@code
 * private final Map<String, String> workingDirectories = new ConcurrentHashMap<>();
 *
 * @ResumeSession
 * public ResumeSessionResponse resume(ResumeSessionRequest request) {
 *     workingDirectories.put(request.sessionId(), request.cwd());
 *     return new ResumeSessionResponse(null, null);  // no modes, no config options
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @since 1.0.0
 * @see AcpAgent
 * @see LoadSession
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ResumeSession {

}

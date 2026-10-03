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
 * Marks the {@link AcpAgent} method that answers {@code session/load}: the client reopens a session
 * the agent stored earlier, and the method restores it and replays its conversation to the client.
 * Declare one when the agent keeps conversations, so that a user can return to one. Without a
 * {@code @LoadSession} method the agent answers {@code session/load} with "Method not found"
 * ({@code -32601}).
 *
 * <p>ACP lets a client call {@code session/load} only when the agent advertised
 * {@code loadSession} in its {@link Initialize} response. It also requires the agent to send the
 * whole conversation, as session updates like those of a prompt turn, before the method returns:
 * take an {@code AcpSyncAgent} parameter and call its {@code sendSessionUpdate}. To reopen a
 * session without the replay, the client uses {@link ResumeSession}.
 *
 * <p>The method can take a {@code LoadSessionRequest} (session id, {@code cwd} and
 * {@code mcpServers}), a {@link SessionId @SessionId} {@code String} and the connection parameters
 * (see {@link AcpAgent}). It returns a {@code LoadSessionResponse} with the session's modes and
 * config options, or a {@code Mono} of one.
 *
 * <p>Example usage:
 * <pre>{@code
 * private final Map<String, List<String>> history = new ConcurrentHashMap<>();
 *
 * @LoadSession
 * public LoadSessionResponse load(LoadSessionRequest request, AcpSyncAgent agent) {
 *     for (String message : history.getOrDefault(request.sessionId(), List.of())) {
 *         agent.sendSessionUpdate(request.sessionId(),
 *                 new AgentMessageChunk(new TextContent(message)));
 *     }
 *     return new LoadSessionResponse(null, null);  // no modes, no config options
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @since 1.0.0
 * @see AcpAgent
 * @see NewSession
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LoadSession {

}

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
 * Marks the {@link AcpAgent} method that answers {@code session/new}: the client starts a new ACP
 * session (a conversation) in a working directory, with the MCP servers the agent should use, and
 * the method returns the new session's id. Declare one to choose the ids, set up per-session state,
 * or offer modes and config options for the session. Without a {@code @NewSession} method the agent
 * answers with a random UUID as the session id and no modes or config options.
 *
 * <p>The SDK keeps no list of sessions: later requests reach their handler methods with whatever
 * session id the client sends. Keep the sessions you create, for example in a concurrent map, and
 * answer an unknown id with an {@code AcpProtocolException}, such as one with
 * {@code AcpErrorCodes.RESOURCE_NOT_FOUND}.
 *
 * <p>The method can take a {@code NewSessionRequest}, which carries {@code cwd} and
 * {@code mcpServers}, and the connection parameters (see {@link AcpAgent}). It cannot take a
 * {@link SessionId} parameter: the session does not exist yet. It returns a
 * {@code NewSessionResponse}, or a {@code Mono} of one.
 *
 * <p>Example usage:
 * <pre>{@code
 * private final Map<String, String> workingDirectories = new ConcurrentHashMap<>();
 *
 * @NewSession
 * public NewSessionResponse newSession(NewSessionRequest request) {
 *     String sessionId = UUID.randomUUID().toString();
 *     workingDirectories.put(sessionId, request.cwd());
 *     return new NewSessionResponse(sessionId, null, null);  // no modes, no config options
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @since 1.0.0
 * @see AcpAgent
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface NewSession {

}

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
 * Marks the {@link AcpAgent} method that answers {@code session/list}: the client asks which
 * sessions the agent knows, optionally only those of one working directory, and the method returns
 * them, a page at a time. Clients use it to show session history and let the user switch sessions;
 * listing restores nothing ({@link LoadSession} and {@link ResumeSession} do). Without a
 * {@code @ListSessions} method the agent answers {@code session/list} with "Method not found"
 * ({@code -32601}).
 *
 * <p>ACP lets a client call {@code session/list} only when the agent advertised
 * {@code sessionCapabilities.list} in its {@link Initialize} response. Pages are linked by a cursor
 * the agent chooses: return it as {@code nextCursor} while more sessions remain, and the client
 * sends it back as {@code cursor}.
 *
 * <p>The method can take a {@code ListSessionsRequest} (the {@code cwd} filter and the
 * {@code cursor}, each of which may be null) and the connection parameters (see
 * {@link AcpAgent}). It returns a {@code ListSessionsResponse}, or a {@code Mono} of one.
 *
 * <p>Example usage:
 * <pre>{@code
 * private final Map<String, String> workingDirectories = new ConcurrentHashMap<>();
 *
 * @ListSessions
 * public ListSessionsResponse list(ListSessionsRequest request) {
 *     List<SessionInfo> sessions = workingDirectories.entrySet().stream()
 *         .filter(session -> request.cwd() == null || request.cwd().equals(session.getValue()))
 *         .map(session -> new SessionInfo(session.getKey(), session.getValue()))
 *         .toList();
 *     return new ListSessionsResponse(sessions);
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
public @interface ListSessions {

}

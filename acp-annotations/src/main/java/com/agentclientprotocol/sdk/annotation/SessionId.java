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
 * Marks a {@code String} parameter of a handler method that receives the session id of the request
 * being handled, the same value as the request's {@code sessionId()}. Use it when the method needs
 * only the id, or does not take the request at all.
 *
 * <p>It works in the methods of requests that name a session: {@link Prompt}, {@link Cancel},
 * {@link LoadSession}, {@link ResumeSession}, {@link CloseSession}, {@link DeleteSession},
 * {@link SetSessionMode}, {@link SetSessionConfigOption} and {@link ForkSession}. In
 * {@link Initialize}, {@link Authenticate}, {@link Logout}, {@link NewSession} and
 * {@link ListSessions} methods there is no session id, and building the agent fails with an
 * {@code IllegalStateException} naming the method; in {@link ExtRequest} and
 * {@link ExtNotification} methods registering the agent fails with an
 * {@code IllegalArgumentException}. A parameter that is not a {@code String} is not supplied
 * either, and building the agent fails the same way.
 *
 * <p>Example usage:
 * <pre>{@code
 * @Prompt
 * public PromptResponse prompt(@SessionId String sessionId, SyncPromptContext context) {
 *     context.sendMessage("This is session " + sessionId);
 *     return PromptResponse.endTurn();
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @since 1.0.0
 * @see AcpAgent
 * @see Prompt
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SessionId {

}

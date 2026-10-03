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
 * Marks the {@link AcpAgent} method that answers {@code session/set_mode}: the client switches a
 * session to another of the modes the agent offered, such as "ask" or "code", and the method makes
 * the session work in it. Declare one when the agent returns modes ({@code SessionModeState}) from
 * its {@link NewSession}, {@link LoadSession} or {@link ResumeSession} method; the agent must then
 * also have a {@link NewSession} method, since the default {@code session/new} offers none
 * (building the agent fails otherwise). Without a {@code @SetSessionMode} method the agent answers
 * {@code session/set_mode} with "Method not found" ({@code -32601}).
 *
 * <p>ACP requires the mode id to be one of the {@code availableModes} the agent offered; the SDK
 * does not check it. When the agent changes a session's mode itself, it tells the client with a
 * {@code CurrentModeUpdate} session update. ACP plans to replace modes with config options
 * ({@link SetSessionConfigOption}); until then, an agent with mode-like settings should offer
 * both.
 *
 * <p>The method can take a {@code SetSessionModeRequest} (session id and {@code modeId}), a
 * {@link SessionId @SessionId} {@code String} and the connection parameters (see
 * {@link AcpAgent}). It must return a {@code SetSessionModeResponse} (not {@code void}), or a
 * {@code Mono} of one.
 *
 * <p>Example usage:
 * <pre>{@code
 * private final Map<String, String> modes = new ConcurrentHashMap<>();
 *
 * @SetSessionMode
 * public SetSessionModeResponse setMode(SetSessionModeRequest request) {
 *     modes.put(request.sessionId(), request.modeId());
 *     return new SetSessionModeResponse();
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
public @interface SetSessionMode {

}

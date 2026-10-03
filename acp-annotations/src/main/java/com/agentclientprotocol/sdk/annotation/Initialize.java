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
 * Marks the {@link AcpAgent} method that answers {@code initialize}: the first request on a
 * connection, in which the client sends its protocol version, capabilities and name, and the
 * method answers with the agent's. Declare one to advertise capabilities, such as
 * {@code loadSession} for a {@link LoadSession} method, to list the authentication methods
 * {@link Authenticate} accepts, or to name the agent ({@code agentInfo}). Without an
 * {@code @Initialize} method the agent answers {@code InitializeResponse.ok()}: protocol version 1
 * and default capabilities, which advertise no optional method.
 *
 * <p>The client's capabilities are recorded before this method runs, so a
 * {@code NegotiatedCapabilities} parameter already holds them here, and every later handler method
 * on the connection gets the same ones.
 *
 * <p>The method can take an {@code InitializeRequest} and the connection parameters (see
 * {@link AcpAgent}). It returns an {@code InitializeResponse}, or a {@code Mono} of one.
 *
 * <p>Example usage, for an agent that also has a {@link LoadSession} method:
 * <pre>{@code
 * @Initialize
 * public InitializeResponse initialize(InitializeRequest request) {
 *     return InitializeResponse.ok(AgentCapabilities.builder().loadSession(true).build());
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
public @interface Initialize {

}

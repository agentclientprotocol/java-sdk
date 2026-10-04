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
 * method answers with the agent's. It is optional: without one, the agent answers with the
 * response derived from its class (see {@link AcpAgent}): the capabilities its handler
 * annotations imply, the {@link AcpAgent#authMethods()}, and {@code agentInfo} from
 * {@link AcpAgent#name()} and {@link AcpAgent#version()}, in the protocol version negotiated with
 * the client. Declare one to look at the client's request, or to advertise what the annotations
 * cannot express, such as a capability's {@code _meta}.
 *
 * <p><b>Merge rule.</b> The derived response is the base, and the response this method returns
 * is laid over it:
 * <ul>
 *   <li>a capability is advertised when either side advertises it, so returning
 *   {@code InitializeResponse.ok()} keeps every derived capability, and a capability a handler
 *   implies cannot be withdrawn here (remove the handler instead)</li>
 *   <li>{@code authMethods} are the derived methods followed by the returned ones, a returned
 *   method replacing a derived method with the same id</li>
 *   <li>{@code protocolVersion}, and {@code agentInfo} and {@code _meta} when not null, are the
 *   returned ones</li>
 * </ul>
 *
 * <p>The client's capabilities are recorded before this method runs, so a
 * {@code NegotiatedCapabilities} parameter already holds them here, and every later handler method
 * on the connection gets the same ones.
 *
 * <p>The method can take an {@code InitializeRequest} and the connection parameters (see
 * {@link AcpAgent}). It returns an {@code InitializeResponse}, or a {@code Mono} of one.
 *
 * <p>Example usage, adding a capability no annotation implies to the derived ones:
 * <pre>{@code
 * @Initialize
 * public InitializeResponse initialize(InitializeRequest request) {
 *     return InitializeResponse.ok(AgentCapabilities.builder()
 *         .sessionCapabilities(new SessionCapabilities(null, null, null, null, Map.of(), null))
 *         .build());
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

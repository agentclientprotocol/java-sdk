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
 * Marks a method as the handler for ACP initialization requests.
 *
 * <p>The annotated method handles the {@code initialize} JSON-RPC method,
 * which is called when a client first connects to negotiate capabilities.
 *
 * <p>The method can have the following parameters (all optional):
 * <ul>
 *   <li>{@code InitializeRequest} - the initialization request</li>
 * </ul>
 *
 * <p>The method should return one of:
 * <ul>
 *   <li>{@code InitializeResponse} - the initialization response</li>
 *   <li>{@code Mono<InitializeResponse>} - for async handling</li>
 * </ul>
 *
 * <p><b>Optional.</b> An agent does not need this method to advertise what it supports:
 * without one, it answers {@code initialize} with the response derived from its class (see
 * {@link AcpAgent}): the capabilities its handler annotations imply, the
 * {@link AcpAgent#authMethods()}, and {@code agentInfo} from {@link AcpAgent#name()} and
 * {@link AcpAgent#version()}, in the protocol version negotiated with the client. Write one to
 * look at the client's request, or to advertise what the annotations cannot express.
 *
 * <p><b>Merge rule.</b> The derived response is the base, and the response this method
 * returns is laid over it:
 * <ul>
 *   <li>a capability is advertised when either side advertises it, so returning
 *   {@code InitializeResponse.ok()} keeps every derived capability, and a capability a
 *   handler implies cannot be withdrawn here (remove the handler instead)</li>
 *   <li>{@code authMethods} are the derived methods followed by the returned ones, a returned
 *   method replacing a derived method with the same id</li>
 *   <li>{@code protocolVersion}, and {@code agentInfo} and {@code _meta} when not null, are
 *   the returned ones</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * @Initialize
 * public InitializeResponse init(InitializeRequest req) {
 *     // adds sessionCapabilities.additionalDirectories to the derived capabilities
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

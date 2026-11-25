/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Answers the agent's {@code session/request_permission} requests to the application's client: the
 * agent asks before a tool call, offering options such as "allow once" and "reject". Declare one as
 * a bean, and the client a framework builds (Spring Boot, Micronaut or Quarkus) asks it. Without
 * one, every request is refused: the client selects the first reject option the agent offered, or
 * answers {@code cancelled} when it offered none. More than one such bean fails the startup.
 *
 * <p>It may block, for example while a person decides: it runs on a thread meant for blocking work,
 * never on a transport thread, and the agent waits for the answer within its request timeout.
 *
 * <pre>{@code
 * @Component
 * class Approvals implements AcpPermissionHandler {
 *     public AcpSchema.RequestPermissionResponse onPermissionRequest(AcpSchema.RequestPermissionRequest request) {
 *         return new AcpSchema.RequestPermissionResponse(
 *             new AcpSchema.PermissionSelected(request.options().get(0).optionId()));
 *     }
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @see AcpSessionUpdateHandler
 */
@FunctionalInterface
public interface AcpPermissionHandler {

	/**
	 * Answers one permission request.
	 * @param request the tool call the agent asks about and the options it offers
	 * @return the answer: an option selected, or {@code cancelled}
	 */
	AcpSchema.RequestPermissionResponse onPermissionRequest(AcpSchema.RequestPermissionRequest request);

}

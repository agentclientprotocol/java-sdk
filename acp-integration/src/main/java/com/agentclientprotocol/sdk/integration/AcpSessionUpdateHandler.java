/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * Receives the {@code session/update} notifications the agent sends the application's client: the
 * streamed message chunks, tool calls, plans and the rest of a turn. Declare it as a bean, and the
 * client a framework builds (Spring Boot, Micronaut or Quarkus) passes it every update. Every such
 * bean receives every update; without one, updates are logged at DEBUG.
 *
 * <p>It may block: it runs on a thread meant for blocking work, never on a transport thread. A turn's
 * updates reach the handlers before the prompt's answer, so when {@code prompt(...)} returns the
 * handlers have seen the whole turn. An exception it throws is logged and does not stop later
 * updates.
 *
 * <pre>{@code
 * @Component
 * class Chat implements AcpSessionUpdateHandler {
 *     public void onSessionUpdate(AcpSchema.SessionNotification notification) {
 *         if (notification.update() instanceof AcpSchema.AgentMessageChunk chunk
 *                 && chunk.content() instanceof AcpSchema.TextContent text) {
 *             stream(text.text());
 *         }
 *     }
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @see AcpPermissionHandler
 */
@FunctionalInterface
public interface AcpSessionUpdateHandler {

	/**
	 * Handles one session update.
	 * @param notification the update and the session it belongs to
	 */
	void onSessionUpdate(AcpSchema.SessionNotification notification);

}

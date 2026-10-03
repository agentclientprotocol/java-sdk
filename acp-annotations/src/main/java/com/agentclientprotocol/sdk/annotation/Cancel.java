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
 * Marks the {@link AcpAgent} method that receives {@code session/cancel}: the client asks the
 * agent to stop the session's running prompt turn, and the method tells the {@link Prompt} method
 * to stop. {@code session/cancel} is a notification, so the method returns {@code void} and
 * nothing is sent back. Without a {@code @Cancel} method the SDK still records the cancel and runs
 * the grace period described below, but the prompt method is not told until the grace period
 * ends.
 *
 * <p>The cancel does not end the turn. The prompt method should stop, send any last updates, and
 * return stop reason {@code cancelled}; until it returns, a new prompt on the session is answered
 * with {@code -32600} (Invalid request). If it has not returned when the cancel grace period ends
 * (60 seconds unless set with {@code AcpAgentSupport.Builder.cancelGracePeriod}), the SDK answers
 * the prompt {@code cancelled} itself and interrupts the prompt method's thread. The grace period
 * is SDK policy; ACP sets no time limit.
 *
 * <p>This method runs on a handler thread while the prompt method is still running on another, so
 * the two share a thread-safe flag, keyed by session id, as in the example. An exception it throws
 * is logged and dropped. {@code $/cancel_request}, which cancels a single request, never reaches
 * this method: the SDK handles it.
 *
 * <p>The method can take a {@code CancelNotification}, a {@link SessionId @SessionId}
 * {@code String} and the connection parameters (see {@link AcpAgent}).
 *
 * <p>Example usage:
 * <pre>{@code
 * private final Map<String, AtomicBoolean> stopFlags = new ConcurrentHashMap<>();
 *
 * @Prompt
 * public PromptResponse prompt(@SessionId String sessionId, SyncPromptContext context) {
 *     AtomicBoolean stop = new AtomicBoolean();
 *     stopFlags.put(sessionId, stop);
 *     try {
 *         for (int step = 1; step <= 3; step++) {
 *             if (stop.get()) {
 *                 return new PromptResponse(StopReason.CANCELLED);
 *             }
 *             context.sendMessage("Step " + step + " done. ");
 *         }
 *         return PromptResponse.endTurn();
 *     }
 *     finally {
 *         stopFlags.remove(sessionId);
 *     }
 * }
 *
 * @Cancel
 * public void cancel(@SessionId String sessionId) {
 *     AtomicBoolean stop = stopFlags.get(sessionId);
 *     if (stop != null) {
 *         stop.set(true);
 *     }
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @since 1.0.0
 * @see AcpAgent
 * @see Prompt
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Cancel {

}

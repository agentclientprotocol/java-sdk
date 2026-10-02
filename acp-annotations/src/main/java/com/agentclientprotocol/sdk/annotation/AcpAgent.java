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
 * Marks a class as an ACP agent that can handle client requests.
 *
 * <p>Classes annotated with {@code @AcpAgent} can define handler methods
 * using annotations like {@link Prompt}, {@link Initialize}, and {@link NewSession}.
 *
 * <p>A request handler returns its method's response (or a {@code Mono} of it); only a
 * {@link Prompt} handler may return {@code void}, which ends the turn. A request handler that
 * returns nothing, or {@code null}, is answered with an internal error ({@code -32603}),
 * because a request must get a result.
 *
 * <p><b>Connection parameters.</b> Besides the parameters its annotation lists, every handler
 * method, extension handlers included, can take any of these, resolved for the connection the
 * request or notification arrived on (with one handler bean serving many connections, each call
 * gets its own connection's):
 * <ul>
 *   <li>{@code NegotiatedCapabilities} - the capabilities the client offered in its
 *   {@code initialize} request, available from the {@code @Initialize} handler on, to gate
 *   options and calls on what the client supports</li>
 *   <li>{@code AcpSyncAgent} or {@code AcpAsyncAgent} - the connection's agent, to send session
 *   updates (such as a {@code ConfigOptionUpdate}) and requests to the client outside a prompt
 *   turn</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * @AcpAgent(name = "support-agent", version = "1.0")
 * public class SupportAgent {
 *
 *     @Initialize
 *     public InitializeResponse init(InitializeRequest req) {
 *         return InitializeResponse.ok();
 *     }
 *
 *     @Prompt
 *     public PromptResponse handlePrompt(PromptRequest req, SyncPromptContext context) {
 *         context.sendMessage("Hello!");
 *         return PromptResponse.endTurn();
 *     }
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @since 1.0.0
 * @see Prompt
 * @see Initialize
 * @see NewSession
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AcpAgent {

	/**
	 * The name of the agent, descriptive only: {@code AcpAgentSupport} does not read it.
	 * Report the agent's identity to the client in the {@code InitializeResponse}
	 * ({@code agentInfo}).
	 * @return the agent name
	 */
	String name() default "";

	/**
	 * The version of the agent, descriptive only, like {@link #name()}.
	 * @return the agent version
	 */
	String version() default "";

}

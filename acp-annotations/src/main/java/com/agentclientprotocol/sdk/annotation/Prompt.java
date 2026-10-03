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
 * Marks a method as the handler for prompt requests.
 *
 * <p>The annotated method handles the {@code session/prompt} JSON-RPC method,
 * which is the main entry point for processing user messages.
 *
 * <p>The method can have the following parameters (all optional, in any order):
 * <ul>
 *   <li>{@code PromptRequest} - the prompt request containing user message</li>
 *   <li>{@code SyncPromptContext} - context for sync handlers with convenience methods</li>
 *   <li>{@code PromptContext} - the async context, for handlers returning Mono</li>
 *   <li>{@code NegotiatedCapabilities} - the negotiated client capabilities</li>
 *   <li>{@code AcpSyncAgent} or {@code AcpAsyncAgent} - the connection's agent (see
 *   {@link AcpAgent})</li>
 * </ul>
 *
 * <p>The method should return one of:
 * <ul>
 *   <li>{@code PromptResponse} - the prompt response</li>
 *   <li>{@code String} - converted to PromptResponse.text()</li>
 *   <li>{@code void} - converted to PromptResponse.endTurn()</li>
 *   <li>{@code Mono<PromptResponse>} - for async handling</li>
 * </ul>
 *
 * <p><b>Cancellation.</b> The context tells the method that its prompt was cancelled, by
 * {@code session/cancel} or by {@code $/cancel_request}: a sync method polls
 * {@code SyncPromptContext.isCancelled()} (or registers {@code onCancel(Runnable)}), a
 * method returning {@code Mono} composes {@code PromptContext.whenCancelled()}. After
 * {@code session/cancel} it stops, sends any last updates, and returns
 * {@code PromptResponse.cancelled()} within the cancel grace period (60 seconds by default,
 * {@code AcpAgentSupport.Builder#cancelGracePeriod}); once that passes the agent answers
 * {@code cancelled} itself and interrupts the method's thread. After {@code $/cancel_request}
 * the agent has already answered, so the method just stops.
 *
 * <p>Example usage:
 * <pre>{@code
 * @Prompt
 * public PromptResponse handlePrompt(PromptRequest req, SyncPromptContext context) {
 *     context.sendMessage("Processing your request...");
 *
 *     // Read files, execute commands, etc.
 *     String content = context.readFile("/path/to/file.txt");
 *
 *     return PromptResponse.text("Here's what I found: " + content);
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
public @interface Prompt {

	/**
	 * Whether the handler accepts image content blocks, advertised as
	 * {@code agentCapabilities.promptCapabilities.image}.
	 * @return false by default
	 */
	boolean image() default false;

	/**
	 * Whether the handler accepts audio content blocks, advertised as
	 * {@code agentCapabilities.promptCapabilities.audio}.
	 * @return false by default
	 */
	boolean audio() default false;

	/**
	 * Whether the handler accepts embedded resources ({@code resource} content blocks),
	 * advertised as {@code agentCapabilities.promptCapabilities.embeddedContext}.
	 * @return false by default
	 */
	boolean embeddedContext() default false;

}

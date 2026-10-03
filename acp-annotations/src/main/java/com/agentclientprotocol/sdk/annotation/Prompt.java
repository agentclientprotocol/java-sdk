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
 * Marks the {@link AcpAgent} method that answers {@code session/prompt}: the client sends the
 * user's message, and the method does the agent's work for that prompt turn. While it runs, it
 * streams progress to the client (message chunks, thoughts, tool calls) through its prompt context,
 * and it can read files, run commands and ask permission on the client. It returns how the turn
 * ended. Without a {@code @Prompt} method the agent answers every prompt with "Method not found"
 * ({@code -32601}).
 *
 * <p>The method can take these parameters, all optional and in any order:
 * <ul>
 *   <li>{@code PromptRequest}: the session id and the prompt's content blocks</li>
 *   <li>{@code SyncPromptContext}: the turn's context, with blocking calls such as
 *   {@code sendMessage}, {@code readFile} and {@code askPermission}</li>
 *   <li>{@code PromptContext}: the same turn's context with calls that return a {@code Mono}, for
 *   code that composes Reactor operators</li>
 *   <li>{@link SessionId @SessionId} {@code String}: the session id</li>
 *   <li>the connection parameters every handler method can take (see {@link AcpAgent})</li>
 * </ul>
 *
 * <p>It returns one of:
 * <ul>
 *   <li>{@code PromptResponse}: the turn's stop reason, usually {@code PromptResponse.endTurn()},
 *   or stop reason {@code cancelled} after a cancel</li>
 *   <li>{@code void}: the same as {@code PromptResponse.endTurn()}</li>
 *   <li>{@code Mono<PromptResponse>}: the runtime waits for it on the handler thread; an empty
 *   {@code Mono} is answered with an internal error ({@code -32603})</li>
 *   <li>{@code String}: sent to the client as an agent message chunk of the turn, then the turn
 *   ends like {@code void}; a null or empty string sends nothing</li>
 * </ul>
 *
 * <p>Prompts always carry text and resource links. The {@link #image()}, {@link #audio()} and
 * {@link #embeddedContext()} attributes declare the other content the method accepts; they are
 * advertised as {@code promptCapabilities}, and a client sends only content the agent advertised.
 *
 * <p>A session has one prompt turn at a time. Until this method returns, a second prompt on the
 * same session is answered with {@code -32600} (Invalid request); prompts on other sessions run at
 * the same time, on other threads.
 *
 * <p><b>Cancellation.</b> The context tells the method that its turn was cancelled, by
 * {@code session/cancel} for its session or by {@code $/cancel_request} for its request: a sync
 * method polls {@code SyncPromptContext.isCancelled()} (or registers {@code onCancel(Runnable)}),
 * a method returning {@code Mono} composes {@code PromptContext.whenCancelled()}. After
 * {@code session/cancel} the method keeps running until it returns: it should stop, send any last
 * updates, and return {@code PromptResponse.cancelled()}. If it has not returned when the cancel
 * grace period ends, the SDK answers {@code cancelled} for it and interrupts its thread. After
 * {@code $/cancel_request} the SDK has already answered, so the method just stops.
 * {@code AcpAgentSupport.Builder} sets the grace period and can also limit how long any turn runs
 * ({@code maxPromptDuration}, off by default).
 *
 * <p>Example usage:
 * <pre>{@code
 * @Prompt
 * public PromptResponse prompt(PromptRequest request, SyncPromptContext context) {
 *     context.sendThought("Reading the README");
 *     String readme = context.readFile("/workspace/README.md");
 *     context.sendMessage("The README has " + readme.lines().count() + " lines.");
 *     return PromptResponse.endTurn();
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @since 1.0.0
 * @see AcpAgent
 * @see Cancel
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

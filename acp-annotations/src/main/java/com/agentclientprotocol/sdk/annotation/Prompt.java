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
 *   <li>{@code String}: ends the turn like {@code void}. The string is not sent to the client, so
 *   send text with {@code sendMessage} before returning</li>
 * </ul>
 *
 * <p>A session has one prompt turn at a time. Until this method returns, a second prompt on the
 * same session is answered with {@code -32600} (Invalid request); prompts on other sessions run at
 * the same time, on other threads. When the client cancels the turn, this method keeps running:
 * the {@link Cancel} method is called, and this method should stop and return stop reason
 * {@code cancelled}. If it has not returned when the cancel grace period ends, the SDK answers
 * {@code cancelled} for it and interrupts its thread. {@code AcpAgentSupport.Builder} sets the
 * grace period and can also limit how long any turn runs ({@code maxPromptDuration}, off by
 * default).
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

}

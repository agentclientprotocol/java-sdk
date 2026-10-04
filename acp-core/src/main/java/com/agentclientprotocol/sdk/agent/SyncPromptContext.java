/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.util.Optional;

import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.jspecify.annotations.Nullable;

/**
 * A prompt handler's way back to the client during one prompt turn, with blocking calls: the
 * session ID, session updates, and the requests an agent sends the client (files, terminals,
 * permission and elicitation). Every {@link AcpAgent.SyncPromptHandler} receives one with each
 * {@code session/prompt}; each call returns once the client has answered. Handlers on the
 * asynchronous builder receive a {@link PromptContext} instead.
 *
 * <p>It offers the same calls as {@link PromptContext}, at the same two levels (the convenience
 * calls here, the protocol calls on {@link #client()}), and blocks on that context's {@code Mono}s
 * ({@link #async()} returns it). It adds {@link #tryReadFile(String)}, and
 * {@link #askChoice(String, String...)} returns an {@link Optional}. Blocking is safe here because
 * synchronous handlers run on the builder's handler executor
 * ({@link AcpAgent.SyncAgentBuilder#handlerExecutor}), not on the transport's thread.
 *
 * <pre>{@code
 * AcpAgent.sync(transport)
 *     .promptHandler((request, context) -> {
 *         context.sendThought("Running the tests");
 *         CommandResult result = context.execute("mvn", "-q", "test");
 *         context.sendMessage(result.success() ? "All tests pass" : result.output());
 *         return AcpSchema.PromptResponse.endTurn();
 *     })
 *     .build();
 * }</pre>
 *
 * <p>Failures are thrown. Once the client has initialized, a call that needs a capability the
 * client did not advertise (reading or writing files, any terminal method, an elicitation mode)
 * throws {@link com.agentclientprotocol.sdk.error.AcpCapabilityException} without sending anything;
 * check {@link #getClientCapabilities()} first. An error answer throws
 * {@link com.agentclientprotocol.sdk.spec.AcpError}. A call has no time limit of its own: a request
 * waits at most the agent's request timeout and then throws
 * {@link com.agentclientprotocol.sdk.error.AcpTimeoutException}, whose cause is the
 * {@link java.util.concurrent.TimeoutException}. When the SDK cancels the handler (after the
 * cancel grace period, or for a {@code $/cancel_request}), it interrupts the handler's thread, and
 * a call blocked at that moment throws {@link java.util.concurrent.CancellationException} and
 * leaves the thread's interrupt flag set. Once the prompt has been answered, its updates are
 * dropped (see {@link #sendSessionUpdate}); its other calls are not checked against the turn.
 *
 * <p>Implementations: the SDK supplies the context handlers receive; implement this interface only
 * for test doubles, and include {@link #async()}.
 *
 * @author Mark Pollack
 * @since 0.9.1
 * @see PromptContext
 * @see AcpAgent.SyncPromptHandler
 */
public interface SyncPromptContext {

	// ========================================================================
	// Session Updates
	// ========================================================================

	/**
	 * Sends a {@code session/update} notification to the client, carrying one
	 * {@link AcpSchema.SessionUpdate}: a message or thought chunk, a tool call or its update, a
	 * plan, and so on, for this prompt's session ({@link #getSessionId()}). Returns once the
	 * notification has been handed to the transport. The Java client hands a turn's updates to its
	 * update handlers in order, before the prompt's answer. Once the prompt has been answered, by the
	 * handler or by the SDK when a prompt deadline passed, the SDK's context drops further
	 * updates (logged at DEBUG) and returns at once: ACP requires a prompt's updates to precede
	 * its answer. To update another session, use
	 * {@link AcpSyncAgent#sendSessionUpdate(String, AcpSchema.SessionUpdate)}.
	 * @param update the update
	 */
	void sendSessionUpdate(AcpSchema.SessionUpdate update);

	// ========================================================================
	// Protocol Layer
	// ========================================================================

	/**
	 * Returns the raw ACP requests to the client, with blocking calls, for what the convenience
	 * calls on this context do not cover: {@code fs/read_text_file} and {@code fs/write_text_file}
	 * with the request records, {@code session/request_permission} for a tool call the agent
	 * announced itself, the five {@code terminal/*} methods one by one, and elicitation. The calls
	 * behave as this context's do.
	 * @return the client's protocol calls for this prompt
	 */
	SyncSessionClient client();

	// ========================================================================
	// Client Capabilities
	// ========================================================================

	/**
	 * Returns what the client advertised in its {@code initialize} request. Check it before a call
	 * that needs a capability, for example {@code supportsTerminal()} before
	 * {@link #execute(Command)}.
	 * @return the client's capabilities, or {@code null} if the client has not initialized
	 */
	@Nullable NegotiatedCapabilities getClientCapabilities();

	/**
	 * Returns the asynchronous context this one blocks on: the same session, turn and client. Use
	 * it to start client calls from a synchronous handler without waiting for each.
	 * @return the asynchronous prompt context
	 */
	PromptContext async();

	// ========================================================================
	// Cancellation
	// ========================================================================

	/**
	 * Whether this prompt has been cancelled: by {@code session/cancel} or {@code session/close}
	 * for its session, by {@code $/cancel_request} for its request, or by the agent itself (the cancel grace
	 * period or the maximum prompt duration passed, or the connection closed). Once true, it
	 * stays true. A long-running handler polls it between steps:
	 * <pre>{@code
	 * for (Step step : plan) {
	 *     if (context.isCancelled()) {
	 *         return PromptResponse.cancelled();
	 *     }
	 *     step.run(context);
	 * }
	 * }</pre>
	 *
	 * <p>
	 * After {@code session/cancel}, answer with stop reason {@code cancelled} within the cancel
	 * grace period ({@code cancelGracePeriod}, 60 seconds by default); once it passes the agent
	 * answers {@code cancelled} itself and interrupts the handler's thread. After
	 * {@code $/cancel_request} the agent has already answered and interrupted the thread, so
	 * what the handler returns is discarded: just stop.
	 * </p>
	 * @return whether the prompt has been cancelled
	 */
	boolean isCancelled();

	/**
	 * Runs {@code action} once when this prompt is cancelled (see {@link #isCancelled()}), at
	 * once on the calling thread if it already has been, for work the handler cannot poll,
	 * such as a subprocess or an HTTP call to abort. The action runs on whichever thread
	 * delivers the cancel, so it must be quick and must not block.
	 * @param action what to run on cancel
	 */
	void onCancel(Runnable action);

	// ========================================================================
	// Convenience API
	// ========================================================================

	/**
	 * Returns the ID of the ACP session this prompt belongs to.
	 * @return the session ID from the prompt request
	 */
	String getSessionId();

	/**
	 * Sends text to the client as an agent message chunk, the visible reply of this prompt's
	 * session. Call it as often as needed: the client shows the chunks as one growing message.
	 * @param text the text
	 */
	void sendMessage(String text);

	/**
	 * Sends text to the client as an agent message chunk that belongs to the given message. Chunks
	 * with the same {@code messageId} make up one message; a new {@code messageId} starts a new
	 * message.
	 *
	 * <p>Implementations get a default that calls {@link #sendSessionUpdate} with an
	 * {@link AcpSchema.AgentMessageChunk} holding the text.
	 * @param text the text
	 * @param messageId the message ID, or {@code null} for none
	 */
	default void sendMessage(String text, @Nullable String messageId) {
		sendSessionUpdate(new AcpSchema.AgentMessageChunk(new AcpSchema.TextContent(text), messageId));
	}

	/**
	 * Sends text to the client as an agent thought chunk: the agent's reasoning, which clients
	 * usually show apart from the reply.
	 * @param text the text
	 */
	void sendThought(String text);

	/**
	 * Sends text to the client as an agent thought chunk that belongs to the given message. Chunks
	 * with the same {@code messageId} make up one message.
	 *
	 * <p>Implementations get a default that calls {@link #sendSessionUpdate} with an {@link AcpSchema.AgentThoughtChunk} holding the text.
	 * @param text the text
	 * @param messageId the message ID, or {@code null} for none
	 */
	default void sendThought(String text, @Nullable String messageId) {
		sendSessionUpdate(new AcpSchema.AgentThoughtChunk(new AcpSchema.TextContent(text), messageId));
	}

	/**
	 * Reads a whole text file through the client ({@code fs/read_text_file}) for this session.
	 * @param path the absolute path of the file
	 * @return the file content
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if the client did not
	 * advertise {@code fs.readTextFile}
	 */
	String readFile(String path);

	/**
	 * Reads part of a text file through the client ({@code fs/read_text_file}) for this session.
	 * The line numbers go to the client unchanged; ACP counts lines from 1.
	 * @param path the absolute path of the file
	 * @param startLine the first line to read, counting from 1, or {@code null} for the first line
	 * @param lineCount the most lines to read, or {@code null} for the rest of the file
	 * @return the content read
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if the client did not
	 * advertise {@code fs.readTextFile}
	 */
	String readFile(String path, @Nullable Integer startLine, @Nullable Integer lineCount);

	/**
	 * Reads a whole text file through the client like {@link #readFile(String)}, but returns empty
	 * instead of throwing. A failure gives empty: a missing capability, an error answer, a timeout.
	 * Cancellation does not: when the SDK cancels the handler while it waits, this throws
	 * {@link java.util.concurrent.CancellationException} with the thread's interrupt flag set, as
	 * the other calls do, so the handler stops.
	 * @param path the absolute path of the file
	 * @return the file content, or empty if it could not be read
	 */
	Optional<String> tryReadFile(String path);

	/**
	 * Writes a text file through the client ({@code fs/write_text_file}) for this session, and
	 * waits until it is written.
	 * @param path the absolute path of the file
	 * @param content the new content of the whole file
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if the client did not
	 * advertise {@code fs.writeTextFile}
	 */
	void writeFile(String path, String content);

	/**
	 * Asks the user to allow or deny an action and waits for the answer, as
	 * {@link PromptContext#askPermission(String)} does: a permission request with the options
	 * "Allow" and "Deny" for a tool call of kind {@code other} titled with the action, announced
	 * first with a {@code tool_call} update.
	 * @param action what the agent wants to do, shown to the user as the tool call's title
	 * @return {@code true} only if the user chose "Allow"; {@code false} if the user denied, or the
	 * client answered without a selected option (it cancelled the request, or sent an outcome
	 * this SDK does not know)
	 */
	boolean askPermission(String action);

	/**
	 * Asks the user to allow or deny an action of the given kind and waits for the answer, as
	 * {@link PromptContext#askPermission(String, AcpSchema.ToolKind)} does: it announces a pending
	 * tool call of that kind, asks permission for it, and settles it once answered.
	 * @param action what the agent wants to do, shown to the user as the tool call's title
	 * @param kind the kind of tool call, for example {@code execute} for a command
	 * @return {@code true} only if the user chose "Allow"; {@code false} if the user denied, or the
	 * client answered without a selected option (it cancelled the request, or sent an outcome
	 * this SDK does not know)
	 */
	boolean askPermission(String action, AcpSchema.ToolKind kind);

	/**
	 * Asks the user to pick one of several options and waits for the answer, as
	 * {@link PromptContext#askChoice(String, String...)} does: a permission request whose options
	 * are the given texts.
	 * @param question the question, shown to the user as the tool call's title
	 * @param options the texts to choose from, at least two
	 * @return the text of the chosen option, or empty if the client answered without a selected
	 * option (it cancelled the request, or sent an outcome this SDK does not know)
	 * @throws IllegalArgumentException if fewer than two options are given
	 * @throws com.agentclientprotocol.sdk.spec.AcpError ({@code -32603}) if the client
	 * answers with an option ID it was not offered
	 */
	Optional<String> askChoice(String question, String... options);

	/**
	 * Runs a command in a client terminal and waits for its output and exit status, as
	 * {@link #execute(Command)} does with {@code Command.of(commandAndArgs)}.
	 * @param commandAndArgs the executable, then its arguments
	 * @return the result
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if the client did not
	 * advertise {@code terminal}
	 */
	CommandResult execute(String... commandAndArgs);

	/**
	 * Runs a command in a client terminal and waits for its output and exit status. It creates a
	 * terminal, waits for the command to end, reads the output, then releases the terminal: always,
	 * also when a step fails or the handler is cancelled while it waits, as ACP requires of an
	 * agent. Waiting counts against the agent's request timeout, so a command that runs
	 * longer throws, and the terminal is still released; raise the builder's {@code requestTimeout}
	 * for long commands.
	 * @param command the command and its options
	 * @return the result
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if the client did not
	 * advertise {@code terminal}
	 */
	CommandResult execute(Command command);

}

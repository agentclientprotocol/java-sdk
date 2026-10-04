/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

/**
 * A prompt handler's way back to the client during one prompt turn: the session ID, session
 * updates, and the requests an agent sends the client (files, terminals, permission and
 * elicitation). Every {@link AcpAgent.PromptHandler} receives one with each {@code session/prompt},
 * and every call returns a Reactor {@code Mono}. Handlers on the synchronous builder receive a
 * {@link SyncPromptContext} instead, whose calls block.
 *
 * <p>It has two levels. The context itself holds the convenience calls, which fill in the
 * session ID for you: {@link #sendMessage(String)} and {@link #sendThought(String)} send text
 * chunks and {@link #sendSessionUpdate} any session update, {@link #readFile(String)} and
 * {@link #writeFile(String, String)} wrap the file requests, {@link #askPermission(String)} and
 * {@link #askChoice(String, String...)} wrap a permission request, and {@link #execute(Command)}
 * runs a command in a client terminal from creation to release. One step down,
 * {@link #client()} holds the protocol calls ({@code readTextFile}, {@code createTerminal},
 * {@code requestPermission}, {@code createElicitation} and the rest), which take ACP request
 * records and return the client's answers.
 *
 * <pre>{@code
 * AcpAgent.async(transport)
 *     .promptHandler((request, context) -> context.sendThought("Reading the build file")
 *         .then(context.readFile("/workspace/pom.xml"))
 *         .flatMap(pom -> context.sendMessage("The build file has " + pom.length() + " chars"))
 *         .thenReturn(AcpSchema.PromptResponse.endTurn()))
 *     .build();
 * }</pre>
 *
 * <p>Calls go through the agent ({@link AcpAsyncAgent}) and behave as its calls do. Nothing is sent
 * until the {@code Mono} is subscribed. Once the client has initialized, a call that needs a
 * capability the client did not advertise (reading or writing files, any terminal method, an
 * elicitation mode) fails with {@link com.agentclientprotocol.sdk.error.AcpCapabilityException}
 * without being sent; check {@link #getClientCapabilities()} first. An error answer fails the
 * {@code Mono} with {@link com.agentclientprotocol.sdk.spec.AcpError}, and no answer within the
 * agent's request timeout fails it with a {@link java.util.concurrent.TimeoutException} (the
 * blocking {@link SyncPromptContext} throws an
 * {@link com.agentclientprotocol.sdk.error.AcpTimeoutException} wrapping it instead). When the
 * SDK cancels the handler (after the cancel grace period, or for a {@code $/cancel_request}), it
 * disposes the handler's {@code Mono}: requests still waiting inside it are cancelled, and the
 * client is sent a {@code $/cancel_request} for each. Once the prompt has been answered, its
 * updates are dropped (see {@link #sendSessionUpdate}); its other calls are not checked against the
 * turn. Its methods may be called from several threads at once.
 *
 * <p>Implementations: the SDK supplies the context handlers receive; implement this interface only
 * for test doubles.
 *
 * @author Mark Pollack
 * @since 0.9.1
 * @see AcpAgent.PromptHandler
 * @see SyncPromptContext
 */
public interface PromptContext {

	// ========================================================================
	// Session Updates
	// ========================================================================

	/**
	 * Sends a {@code session/update} notification to the client, carrying one
	 * {@link AcpSchema.SessionUpdate}: a message or thought chunk, a tool call or its update, a
	 * plan, and so on, for this prompt's session ({@link #getSessionId()}). The Java client hands a
	 * turn's updates to its update handlers in order, before the prompt's answer. Once the prompt has
	 * been answered, by the handler or by the SDK when a prompt deadline passed, the SDK's context
	 * drops further updates (logged at DEBUG) and the {@code Mono} completes empty: ACP requires a
	 * prompt's updates to precede its answer. To update another
	 * session, use {@link AcpAsyncAgent#sendSessionUpdate(String, AcpSchema.SessionUpdate)}.
	 * @param update the update
	 * @return a {@code Mono} that completes when the notification has been handed to the transport
	 */
	Mono<Void> sendSessionUpdate(AcpSchema.SessionUpdate update);

	// ========================================================================
	// Protocol Layer
	// ========================================================================

	/**
	 * Returns the raw ACP requests to the client, for what the convenience calls on this context
	 * do not cover: {@code fs/read_text_file} and {@code fs/write_text_file} with the request
	 * records, {@code session/request_permission} for a tool call the agent announced itself, the
	 * five {@code terminal/*} methods one by one, and elicitation. The calls behave as this
	 * context's do, and are cancelled with the prompt handler.
	 * @return the client's protocol calls for this prompt
	 */
	SessionClient client();

	// ========================================================================
	// Client Capabilities
	// ========================================================================

	/**
	 * Returns what the client advertised in its {@code initialize} request. Check it before a call
	 * that needs a capability, for example {@code supportsReadTextFile()} before
	 * {@link #readFile(String)}.
	 * @return the client's capabilities, or {@code null} if the client has not initialized
	 */
	@Nullable NegotiatedCapabilities getClientCapabilities();

	// ========================================================================
	// Cancellation
	// ========================================================================

	/**
	 * Whether this prompt has been cancelled: by {@code session/cancel} or {@code session/close}
	 * for its session, by {@code $/cancel_request} for its request, or by the agent itself (the cancel grace
	 * period or the maximum prompt duration passed, or the connection closed). Once true, it
	 * stays true.
	 *
	 * <p>
	 * After {@code session/cancel}, stop the prompt's work, send any last updates, and answer
	 * with stop reason {@code cancelled} ({@link AcpSchema.PromptResponse#cancelled()}), within
	 * the cancel grace period ({@code cancelGracePeriod}, 60 seconds by default); once it passes
	 * the agent answers {@code cancelled} itself and cancels the handler. After
	 * {@code $/cancel_request} the agent has already answered (error {@code -32800}, or
	 * {@code cancelled} if the session was cancelled too), so what the handler returns is
	 * discarded: just stop.
	 * </p>
	 * @return whether the prompt has been cancelled
	 * @see #whenCancelled()
	 */
	boolean isCancelled();

	/**
	 * Completes, empty, when this prompt is cancelled (see {@link #isCancelled()}), at once if
	 * it already has been; it never errors, and never completes for a prompt that is not
	 * cancelled. Compose it into the handler's {@code Mono}, for example
	 * {@code work.takeUntilOther(context.whenCancelled())}, to stop on a cancel.
	 * @return a Mono completing when the prompt is cancelled
	 */
	Mono<Void> whenCancelled();

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
	 * @return a {@code Mono} that completes when the update has been handed to the transport
	 */
	Mono<Void> sendMessage(String text);

	/**
	 * Sends text to the client as an agent message chunk that belongs to the given message. Chunks
	 * with the same {@code messageId} make up one message; a new {@code messageId} starts a new
	 * message.
	 *
	 * <p>Implementations get a default that calls {@link #sendSessionUpdate} with an
	 * {@link AcpSchema.AgentMessageChunk} holding the text.
	 * @param text the text
	 * @param messageId the message ID, or {@code null} for none
	 * @return a {@code Mono} that completes when the update has been handed to the transport
	 */
	default Mono<Void> sendMessage(String text, @Nullable String messageId) {
		return sendSessionUpdate(new AcpSchema.AgentMessageChunk(new AcpSchema.TextContent(text), messageId));
	}

	/**
	 * Sends text to the client as an agent thought chunk: the agent's reasoning, which clients
	 * usually show apart from the reply.
	 * @param text the text
	 * @return a {@code Mono} that completes when the update has been handed to the transport
	 */
	Mono<Void> sendThought(String text);

	/**
	 * Sends text to the client as an agent thought chunk that belongs to the given message. Chunks
	 * with the same {@code messageId} make up one message.
	 *
	 * <p>Implementations get a default that calls {@link #sendSessionUpdate} with an {@link AcpSchema.AgentThoughtChunk} holding the text.
	 * @param text the text
	 * @param messageId the message ID, or {@code null} for none
	 * @return a {@code Mono} that completes when the update has been handed to the transport
	 */
	default Mono<Void> sendThought(String text, @Nullable String messageId) {
		return sendSessionUpdate(new AcpSchema.AgentThoughtChunk(new AcpSchema.TextContent(text), messageId));
	}

	/**
	 * Reads a whole text file through the client ({@code fs/read_text_file}) for this session.
	 * @param path the absolute path of the file
	 * @return a {@code Mono} emitting the file content; it fails with
	 * {@link com.agentclientprotocol.sdk.error.AcpCapabilityException} if the client did not
	 * advertise {@code fs.readTextFile}
	 */
	Mono<String> readFile(String path);

	/**
	 * Reads part of a text file through the client ({@code fs/read_text_file}) for this session.
	 * The line numbers go to the client unchanged; ACP counts lines from 1.
	 * @param path the absolute path of the file
	 * @param startLine the first line to read, counting from 1, or {@code null} for the first line
	 * @param lineCount the most lines to read, or {@code null} for the rest of the file
	 * @return a {@code Mono} emitting the content read; it fails with
	 * {@link com.agentclientprotocol.sdk.error.AcpCapabilityException} if the client did not
	 * advertise {@code fs.readTextFile}
	 */
	Mono<String> readFile(String path, @Nullable Integer startLine, @Nullable Integer lineCount);

	/**
	 * Writes a text file through the client ({@code fs/write_text_file}) for this session.
	 * @param path the absolute path of the file
	 * @param content the new content of the whole file
	 * @return a {@code Mono} that completes once the client has written the file; it fails with
	 * {@link com.agentclientprotocol.sdk.error.AcpCapabilityException} if the client did not
	 * advertise {@code fs.writeTextFile}
	 */
	Mono<Void> writeFile(String path, String content);

	/**
	 * Asks the user to allow or deny an action, with a permission request of two options: "Allow"
	 * (allow once) and "Deny" (reject once), as {@link #askPermission(String, AcpSchema.ToolKind)}
	 * does with the kind {@code other}.
	 * @param action what the agent wants to do, shown to the user as the tool call's title
	 * @return a {@code Mono} emitting {@code true} only if the user chose "Allow"; {@code false} if
	 * the user denied, or the client answered without a selected option (it cancelled the
	 * request, or sent an outcome this SDK does not know)
	 */
	Mono<Boolean> askPermission(String action);

	/**
	 * Asks the user to allow or deny an action of the given kind, with a permission request of two
	 * options: "Allow" (allow once) and "Deny" (reject once). ACP asks permission for a tool call,
	 * so the context first announces one: a {@code tool_call} session update with a new random ID,
	 * the action as its title, the kind, and status {@code pending}. The permission request names
	 * that tool call, and once the user answered, a {@code tool_call_update} sets its status to
	 * {@code completed} (an option was selected, either way) or {@code failed} (any other
	 * outcome: the client cancelled the request, or sent an outcome this SDK does not know). For a
	 * tool call the agent announced itself, send {@link SessionClient#requestPermission} through
	 * {@link #client()} instead.
	 * @param action what the agent wants to do, shown to the user as the tool call's title
	 * @param kind the kind of tool call, which clients use to pick an icon, for example
	 * {@code execute} for a command
	 * @return a {@code Mono} emitting {@code true} only if the user chose "Allow"; {@code false} if
	 * the user denied, or the client answered without a selected option (it cancelled the
	 * request, or sent an outcome this SDK does not know)
	 */
	Mono<Boolean> askPermission(String action, AcpSchema.ToolKind kind);

	/**
	 * Asks the user to pick one of several options, with a permission request whose options are the
	 * given texts. Like {@link #askPermission(String, AcpSchema.ToolKind)}, it announces a pending
	 * tool call with a new random ID, the question as its title and the kind {@code other}, and
	 * settles it once the user answered; every option has the kind "allow once", and its ID is its
	 * position in {@code options}.
	 * @param question the question, shown to the user as the tool call's title
	 * @param options the texts to choose from, at least two
	 * @return a {@code Mono} emitting the text of the chosen option, or completing empty if the
	 * client answered without a selected option (it cancelled the request, or sent an outcome
	 * this SDK does not know); it fails with {@link IllegalArgumentException} if fewer than
	 * two options are given, and with an
	 * {@link com.agentclientprotocol.sdk.spec.AcpError} ({@code -32603}) if the client
	 * answers with an option ID it was not offered
	 */
	Mono<String> askChoice(String question, String... options);

	/**
	 * Runs a command in a client terminal and returns its output and exit status, as
	 * {@link #execute(Command)} does with {@code Command.of(commandAndArgs)}.
	 * @param commandAndArgs the executable, then its arguments
	 * @return a {@code Mono} emitting the result; see {@link #execute(Command)}
	 */
	Mono<CommandResult> execute(String... commandAndArgs);

	/**
	 * Runs a command in a client terminal and returns its output and exit status. It creates a
	 * terminal, waits for the command to end, reads the output, then releases the terminal: always,
	 * also when a step fails or the returned {@code Mono} is cancelled (for example because the
	 * prompt was cancelled), as ACP requires of an agent. Waiting counts against the agent's
	 * request timeout, so a command that runs longer fails with a
	 * {@link java.util.concurrent.TimeoutException}, and the terminal is still released; raise the
	 * builder's {@code requestTimeout} for long commands.
	 * @param command the command and its options
	 * @return a {@code Mono} emitting the result; it fails with
	 * {@link com.agentclientprotocol.sdk.error.AcpCapabilityException} if the client did not
	 * advertise {@code terminal}, and with the error of the first step that failed
	 */
	Mono<CommandResult> execute(Command command);

}

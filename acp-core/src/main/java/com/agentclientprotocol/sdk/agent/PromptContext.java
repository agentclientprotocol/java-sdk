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
 * <p>It has two levels. The protocol calls ({@link #sendUpdate}, {@link #readTextFile},
 * {@link #createTerminal}, {@link #requestPermission} and the rest) take ACP request records and
 * return the client's answers. The convenience calls fill in the session ID for you:
 * {@link #sendMessage(String)} and {@link #sendThought(String)} send text chunks,
 * {@link #readFile(String)} and {@link #writeFile(String, String)} wrap the file requests,
 * {@link #askPermission(String)} and {@link #askChoice(String, String...)} wrap a permission
 * request, and {@link #execute(Command)} runs a command in a client terminal from creation to
 * release.
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
 * capability the client did not advertise (reading or writing files, creating a terminal, an
 * elicitation mode) fails with {@link com.agentclientprotocol.sdk.error.AcpCapabilityException}
 * without being sent; check {@link #getClientCapabilities()} first. An error answer fails the
 * {@code Mono} with {@link com.agentclientprotocol.sdk.spec.AcpError}, and no answer within the
 * agent's request timeout fails it with a {@link java.util.concurrent.TimeoutException}. When the
 * SDK cancels the handler (after the cancel grace period, or for a {@code $/cancel_request}), it
 * disposes the handler's {@code Mono}: requests still waiting inside it are cancelled, and the
 * client is sent a {@code $/cancel_request} for each. The context does not check that its turn is
 * still active. Its methods may be called from several threads at once.
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
	 * plan, and so on. The Java client hands a turn's updates to its consumers in order, before the
	 * prompt's answer.
	 * @param sessionId the ACP session the update belongs to, normally {@link #getSessionId()}
	 * @param update the update
	 * @return a {@code Mono} that completes when the notification has been handed to the transport
	 */
	Mono<Void> sendUpdate(String sessionId, AcpSchema.SessionUpdate update);

	// ========================================================================
	// File System Operations
	// ========================================================================

	/**
	 * Asks the client for the content of a text file ({@code fs/read_text_file}), including unsaved
	 * changes in its editor. The client must have advertised {@code fs.readTextFile}.
	 * @param request the session, an absolute path, and optionally a 1-based first line and a line
	 * limit
	 * @return a {@code Mono} emitting the file content; it fails with
	 * {@link com.agentclientprotocol.sdk.error.AcpCapabilityException} if the client did not
	 * advertise the capability
	 */
	Mono<AcpSchema.ReadTextFileResponse> readTextFile(AcpSchema.ReadTextFileRequest request);

	/**
	 * Asks the client to write a text file ({@code fs/write_text_file}); ACP requires the client to
	 * create the file if it does not exist. The client must have advertised
	 * {@code fs.writeTextFile}.
	 * @param request the session, an absolute path and the new content
	 * @return a {@code Mono} emitting the client's empty answer once the file is written; it fails
	 * with {@link com.agentclientprotocol.sdk.error.AcpCapabilityException} if the client did not
	 * advertise the capability
	 */
	Mono<AcpSchema.WriteTextFileResponse> writeTextFile(AcpSchema.WriteTextFileRequest request);

	// ========================================================================
	// Permission Requests
	// ========================================================================

	/**
	 * Asks the client to let the user approve a tool call ({@code session/request_permission}). The
	 * request describes the tool call and the options to choose from; every client handles this
	 * request, so no capability is checked.
	 * @param request the session, the tool call and the permission options
	 * @return a {@code Mono} emitting the user's choice: the selected option, or a cancelled
	 * outcome when the client cancelled the prompt turn
	 */
	Mono<AcpSchema.RequestPermissionResponse> requestPermission(AcpSchema.RequestPermissionRequest request);

	// ========================================================================
	// Terminal Operations
	// ========================================================================

	/**
	 * Asks the client to start a command in a new terminal ({@code terminal/create}). The client
	 * must have advertised {@code terminal}. ACP requires the agent to release every terminal it
	 * creates with {@link #releaseTerminal}; {@link #execute(Command)} does the whole sequence for
	 * you.
	 * @param request the session, the command, its arguments and optionally a working directory,
	 * environment variables and an output limit
	 * @return a {@code Mono} emitting the new terminal's ID; it fails with
	 * {@link com.agentclientprotocol.sdk.error.AcpCapabilityException} if the client did not
	 * advertise the capability
	 */
	Mono<AcpSchema.CreateTerminalResponse> createTerminal(AcpSchema.CreateTerminalRequest request);

	/**
	 * Asks the client for a terminal's output so far ({@code terminal/output}), without waiting for
	 * the command to end.
	 * @param request the session and the terminal ID
	 * @return a {@code Mono} emitting the output, whether it was truncated, and the exit status if
	 * the command has ended
	 */
	Mono<AcpSchema.TerminalOutputResponse> getTerminalOutput(AcpSchema.TerminalOutputRequest request);

	/**
	 * Asks the client to release a terminal ({@code terminal/release}), which kills its command if
	 * it is still running. The terminal ID is invalid afterwards.
	 * @param request the session and the terminal ID
	 * @return a {@code Mono} emitting the client's empty answer once the terminal is released
	 */
	Mono<AcpSchema.ReleaseTerminalResponse> releaseTerminal(AcpSchema.ReleaseTerminalRequest request);

	/**
	 * Asks the client to answer when a terminal's command has ended
	 * ({@code terminal/wait_for_exit}). The wait counts against the agent's request timeout, so a
	 * long command can fail it with a {@link java.util.concurrent.TimeoutException}.
	 * @param request the session and the terminal ID
	 * @return a {@code Mono} emitting the exit code or the signal that ended the command
	 */
	Mono<AcpSchema.WaitForTerminalExitResponse> waitForTerminalExit(AcpSchema.WaitForTerminalExitRequest request);

	/**
	 * Asks the client to kill a terminal's command ({@code terminal/kill}) without releasing the
	 * terminal: its output and exit status can still be read, and it must still be released.
	 * @param request the session and the terminal ID
	 * @return a {@code Mono} emitting the client's empty answer once the command is killed
	 */
	Mono<AcpSchema.KillTerminalCommandResponse> killTerminal(AcpSchema.KillTerminalCommandRequest request);

	// ========================================================================
	// Elicitation
	// ========================================================================

	/**
	 * Asks the client to collect structured input from the user ({@code elicitation/create}),
	 * either with a form built from a schema or by sending the user to a URL. The client must have
	 * advertised the request's mode in its elicitation capabilities.
	 * @param request the elicitation, made with {@link AcpSchema.CreateElicitationRequest#form} or
	 * {@link AcpSchema.CreateElicitationRequest#url}
	 * @return a {@code Mono} emitting the user's answer: accept (with the form content), decline or
	 * cancel; it fails with {@link com.agentclientprotocol.sdk.error.AcpCapabilityException} if the
	 * client did not advertise the mode
	 */
	Mono<AcpSchema.CreateElicitationResponse> createElicitation(AcpSchema.CreateElicitationRequest request);

	/**
	 * Tells the client that the outside interaction of a URL-mode elicitation has finished
	 * ({@code elicitation/complete}).
	 * @param notification the ID of the elicitation that finished
	 * @return a {@code Mono} that completes when the notification has been handed to the transport
	 */
	Mono<Void> completeElicitation(AcpSchema.CompleteElicitationNotification notification);

	// ========================================================================
	// Client Capabilities
	// ========================================================================

	/**
	 * Returns what the client advertised in its {@code initialize} request. Check it before a call
	 * that needs a capability, for example {@code supportsReadTextFile()} before
	 * {@link #readTextFile}.
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
	 * <p>Implementations get a default that calls {@link #sendUpdate} with {@link #getSessionId()}
	 * and an {@link AcpSchema.AgentMessageChunk} holding the text.
	 * @param text the text
	 * @param messageId the message ID, or {@code null} for none
	 * @return a {@code Mono} that completes when the update has been handed to the transport
	 */
	default Mono<Void> sendMessage(String text, @Nullable String messageId) {
		return sendUpdate(getSessionId(),
				new AcpSchema.AgentMessageChunk(new AcpSchema.TextContent(text), messageId));
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
	 * <p>Implementations get a default that calls {@link #sendUpdate} with {@link #getSessionId()}
	 * and an {@link AcpSchema.AgentThoughtChunk} holding the text.
	 * @param text the text
	 * @param messageId the message ID, or {@code null} for none
	 * @return a {@code Mono} that completes when the update has been handed to the transport
	 */
	default Mono<Void> sendThought(String text, @Nullable String messageId) {
		return sendUpdate(getSessionId(),
				new AcpSchema.AgentThoughtChunk(new AcpSchema.TextContent(text), messageId));
	}

	/**
	 * Reads a whole text file through the client, as {@link #readTextFile} does for this session.
	 * @param path the absolute path of the file
	 * @return a {@code Mono} emitting the file content; it fails with
	 * {@link com.agentclientprotocol.sdk.error.AcpCapabilityException} if the client did not
	 * advertise {@code fs.readTextFile}
	 */
	Mono<String> readFile(String path);

	/**
	 * Reads part of a text file through the client, as {@link #readTextFile} does for this session.
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
	 * Writes a text file through the client, as {@link #writeTextFile} does for this session.
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
	 * the user denied or the client cancelled the request
	 */
	Mono<Boolean> askPermission(String action);

	/**
	 * Asks the user to allow or deny an action of the given kind, with a permission request of two
	 * options: "Allow" (allow once) and "Deny" (reject once). ACP asks permission for a tool call,
	 * so the context first announces one: a {@code tool_call} session update with a new random ID,
	 * the action as its title, the kind, and status {@code pending}. The permission request names
	 * that tool call, and once the user answered, a {@code tool_call_update} sets its status to
	 * {@code completed} (answered, either way) or {@code failed} (the client cancelled the request).
	 * For a tool call the agent announced itself, send {@link #requestPermission} instead.
	 * @param action what the agent wants to do, shown to the user as the tool call's title
	 * @param kind the kind of tool call, which clients use to pick an icon, for example
	 * {@code execute} for a command
	 * @return a {@code Mono} emitting {@code true} only if the user chose "Allow"; {@code false} if
	 * the user denied or the client cancelled the request
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
	 * client cancelled the request; it fails with {@link IllegalArgumentException} if fewer than
	 * two options are given, and with an
	 * {@link com.agentclientprotocol.sdk.error.AcpProtocolException} ({@code -32603}) if the client
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

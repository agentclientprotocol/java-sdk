/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

/**
 * Context provided to prompt handlers for accessing agent capabilities.
 *
 * <p>
 * This interface provides prompt handlers with everything they need to:
 * <ul>
 * <li>Send session updates back to the client</li>
 * <li>Request file operations from the client</li>
 * <li>Request permissions from the client</li>
 * <li>Create and manage terminals</li>
 * <li>Query client capabilities</li>
 * </ul>
 *
 * <p>
 * This follows the MCP SDK's "Exchange" pattern where handlers receive
 * a context object with all necessary capabilities, eliminating the need
 * for external references to the agent instance.
 *
 * <p>
 * Example usage:
 * <pre>{@code
 * agent.promptHandler((request, context) -> {
 *     // Send an update
 *     context.sendUpdate(sessionId, update);
 *
 *     // Read a file (if client supports it; capabilities are null before initialize)
 *     NegotiatedCapabilities caps = context.getClientCapabilities();
 *     if (caps != null && caps.supportsReadTextFile()) {
 *         var content = context.readTextFile(new ReadTextFileRequest(...)).block();
 *     }
 *
 *     // Request permission
 *     var permission = context.requestPermission(new RequestPermissionRequest(...));
 *
 *     return Mono.just(new PromptResponse(StopReason.END_TURN));
 * });
 * }</pre>
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
	 * Sends a session update notification to the client.
	 * Used for streaming updates during prompt processing.
	 * @param sessionId The session ID
	 * @param update The session update to send
	 * @return A Mono that completes when the notification is sent
	 */
	Mono<Void> sendUpdate(String sessionId, AcpSchema.SessionUpdate update);

	// ========================================================================
	// File System Operations
	// ========================================================================

	/**
	 * Requests the client to read a text file.
	 * @param request The read file request
	 * @return A Mono containing the file content
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if client doesn't support file reading
	 */
	Mono<AcpSchema.ReadTextFileResponse> readTextFile(AcpSchema.ReadTextFileRequest request);

	/**
	 * Requests the client to write a text file.
	 * @param request The write file request
	 * @return A Mono that completes when the file is written
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if client doesn't support file writing
	 */
	Mono<AcpSchema.WriteTextFileResponse> writeTextFile(AcpSchema.WriteTextFileRequest request);

	// ========================================================================
	// Permission Requests
	// ========================================================================

	/**
	 * Requests permission from the client for a sensitive operation.
	 * @param request The permission request
	 * @return A Mono containing the permission response
	 */
	Mono<AcpSchema.RequestPermissionResponse> requestPermission(AcpSchema.RequestPermissionRequest request);

	// ========================================================================
	// Terminal Operations
	// ========================================================================

	/**
	 * Requests the client to create a terminal.
	 * @param request The create terminal request
	 * @return A Mono containing the terminal ID
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if client doesn't support terminals
	 */
	Mono<AcpSchema.CreateTerminalResponse> createTerminal(AcpSchema.CreateTerminalRequest request);

	/**
	 * Requests terminal output from the client.
	 * @param request The terminal output request
	 * @return A Mono containing the terminal output
	 */
	Mono<AcpSchema.TerminalOutputResponse> getTerminalOutput(AcpSchema.TerminalOutputRequest request);

	/**
	 * Requests the client to release a terminal.
	 * @param request The release terminal request
	 * @return A Mono that completes when the terminal is released
	 */
	Mono<AcpSchema.ReleaseTerminalResponse> releaseTerminal(AcpSchema.ReleaseTerminalRequest request);

	/**
	 * Waits for a terminal to exit.
	 * @param request The wait for exit request
	 * @return A Mono containing the exit status
	 */
	Mono<AcpSchema.WaitForTerminalExitResponse> waitForTerminalExit(AcpSchema.WaitForTerminalExitRequest request);

	/**
	 * Requests the client to kill a terminal.
	 * @param request The kill terminal request
	 * @return A Mono that completes when the terminal is killed
	 */
	Mono<AcpSchema.KillTerminalCommandResponse> killTerminal(AcpSchema.KillTerminalCommandRequest request);

	// ========================================================================
	// Elicitation
	// ========================================================================

	/**
	 * Requests structured user input from the client via a form or URL.
	 * @param request The elicitation request
	 * @return A Mono containing the user's response
	 */
	Mono<AcpSchema.CreateElicitationResponse> createElicitation(AcpSchema.CreateElicitationRequest request);

	/**
	 * Notifies the client that a URL-mode elicitation has completed.
	 * @param notification The completion notification
	 * @return A Mono that completes when the notification is sent
	 */
	Mono<Void> completeElicitation(AcpSchema.CompleteElicitationNotification notification);

	// ========================================================================
	// Client Capabilities
	// ========================================================================

	/**
	 * Returns the capabilities negotiated with the client during initialization.
	 *
	 * <p>
	 * Use this to check what features the client supports before calling
	 * methods like {@link #readTextFile} or {@link #createTerminal}.
	 *
	 * @return the negotiated client capabilities, or null if not yet initialized
	 */
	@Nullable NegotiatedCapabilities getClientCapabilities();

	// ========================================================================
	// Cancellation
	// ========================================================================

	/**
	 * Whether this prompt has been cancelled: by {@code session/cancel} for its session, by
	 * {@code $/cancel_request} for its request, or by the agent itself (the cancel grace
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
	 * Returns the session ID for this prompt invocation.
	 * @return the session ID
	 */
	String getSessionId();

	/**
	 * Sends a message to the client as an agent message chunk.
	 * This is a convenience method that wraps the text in the appropriate
	 * session update structure.
	 * @param text The message text to send
	 * @return A Mono that completes when the message is sent
	 */
	Mono<Void> sendMessage(String text);

	/**
	 * Sends a message to the client as an agent message chunk, tagged with a message ID.
	 * Chunks sharing the same {@code messageId} belong to the same logical message; a change
	 * in {@code messageId} signals a new message.
	 * @param text The message text to send
	 * @param messageId The message identifier, or {@code null} for none
	 * @return A Mono that completes when the message is sent
	 */
	default Mono<Void> sendMessage(String text, @Nullable String messageId) {
		return sendUpdate(getSessionId(),
				new AcpSchema.AgentMessageChunk(new AcpSchema.TextContent(text), messageId));
	}

	/**
	 * Sends a thought to the client as an agent thought chunk.
	 * Thoughts are typically displayed differently than messages,
	 * showing the agent's reasoning process.
	 * @param text The thought text to send
	 * @return A Mono that completes when the thought is sent
	 */
	Mono<Void> sendThought(String text);

	/**
	 * Sends a thought to the client as an agent thought chunk, tagged with a message ID.
	 * Chunks sharing the same {@code messageId} belong to the same logical message.
	 * @param text The thought text to send
	 * @param messageId The message identifier, or {@code null} for none
	 * @return A Mono that completes when the thought is sent
	 */
	default Mono<Void> sendThought(String text, @Nullable String messageId) {
		return sendUpdate(getSessionId(),
				new AcpSchema.AgentThoughtChunk(new AcpSchema.TextContent(text), messageId));
	}

	/**
	 * Reads a text file from the client's file system.
	 * @param path The path to the file
	 * @return A Mono containing the file content
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if client doesn't support file reading
	 */
	Mono<String> readFile(String path);

	/**
	 * Reads a portion of a text file from the client's file system.
	 * @param path The path to the file
	 * @param startLine The line number to start reading from (0-indexed, null for beginning)
	 * @param lineCount The number of lines to read (null for all remaining)
	 * @return A Mono containing the file content
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if client doesn't support file reading
	 */
	Mono<String> readFile(String path, @Nullable Integer startLine, @Nullable Integer lineCount);

	/**
	 * Writes content to a text file on the client's file system.
	 * @param path The path to the file
	 * @param content The content to write
	 * @return A Mono that completes when the file is written
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if client doesn't support file writing
	 */
	Mono<Void> writeFile(String path, String content);

	/**
	 * Asks the client for permission to perform an action.
	 * Presents a simple Allow/Deny choice.
	 * @param action A description of the action to request permission for
	 * @return A Mono containing true if allowed, false otherwise
	 */
	Mono<Boolean> askPermission(String action);

	/**
	 * Asks the client to choose from multiple options.
	 * @param question The question to ask
	 * @param options The available options (at least 2)
	 * @return A Mono emitting the selected option text, or completing empty if the
	 * client cancelled the choice
	 */
	Mono<String> askChoice(String question, String... options);

	/**
	 * Executes a command in a terminal and waits for completion.
	 * The terminal is automatically released after execution.
	 * @param commandAndArgs The command and arguments to execute
	 * @return A Mono containing the command result
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if client doesn't support terminals
	 */
	Mono<CommandResult> execute(String... commandAndArgs);

	/**
	 * Executes a command with options and waits for completion.
	 * The terminal is automatically released after execution.
	 * @param command The command configuration
	 * @return A Mono containing the command result
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if client doesn't support terminals
	 */
	Mono<CommandResult> execute(Command command);

}

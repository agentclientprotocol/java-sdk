/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

/**
 * Asynchronous ACP agent interface providing non-blocking operations for
 * handling client requests and sending notifications.
 *
 * <p>
 * The agent is the server-side component in the ACP protocol, responsible for:
 * <ul>
 * <li>Responding to client initialization and authentication requests</li>
 * <li>Managing sessions and processing prompts</li>
 * <li>Sending session update notifications during processing</li>
 * <li>Requesting client capabilities (file system, terminal, permissions)</li>
 * </ul>
 *
 * @author Mark Pollack
 * @see AcpAgent
 * @see AcpSyncAgent
 */
public interface AcpAsyncAgent {

	/**
	 * Starts the agent, beginning to accept client connections.
	 * @return A Mono that completes when the agent is started
	 */
	Mono<Void> start();

	/**
	 * Returns a Mono that completes when the agent terminates.
	 * This is useful for blocking until the transport closes, particularly
	 * when using daemon threads.
	 *
	 * <p>On stdio, the agent terminates once the client has closed its input and every
	 * request received before has been answered, bounded by the transport's drain timeout
	 * (see {@code StdioAcpAgentTransport}): exiting then loses no reply.
	 *
	 * <p>Example usage:
	 * <pre>{@code
	 * agent.start().block();
	 * agent.awaitTermination().block(); // Block until transport closes
	 * }</pre>
	 *
	 * @return A Mono that completes when the agent terminates
	 */
	Mono<Void> awaitTermination();

	/**
	 * Returns the capabilities negotiated with the client during initialization.
	 *
	 * <p>
	 * This method returns null if initialization has not been completed yet.
	 * Use this to check what features the client supports before calling
	 * methods like {@link #readTextFile} or {@link #createTerminal}.
	 * </p>
	 * @return the negotiated client capabilities, or null if not initialized
	 */
	@Nullable NegotiatedCapabilities getClientCapabilities();

	/**
	 * Sends a session update notification to the client.
	 * Used for streaming updates during prompt processing.
	 * @param sessionId The session ID
	 * @param update The session update to send
	 * @return A Mono that completes when the notification is sent
	 */
	Mono<Void> sendSessionUpdate(String sessionId, AcpSchema.SessionUpdate update);

	/**
	 * Requests permission from the client for a sensitive operation.
	 * @param request The permission request
	 * @return A Mono containing the permission response
	 */
	Mono<AcpSchema.RequestPermissionResponse> requestPermission(AcpSchema.RequestPermissionRequest request);

	/**
	 * Requests the client to read a text file.
	 * @param request The read file request
	 * @return A Mono containing the file content
	 */
	Mono<AcpSchema.ReadTextFileResponse> readTextFile(AcpSchema.ReadTextFileRequest request);

	/**
	 * Requests the client to write a text file.
	 * @param request The write file request
	 * @return A Mono that completes when the file is written
	 */
	Mono<AcpSchema.WriteTextFileResponse> writeTextFile(AcpSchema.WriteTextFileRequest request);

	/**
	 * Requests the client to create a terminal.
	 * @param request The create terminal request
	 * @return A Mono containing the terminal ID
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

	/**
	 * Sends a custom extension request ({@code _}-prefixed method name, ACP v1
	 * Extensibility) to the client and reads its result as the given type. A client that
	 * does not handle the method answers "Method not found" (-32601), which fails the Mono
	 * with an {@link com.agentclientprotocol.sdk.spec.AcpError}. The SDK does not check
	 * capabilities for extension methods: advertise and check them in the {@code _meta} of
	 * the capability objects.
	 * @param <T> the result type
	 * @param method the method name, which must start with {@code _}
	 * @param params the params, any value the JSON mapper can write
	 * @param resultType the type the result is read as
	 * @return a Mono emitting the result, or completing empty when the client answers
	 * {@code "result": null}
	 * @throws IllegalArgumentException if the method name does not start with {@code _}
	 */
	<T> Mono<T> sendExtRequest(String method, Object params, TypeRef<T> resultType);

	/**
	 * Sends a custom extension request to the client and returns its result as the raw
	 * JSON value: a {@code Map}, {@code List}, {@code String}, {@code Number} or
	 * {@code Boolean}.
	 * @param method the method name, which must start with {@code _}
	 * @param params the params, any value the JSON mapper can write
	 * @return a Mono emitting the result, or completing empty when the client answers
	 * {@code "result": null}
	 * @throws IllegalArgumentException if the method name does not start with {@code _}
	 * @see #sendExtRequest(String, Object, TypeRef)
	 */
	Mono<Object> sendExtRequest(String method, Object params);

	/**
	 * Sends a custom extension notification ({@code _}-prefixed method name) to the
	 * client. A client without a handler for it ignores it.
	 * @param method the method name, which must start with {@code _}
	 * @param params the params, any value the JSON mapper can write
	 * @return a Mono that completes when the notification is sent
	 * @throws IllegalArgumentException if the method name does not start with {@code _}
	 */
	Mono<Void> sendExtNotification(String method, Object params);

	/**
	 * Closes the agent gracefully, allowing pending operations to complete.
	 * @return A Mono that completes when the agent is closed
	 */
	Mono<Void> closeGracefully();

	/**
	 * Closes the agent immediately.
	 */
	void close();

}

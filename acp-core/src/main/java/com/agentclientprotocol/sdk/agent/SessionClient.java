/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import reactor.core.publisher.Mono;

/**
 * The protocol layer of a prompt turn: the raw ACP requests an agent sends the client (files,
 * terminals, permission and elicitation), each taking the ACP request record and returning the
 * client's answer as a Reactor {@code Mono}. Get it from {@link PromptContext#client()}; the
 * context itself holds the convenience calls built on these ({@link PromptContext#readFile},
 * {@link PromptContext#askPermission}, {@link PromptContext#execute} and the rest), which fill in
 * the session ID for you. Use this one for what the convenience calls do not cover: a line range,
 * a permission request for a tool call the agent announced itself, a terminal the handler manages
 * step by step, or an elicitation.
 *
 * <pre>{@code
 * context.client()
 *     .readTextFile(new AcpSchema.ReadTextFileRequest(context.getSessionId(), path, 10, 20))
 *     .map(AcpSchema.ReadTextFileResponse::content)
 * }</pre>
 *
 * <p>The calls are those of {@link AcpAsyncAgent}, with the same checks: once the client has
 * initialized, a call that needs a capability the client did not advertise (reading or writing
 * files, any terminal method, an elicitation mode) fails with
 * {@link com.agentclientprotocol.sdk.error.AcpCapabilityException} without being sent. An error
 * answer fails the {@code Mono} with {@link com.agentclientprotocol.sdk.spec.AcpError}, and no
 * answer within the agent's request timeout with a {@link java.util.concurrent.TimeoutException}.
 * Nothing is sent until the {@code Mono} is subscribed, and a request still waiting when the SDK
 * cancels the prompt handler is cancelled with it. The requests carry their own session ID, which
 * should be the prompt's ({@link PromptContext#getSessionId()}).
 *
 * <p>Implementations: the SDK supplies it; implement this interface only for test doubles.
 *
 * @author Mark Pollack
 * @see PromptContext#client()
 * @see SyncSessionClient
 */
public interface SessionClient {

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

	/**
	 * Asks the client to let the user approve a tool call ({@code session/request_permission}). The
	 * request describes the tool call and the options to choose from; every client handles this
	 * request, so no capability is checked.
	 * @param request the session, the tool call and the permission options
	 * @return a {@code Mono} emitting the user's choice: the selected option, or a cancelled
	 * outcome when the client cancelled the prompt turn
	 */
	Mono<AcpSchema.RequestPermissionResponse> requestPermission(AcpSchema.RequestPermissionRequest request);

	/**
	 * Asks the client to start a command in a new terminal ({@code terminal/create}). The client
	 * must have advertised {@code terminal}. ACP requires the agent to release every terminal it
	 * creates with {@link #releaseTerminal}; {@link PromptContext#execute(Command)} does the whole
	 * sequence for you.
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

}

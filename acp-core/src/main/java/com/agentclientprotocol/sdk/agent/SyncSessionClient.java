/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * The protocol layer of a prompt turn, with blocking calls: the raw ACP requests an agent sends
 * the client (files, terminals, permission and elicitation), each taking the ACP request record
 * and returning once the client has answered. Get it from {@link SyncPromptContext#client()}; the
 * context itself holds the convenience calls built on these ({@link SyncPromptContext#readFile},
 * {@link SyncPromptContext#askPermission}, {@link SyncPromptContext#execute} and the rest), which
 * fill in the session ID for you.
 *
 * <pre>{@code
 * String head = context.client()
 *     .readTextFile(new AcpSchema.ReadTextFileRequest(context.getSessionId(), path, 1, 20))
 *     .content();
 * }</pre>
 *
 * <p>Each call blocks on the corresponding {@link SessionClient} call and throws its failure: an
 * {@link com.agentclientprotocol.sdk.error.AcpCapabilityException} for a capability the client did
 * not advertise (nothing is sent), an {@link com.agentclientprotocol.sdk.spec.AcpError} for an
 * error answer, and an {@link com.agentclientprotocol.sdk.error.AcpTimeoutException} once the
 * agent's request timeout passes. When the SDK cancels the handler, a call blocked at that moment
 * throws {@link java.util.concurrent.CancellationException} and leaves the thread's interrupt flag
 * set.
 *
 * <p>Implementations: the SDK supplies it; implement this interface only for test doubles.
 *
 * @author Mark Pollack
 * @see SyncPromptContext#client()
 * @see SessionClient
 */
public interface SyncSessionClient {

	/**
	 * Asks the client for the content of a text file ({@code fs/read_text_file}), including unsaved
	 * changes in its editor, and waits for it.
	 * @param request the session, an absolute path, and optionally a 1-based first line and a line
	 * limit
	 * @return the file content
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if the client did not
	 * advertise {@code fs.readTextFile}
	 */
	AcpSchema.ReadTextFileResponse readTextFile(AcpSchema.ReadTextFileRequest request);

	/**
	 * Asks the client to write a text file ({@code fs/write_text_file}) and waits until it is
	 * written; ACP requires the client to create the file if it does not exist.
	 * @param request the session, an absolute path and the new content
	 * @return the client's empty answer
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if the client did not
	 * advertise {@code fs.writeTextFile}
	 */
	AcpSchema.WriteTextFileResponse writeTextFile(AcpSchema.WriteTextFileRequest request);

	/**
	 * Asks the client to let the user approve a tool call ({@code session/request_permission}) and
	 * waits for the user's choice, within the agent's request timeout. Every client handles this
	 * request, so no capability is checked.
	 * @param request the session, the tool call and the permission options
	 * @return the user's choice: the selected option, or a cancelled outcome when the client
	 * cancelled the prompt turn
	 */
	AcpSchema.RequestPermissionResponse requestPermission(AcpSchema.RequestPermissionRequest request);

	/**
	 * Asks the client to start a command in a new terminal ({@code terminal/create}) and waits for
	 * the terminal's ID. ACP requires the agent to release every terminal it creates with
	 * {@link #releaseTerminal}; {@link SyncPromptContext#execute(Command)} does the whole sequence
	 * for you.
	 * @param request the session, the command, its arguments and optionally a working directory,
	 * environment variables and an output limit
	 * @return the new terminal's ID
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if the client did not
	 * advertise {@code terminal}
	 */
	AcpSchema.CreateTerminalResponse createTerminal(AcpSchema.CreateTerminalRequest request);

	/**
	 * Asks the client for a terminal's output so far ({@code terminal/output}), without waiting for
	 * the command to end.
	 * @param request the session and the terminal ID
	 * @return the output, whether it was truncated, and the exit status if the command has ended
	 */
	AcpSchema.TerminalOutputResponse getTerminalOutput(AcpSchema.TerminalOutputRequest request);

	/**
	 * Asks the client to release a terminal ({@code terminal/release}), which kills its command if
	 * it is still running, and waits until it is released. The terminal ID is invalid afterwards.
	 * @param request the session and the terminal ID
	 * @return the client's empty answer
	 */
	AcpSchema.ReleaseTerminalResponse releaseTerminal(AcpSchema.ReleaseTerminalRequest request);

	/**
	 * Waits until a terminal's command has ended ({@code terminal/wait_for_exit}). The wait counts
	 * against the agent's request timeout, so a long command makes this call throw.
	 * @param request the session and the terminal ID
	 * @return the exit code or the signal that ended the command
	 */
	AcpSchema.WaitForTerminalExitResponse waitForTerminalExit(AcpSchema.WaitForTerminalExitRequest request);

	/**
	 * Asks the client to kill a terminal's command ({@code terminal/kill}) without releasing the
	 * terminal, and waits until it is killed. The output and exit status can still be read, and the
	 * terminal must still be released.
	 * @param request the session and the terminal ID
	 * @return the client's empty answer
	 */
	AcpSchema.KillTerminalCommandResponse killTerminal(AcpSchema.KillTerminalCommandRequest request);

	/**
	 * Asks the client to collect structured input from the user ({@code elicitation/create}), with
	 * a form or by sending the user to a URL, and waits for the answer, within the agent's request
	 * timeout.
	 * @param request the elicitation, made with {@link AcpSchema.CreateElicitationRequest#form} or
	 * {@link AcpSchema.CreateElicitationRequest#url}
	 * @return the user's answer: accept (with the form content), decline or cancel
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if the client did not
	 * advertise the request's mode
	 */
	AcpSchema.CreateElicitationResponse createElicitation(AcpSchema.CreateElicitationRequest request);

	/**
	 * Tells the client that the outside interaction of a URL-mode elicitation has finished
	 * ({@code elicitation/complete}). Returns once the notification has been handed to the
	 * transport.
	 * @param notification the ID of the elicitation that finished
	 */
	void completeElicitation(AcpSchema.CompleteElicitationNotification notification);

}

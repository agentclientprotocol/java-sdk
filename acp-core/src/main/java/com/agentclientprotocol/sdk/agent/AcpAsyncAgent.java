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
 * A running ACP agent with a Reactor API: it answers the client with the handlers registered on
 * {@link AcpAgent.AsyncAgentBuilder}, and it sends the client requests and notifications (session
 * updates, files, terminals, permission and elicitation requests, extension methods). Get one from
 * {@link AcpAgent#async(AcpAgentTransport)}, or from {@link AcpSyncAgent#async()}. Use
 * {@link AcpSyncAgent} for the same calls as blocking methods.
 *
 * <p>Inside a prompt handler, use the {@link PromptContext} it receives: it makes the same calls
 * for the prompt's session. Use the agent itself outside a prompt turn, for example to send a
 * {@code ConfigOptionUpdate} after a {@code session/set_config_option}; a builder handler receives
 * it as a second parameter ({@link AgentAwareHandler}).
 *
 * <p>{@link #start()} starts the transport, and the agent answers from then on;
 * {@link #awaitTermination()} completes when the transport has ended; {@link #closeGracefully()}
 * and {@link #close()} shut the agent down. An agent serves one transport, and a transport can be
 * started once.
 *
 * <p>Calls to the client return a {@code Mono} and send nothing until it is subscribed. Before
 * {@link #start()} they fail with {@link IllegalStateException}. Once the client's
 * {@code initialize} request has arrived, a call that needs a capability the client did not
 * advertise (reading or writing files, any terminal method, an elicitation mode) fails with
 * {@link com.agentclientprotocol.sdk.error.AcpCapabilityException} without being sent; before it,
 * nothing is checked. An error answer from the client fails the {@code Mono} with
 * {@link com.agentclientprotocol.sdk.spec.AcpError}. If the client does not answer within the
 * builder's request timeout (60 seconds by default), the {@code Mono} fails with a
 * {@link java.util.concurrent.TimeoutException} and the agent sends the client a
 * {@code $/cancel_request}; disposing the {@code Mono} before the answer sends one too. Methods may
 * be called from several threads at once.
 *
 * <p>Implementations: the SDK supplies the implementation the builder returns; implement this
 * interface only for test doubles.
 *
 * @author Mark Pollack
 * @see AcpAgent
 * @see AcpSyncAgent
 */
public interface AcpAsyncAgent {

	/**
	 * Starts the agent: it creates the agent's protocol session, which starts the transport, and
	 * from then on the agent answers the client. The {@code Mono} does not wait for the client to
	 * connect or send anything.
	 * @return a {@code Mono} that completes once the transport has been started; it fails with
	 * {@link IllegalStateException} if the transport refuses to start, for example because it was
	 * started before
	 */
	Mono<Void> start();

	/**
	 * Returns a {@code Mono} that completes when the agent's transport has ended. Block on it to
	 * keep a process alive while the transport's daemon threads do the work.
	 *
	 * <p>On stdio, the transport ends once the client has closed the agent's input and every
	 * request received before has been answered, bounded by the transport's drain timeout (see
	 * {@link com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport}), so exiting then
	 * loses no reply.
	 *
	 * <pre>{@code
	 * agent.start().block();
	 * agent.awaitTermination().block();
	 * }</pre>
	 *
	 * @return a {@code Mono} that completes when the transport has ended
	 */
	Mono<Void> awaitTermination();

	/**
	 * Returns what the client advertised in its {@code initialize} request. Check it before a call
	 * that needs a capability, for example {@code supportsReadTextFile()} before
	 * {@link #readTextFile}. It is recorded when the request arrives, before the initialize handler
	 * runs, so the handler can already read it.
	 * @return the client's capabilities, or {@code null} if the client has not initialized
	 */
	@Nullable NegotiatedCapabilities getClientCapabilities();

	/**
	 * Sends a {@code session/update} notification to the client, carrying one
	 * {@link AcpSchema.SessionUpdate}: a message or thought chunk, a tool call, a plan, a config
	 * option change and so on. During a prompt turn the Java client hands the updates to its
	 * update handlers in order, before the prompt's answer. Updates may also be sent between turns.
	 * @param sessionId the ACP session the update belongs to
	 * @param update the update
	 * @return a {@code Mono} that completes when the notification has been handed to the transport
	 */
	Mono<Void> sendSessionUpdate(String sessionId, AcpSchema.SessionUpdate update);

	/**
	 * Asks the client to let the user approve a tool call ({@code session/request_permission}).
	 * Every client handles this request, so no capability is checked.
	 * @param request the session, the tool call and the permission options
	 * @return a {@code Mono} emitting the user's choice: the selected option, or a cancelled
	 * outcome when the client cancelled the prompt turn
	 */
	Mono<AcpSchema.RequestPermissionResponse> requestPermission(AcpSchema.RequestPermissionRequest request);

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
	 * Asks the client to start a command in a new terminal ({@code terminal/create}). The client
	 * must have advertised {@code terminal}, and ACP requires the agent to release the terminal
	 * with {@link #releaseTerminal} when done. {@link PromptContext#execute(Command)} does the
	 * whole sequence.
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
	 * ({@code terminal/wait_for_exit}). The wait counts against the request timeout.
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
	 * Asks the client to collect structured input from the user ({@code elicitation/create}), with
	 * a form built from a schema or by sending the user to a URL. The client must have advertised
	 * the request's mode; a mode this SDK does not know only needs elicitation to be advertised at
	 * all.
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

	/**
	 * Sends a custom extension request ({@code _}-prefixed method name, ACP v1 Extensibility) to
	 * the client and reads its result as the given type. A client that does not handle the method
	 * answers "Method not found" ({@code -32601}), which fails the {@code Mono} with
	 * {@link com.agentclientprotocol.sdk.spec.AcpError}. The SDK checks no capability for extension
	 * methods: advertise and check them in the {@code _meta} of the capability objects.
	 * @param <T> the result type
	 * @param method the method name, which must start with {@code _}
	 * @param params the params, any value the JSON mapper can write
	 * @param resultType the type the result is read as
	 * @return a {@code Mono} emitting the result, or completing empty when the client answers
	 * {@code "result": null}
	 * @throws IllegalArgumentException if the method name does not start with {@code _}
	 */
	<T> Mono<T> sendExtRequest(String method, Object params, TypeRef<T> resultType);

	/**
	 * Sends a custom extension request to the client and returns its result as the raw JSON value:
	 * a {@code Map}, {@code List}, {@code String}, {@code Number} or {@code Boolean}.
	 * @param method the method name, which must start with {@code _}
	 * @param params the params, any value the JSON mapper can write
	 * @return a {@code Mono} emitting the result, or completing empty when the client answers
	 * {@code "result": null}
	 * @throws IllegalArgumentException if the method name does not start with {@code _}
	 * @see #sendExtRequest(String, Object, TypeRef)
	 */
	Mono<Object> sendExtRequest(String method, Object params);

	/**
	 * Sends a custom extension notification ({@code _}-prefixed method name) to the client. A
	 * client without a handler for it ignores it.
	 * @param method the method name, which must start with {@code _}
	 * @param params the params, any value the JSON mapper can write
	 * @return a {@code Mono} that completes when the notification has been handed to the transport
	 * @throws IllegalArgumentException if the method name does not start with {@code _}
	 */
	Mono<Void> sendExtNotification(String method, Object params);

	/**
	 * Shuts the agent down, then closes the transport gracefully. Requests from the client still
	 * being handled are cancelled and answered as cancelled ({@code -32800}) as far as the
	 * transport still delivers, and requests the agent sent the client and is still waiting for
	 * fail at once. What a graceful close means beyond that is up to the transport. Before
	 * {@link #start()} it completes at once.
	 * @return a {@code Mono} that completes when the transport has closed
	 */
	Mono<Void> closeGracefully();

	/**
	 * Shuts the agent down and closes the transport at once. Requests still being handled are
	 * cancelled, and requests waiting for the client fail.
	 */
	void close();

}

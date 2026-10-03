/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.time.Duration;

import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.SyncCalls;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A running ACP agent with blocking calls: the agent {@link AcpAsyncAgent} describes, wrapped so
 * that each call waits for its result. {@link AcpAgent.SyncAgentBuilder#build()} returns one. Use
 * it for an agent written as plain blocking Java: {@link #run()} starts it and blocks until the
 * client is gone, which is the usual {@code main} of a stdio agent.
 *
 * <p>Inside a prompt handler, use the {@link SyncPromptContext} it receives. Other handlers get
 * only their request; one that needs the agent, for example to send a {@code ConfigOptionUpdate},
 * captures the built agent:
 *
 * <pre>{@code
 * AtomicReference<AcpSyncAgent> self = new AtomicReference<>();
 * self.set(AcpAgent.sync(transport)
 *     .setSessionConfigOptionHandler(request -> {
 *         self.get().sendSessionUpdate(request.sessionId(),
 *             new AcpSchema.ConfigOptionUpdate(options));
 *         return new AcpSchema.SetSessionConfigOptionResponse(options);
 *     })
 *     .build());
 * }</pre>
 *
 * <p>Each call blocks on the asynchronous agent's {@code Mono} ({@link #async()} returns that
 * agent) for at most the block timeout, 5 minutes unless given to the constructor. Requests to the
 * client usually end sooner, at the builder's request timeout (60 seconds by default). Either
 * timeout throws {@link com.agentclientprotocol.sdk.error.AcpTimeoutException}, whose cause is the
 * {@link java.util.concurrent.TimeoutException}; an interrupt of the waiting thread throws
 * {@link java.util.concurrent.CancellationException} and leaves the interrupt flag set. Other
 * failures are thrown as they are:
 * {@link IllegalStateException} before {@link #start()},
 * {@link com.agentclientprotocol.sdk.error.AcpCapabilityException} for a capability the client did
 * not advertise, and {@link com.agentclientprotocol.sdk.spec.AcpError} for an error answer from the
 * client. Methods may be called from several threads at once, but not from a thread that must not
 * block.
 *
 * @author Mark Pollack
 * @see AcpAsyncAgent
 * @see AcpAgent
 */
public class AcpSyncAgent implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(AcpSyncAgent.class);

	private static final Duration DEFAULT_BLOCK_TIMEOUT = Duration.ofMinutes(5);

	/** The longest {@link #close()} waits for a graceful close before closing at once. */
	private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(10);

	private final AcpAsyncAgent asyncAgent;

	private final Duration blockTimeout;

	/**
	 * Wraps an asynchronous agent with blocking calls that wait at most 5 minutes. The synchronous
	 * builder uses this; call it yourself for a blocking view of an agent built with
	 * {@link AcpAgent#async(AcpAgentTransport)}. Both views share the one agent.
	 * @param asyncAgent the agent to wrap; must not be null
	 */
	public AcpSyncAgent(AcpAsyncAgent asyncAgent) {
		this(asyncAgent, DEFAULT_BLOCK_TIMEOUT);
	}

	/**
	 * Wraps an asynchronous agent with blocking calls that wait at most the given time.
	 * @param asyncAgent the agent to wrap; must not be null
	 * @param blockTimeout the most each blocking call waits, except {@link #awaitTermination()} and
	 * {@link #run()}, which wait without limit
	 */
	public AcpSyncAgent(AcpAsyncAgent asyncAgent, Duration blockTimeout) {
		this.asyncAgent = asyncAgent;
		this.blockTimeout = blockTimeout;
	}

	/**
	 * Starts the agent, as {@link AcpAsyncAgent#start()} does, and returns without waiting for the
	 * client. Call {@link #awaitTermination()} afterwards, or use {@link #run()}, to block until
	 * the transport has ended.
	 * @throws IllegalStateException if the transport refuses to start, for example because it was
	 * started before
	 */
	public void start() {
		SyncCalls.block(asyncAgent.start(), blockTimeout);
	}

	/**
	 * Blocks, without a time limit, until the agent's transport has ended. On stdio that is once
	 * the client has closed the agent's input and every request received before has been answered.
	 * Use it to keep the main thread alive when the transport's threads are daemon threads, as the
	 * stdio transport's are.
	 *
	 * <pre>{@code
	 * agent.start();
	 * agent.awaitTermination();
	 * }</pre>
	 */
	public void awaitTermination() {
		SyncCalls.block(asyncAgent.awaitTermination());
	}

	/**
	 * Starts the agent and blocks until its transport has ended: {@link #start()}, then
	 * {@link #awaitTermination()}.
	 *
	 * <pre>{@code
	 * public static void main(String[] args) {
	 *     AcpSyncAgent agent = AcpAgent.sync(new StdioAcpAgentTransport())
	 *         .initializeHandler(request -> AcpSchema.InitializeResponse.ok())
	 *         .newSessionHandler(request -> new AcpSchema.NewSessionResponse(
	 *             UUID.randomUUID().toString(), null, null))
	 *         .promptHandler((request, context) -> {
	 *             context.sendMessage("Hello");
	 *             return AcpSchema.PromptResponse.endTurn();
	 *         })
	 *         .build();
	 *     agent.run();
	 * }
	 * }</pre>
	 *
	 * @throws IllegalStateException if the transport refuses to start
	 */
	public void run() {
		start();
		awaitTermination();
	}

	/**
	 * Sends a {@code session/update} notification to the client, carrying one
	 * {@link AcpSchema.SessionUpdate}, and returns once it has been handed to the transport.
	 * Updates may also be sent between prompt turns.
	 * @param sessionId the ACP session the update belongs to
	 * @param update the update
	 * @throws IllegalStateException if the agent is not started
	 */
	public void sendSessionUpdate(String sessionId, AcpSchema.SessionUpdate update) {
		SyncCalls.block(asyncAgent.sendSessionUpdate(sessionId, update), blockTimeout);
	}

	/**
	 * Sends a custom extension request ({@code _}-prefixed method name) to the client and blocks
	 * for its result, read as the given type. A client that does not handle the method answers
	 * "Method not found" ({@code -32601}), thrown as
	 * {@link com.agentclientprotocol.sdk.spec.AcpError}.
	 * @param <T> the result type
	 * @param method the method name, which must start with {@code _}
	 * @param params the params, any value the JSON mapper can write
	 * @param resultType the type the result is read as
	 * @return the result, or {@code null} when the client answers {@code "result": null}
	 * @throws IllegalArgumentException if the method name does not start with {@code _}
	 * @see AcpAsyncAgent#sendExtRequest(String, Object, TypeRef)
	 */
	public <T> @Nullable T sendExtRequest(String method, Object params, TypeRef<T> resultType) {
		return SyncCalls.block(asyncAgent.sendExtRequest(method, params, resultType), blockTimeout);
	}

	/**
	 * Sends a custom extension request to the client and blocks for its result, as the raw JSON
	 * value: a {@code Map}, {@code List}, {@code String}, {@code Number} or {@code Boolean}.
	 * @param method the method name, which must start with {@code _}
	 * @param params the params, any value the JSON mapper can write
	 * @return the result, or {@code null} when the client answers {@code "result": null}
	 * @throws IllegalArgumentException if the method name does not start with {@code _}
	 * @see AcpAsyncAgent#sendExtRequest(String, Object)
	 */
	public @Nullable Object sendExtRequest(String method, Object params) {
		return SyncCalls.block(asyncAgent.sendExtRequest(method, params), blockTimeout);
	}

	/**
	 * Sends a custom extension notification ({@code _}-prefixed method name) to the client and
	 * returns once it has been handed to the transport. A client without a handler for it ignores
	 * it.
	 * @param method the method name, which must start with {@code _}
	 * @param params the params, any value the JSON mapper can write
	 * @throws IllegalArgumentException if the method name does not start with {@code _}
	 */
	public void sendExtNotification(String method, Object params) {
		SyncCalls.block(asyncAgent.sendExtNotification(method, params), blockTimeout);
	}

	/**
	 * Asks the client to let the user approve a tool call ({@code session/request_permission}) and
	 * waits for the user's choice. Every client handles this request, so no capability is checked.
	 * @param request the session, the tool call and the permission options
	 * @return the user's choice: the selected option, or a cancelled outcome when the client
	 * cancelled the prompt turn
	 */
	public AcpSchema.RequestPermissionResponse requestPermission(AcpSchema.RequestPermissionRequest request) {
		return SyncBlocking.awaitResponse(asyncAgent.requestPermission(request), blockTimeout);
	}

	/**
	 * Asks the client for the content of a text file ({@code fs/read_text_file}), including unsaved
	 * changes in its editor, and waits for it.
	 * @param request the session, an absolute path, and optionally a 1-based first line and a line
	 * limit
	 * @return the file content
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if the client did not
	 * advertise {@code fs.readTextFile}
	 */
	public AcpSchema.ReadTextFileResponse readTextFile(AcpSchema.ReadTextFileRequest request) {
		return SyncBlocking.awaitResponse(asyncAgent.readTextFile(request), blockTimeout);
	}

	/**
	 * Asks the client to write a text file ({@code fs/write_text_file}) and waits until it is
	 * written; ACP requires the client to create the file if it does not exist.
	 * @param request the session, an absolute path and the new content
	 * @return the client's empty answer
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if the client did not
	 * advertise {@code fs.writeTextFile}
	 */
	public AcpSchema.WriteTextFileResponse writeTextFile(AcpSchema.WriteTextFileRequest request) {
		return SyncBlocking.awaitResponse(asyncAgent.writeTextFile(request), blockTimeout);
	}

	/**
	 * Asks the client to start a command in a new terminal ({@code terminal/create}) and waits for
	 * the terminal's ID. ACP requires the agent to release the terminal with
	 * {@link #releaseTerminal} when done; {@link SyncPromptContext#execute(Command)} does the whole
	 * sequence.
	 * @param request the session, the command, its arguments and optionally a working directory,
	 * environment variables and an output limit
	 * @return the new terminal's ID
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if the client did not
	 * advertise {@code terminal}
	 */
	public AcpSchema.CreateTerminalResponse createTerminal(AcpSchema.CreateTerminalRequest request) {
		return SyncBlocking.awaitResponse(asyncAgent.createTerminal(request), blockTimeout);
	}

	/**
	 * Asks the client for a terminal's output so far ({@code terminal/output}), without waiting for
	 * the command to end.
	 * @param request the session and the terminal ID
	 * @return the output, whether it was truncated, and the exit status if the command has ended
	 */
	public AcpSchema.TerminalOutputResponse getTerminalOutput(AcpSchema.TerminalOutputRequest request) {
		return SyncBlocking.awaitResponse(asyncAgent.getTerminalOutput(request), blockTimeout);
	}

	/**
	 * Asks the client to release a terminal ({@code terminal/release}), which kills its command if
	 * it is still running, and waits until it is released. The terminal ID is invalid afterwards.
	 * @param request the session and the terminal ID
	 * @return the client's empty answer
	 */
	public AcpSchema.ReleaseTerminalResponse releaseTerminal(AcpSchema.ReleaseTerminalRequest request) {
		return SyncBlocking.awaitResponse(asyncAgent.releaseTerminal(request), blockTimeout);
	}

	/**
	 * Waits until a terminal's command has ended ({@code terminal/wait_for_exit}). The wait counts
	 * against the request timeout.
	 * @param request the session and the terminal ID
	 * @return the exit code or the signal that ended the command
	 */
	public AcpSchema.WaitForTerminalExitResponse waitForTerminalExit(AcpSchema.WaitForTerminalExitRequest request) {
		return SyncBlocking.awaitResponse(asyncAgent.waitForTerminalExit(request), blockTimeout);
	}

	/**
	 * Asks the client to kill a terminal's command ({@code terminal/kill}) without releasing the
	 * terminal, and waits until it is killed. The output and exit status can still be read, and the
	 * terminal must still be released.
	 * @param request the session and the terminal ID
	 * @return the client's empty answer
	 */
	public AcpSchema.KillTerminalCommandResponse killTerminal(AcpSchema.KillTerminalCommandRequest request) {
		return SyncBlocking.awaitResponse(asyncAgent.killTerminal(request), blockTimeout);
	}

	/**
	 * Asks the client to collect structured input from the user ({@code elicitation/create}), with
	 * a form or by sending the user to a URL, and waits for the answer.
	 * @param request the elicitation, made with {@link AcpSchema.CreateElicitationRequest#form} or
	 * {@link AcpSchema.CreateElicitationRequest#url}
	 * @return the user's answer: accept (with the form content), decline or cancel
	 * @throws com.agentclientprotocol.sdk.error.AcpCapabilityException if the client did not
	 * advertise the request's mode
	 */
	public AcpSchema.CreateElicitationResponse createElicitation(AcpSchema.CreateElicitationRequest request) {
		return SyncBlocking.awaitResponse(asyncAgent.createElicitation(request), blockTimeout);
	}

	/**
	 * Tells the client that the outside interaction of a URL-mode elicitation has finished
	 * ({@code elicitation/complete}), and returns once the notification has been handed to the
	 * transport.
	 * @param notification the ID of the elicitation that finished
	 */
	public void completeElicitation(AcpSchema.CompleteElicitationNotification notification) {
		SyncCalls.block(asyncAgent.completeElicitation(notification), blockTimeout);
	}

	/**
	 * Returns what the client advertised in its {@code initialize} request. Check it before a call
	 * that needs a capability, for example {@code supportsTerminal()} before
	 * {@link #createTerminal}. It is recorded when the request arrives, before the initialize
	 * handler runs.
	 * @return the client's capabilities, or {@code null} if the client has not initialized
	 */
	public com.agentclientprotocol.sdk.capabilities.@Nullable NegotiatedCapabilities getClientCapabilities() {
		return asyncAgent.getClientCapabilities();
	}

	/**
	 * Returns the asynchronous agent this one wraps: the same agent, with calls that return
	 * {@code Mono}s. {@link AcpAgentFactory#sync} hands it to listener transports.
	 * @return the asynchronous agent
	 */
	public AcpAsyncAgent async() {
		return asyncAgent;
	}

	/**
	 * Shuts the agent down as {@link AcpAsyncAgent#closeGracefully()} does, and waits for the
	 * transport to close, at most the block timeout.
	 */
	public void closeGracefully() {
		SyncCalls.block(asyncAgent.closeGracefully(), blockTimeout);
	}

	/**
	 * Closes the agent the way try-with-resources expects: gracefully, as
	 * {@link #closeGracefully()} does, waiting at most 10 seconds (or the block timeout, if
	 * shorter), then at once, as {@link AcpAsyncAgent#close()} does, if that failed or took longer.
	 * To close at once without waiting, call {@code async().close()}.
	 */
	@Override
	public void close() {
		Duration bound = this.blockTimeout.compareTo(CLOSE_TIMEOUT) < 0 ? this.blockTimeout : CLOSE_TIMEOUT;
		try {
			SyncCalls.block(asyncAgent.closeGracefully(), bound);
		}
		catch (RuntimeException e) {
			logger.warn("The agent did not close gracefully, closing it now: {}", e.toString());
			asyncAgent.close();
		}
	}

}

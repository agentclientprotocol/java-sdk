/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.error.AcpProtocolException;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentThoughtChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.CreateTerminalRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.EnvVariable;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionOption;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionOptionKind;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionSelected;
import com.agentclientprotocol.sdk.spec.AcpSchema.ReadTextFileRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.ReleaseTerminalRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.RequestPermissionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.TerminalOutputRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCall;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallStatus;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallUpdate;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallUpdateNotification;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolKind;
import com.agentclientprotocol.sdk.spec.AcpSchema.WaitForTerminalExitRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.WriteTextFileRequest;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Default implementation of {@link PromptContext} that delegates to an {@link AcpAsyncAgent}.
 *
 * <p>
 * This class is created internally by {@link DefaultAcpAsyncAgent} and passed to prompt handlers.
 * It provides a clean interface for handlers to access all agent capabilities without needing
 * a direct reference to the agent instance.
 *
 * @author Mark Pollack
 * @since 0.9.1
 */
class DefaultPromptContext implements PromptContext {

	private static final Logger logger = LoggerFactory.getLogger(DefaultPromptContext.class);

	private final AcpAsyncAgent agent;

	private final String sessionId;

	private final PromptCancellations.Signal cancellation;

	/**
	 * Creates a new prompt context wrapping the given agent.
	 * @param agent The agent to delegate to
	 * @param sessionId The session ID for this prompt invocation
	 */
	DefaultPromptContext(AcpAsyncAgent agent, String sessionId) {
		this.agent = agent;
		this.sessionId = sessionId;
		this.cancellation = (agent instanceof DefaultAcpAsyncAgent running) ? running.promptSignal(sessionId)
				: new PromptCancellations.Signal();
	}

	// ========================================================================
	// Low-Level API
	// ========================================================================

	@Override
	public Mono<Void> sendUpdate(AcpSchema.SessionUpdate update) {
		return agent.sendSessionUpdate(sessionId, update);
	}

	@Override
	public Mono<AcpSchema.ReadTextFileResponse> readTextFile(AcpSchema.ReadTextFileRequest request) {
		return agent.readTextFile(request);
	}

	@Override
	public Mono<AcpSchema.WriteTextFileResponse> writeTextFile(AcpSchema.WriteTextFileRequest request) {
		return agent.writeTextFile(request);
	}

	@Override
	public Mono<AcpSchema.RequestPermissionResponse> requestPermission(AcpSchema.RequestPermissionRequest request) {
		return agent.requestPermission(request);
	}

	@Override
	public Mono<AcpSchema.CreateTerminalResponse> createTerminal(AcpSchema.CreateTerminalRequest request) {
		return agent.createTerminal(request);
	}

	@Override
	public Mono<AcpSchema.TerminalOutputResponse> getTerminalOutput(AcpSchema.TerminalOutputRequest request) {
		return agent.getTerminalOutput(request);
	}

	@Override
	public Mono<AcpSchema.ReleaseTerminalResponse> releaseTerminal(AcpSchema.ReleaseTerminalRequest request) {
		return agent.releaseTerminal(request);
	}

	@Override
	public Mono<AcpSchema.WaitForTerminalExitResponse> waitForTerminalExit(
			AcpSchema.WaitForTerminalExitRequest request) {
		return agent.waitForTerminalExit(request);
	}

	@Override
	public Mono<AcpSchema.KillTerminalCommandResponse> killTerminal(AcpSchema.KillTerminalCommandRequest request) {
		return agent.killTerminal(request);
	}

	@Override
	public Mono<AcpSchema.CreateElicitationResponse> createElicitation(
			AcpSchema.CreateElicitationRequest request) {
		return agent.createElicitation(request);
	}

	@Override
	public Mono<Void> completeElicitation(AcpSchema.CompleteElicitationNotification notification) {
		return agent.completeElicitation(notification);
	}

	@Override
	public @Nullable NegotiatedCapabilities getClientCapabilities() {
		return agent.getClientCapabilities();
	}

	// ========================================================================
	// Cancellation
	// ========================================================================

	@Override
	public boolean isCancelled() {
		return cancellation.isCancelled();
	}

	@Override
	public Mono<Void> whenCancelled() {
		return cancellation.whenCancelled();
	}

	// ========================================================================
	// Convenience API
	// ========================================================================

	@Override
	public String getSessionId() {
		return sessionId;
	}

	@Override
	public Mono<Void> sendMessage(String text) {
		return sendUpdate(new AgentMessageChunk(new TextContent(text)));
	}

	@Override
	public Mono<Void> sendThought(String text) {
		return sendUpdate(new AgentThoughtChunk(new TextContent(text)));
	}

	@Override
	public Mono<String> readFile(String path) {
		return readFile(path, null, null);
	}

	@Override
	public Mono<String> readFile(String path, @Nullable Integer startLine, @Nullable Integer lineCount) {
		return readTextFile(new ReadTextFileRequest(sessionId, path, startLine, lineCount))
				.map(AcpSchema.ReadTextFileResponse::content);
	}

	@Override
	public Mono<Void> writeFile(String path, String content) {
		return writeTextFile(new WriteTextFileRequest(sessionId, path, content)).then();
	}

	@Override
	public Mono<Boolean> askPermission(String action) {
		return askPermission(action, ToolKind.OTHER);
	}

	@Override
	public Mono<Boolean> askPermission(String action, ToolKind kind) {
		List<PermissionOption> options = List.of(
				new PermissionOption("allow", "Allow", PermissionOptionKind.ALLOW_ONCE),
				new PermissionOption("deny", "Deny", PermissionOptionKind.REJECT_ONCE));

		return ask(action, kind, options)
				.map(response -> response.outcome() instanceof PermissionSelected s
						&& "allow".equals(s.optionId()));
	}

	/**
	 * Asks the user about a tool call the client knows: announces it with a pending
	 * {@code tool_call} update, sends the permission request naming it, then settles it with a
	 * {@code tool_call_update}: completed once the user answered, failed if the request was
	 * cancelled.
	 */
	private Mono<AcpSchema.RequestPermissionResponse> ask(String title, ToolKind kind, List<PermissionOption> options) {
		return Mono.defer(() -> {
			String toolCallId = UUID.randomUUID().toString();
			ToolCall announce = new ToolCall("tool_call", toolCallId, title, null, kind, ToolCallStatus.PENDING, null, null,
					null, null, null);
			ToolCallUpdate toolCall = new ToolCallUpdate(toolCallId, title, kind, ToolCallStatus.PENDING);
			return agent.sendSessionUpdate(sessionId, announce)
				.then(requestPermission(new RequestPermissionRequest(sessionId, toolCall, options)))
				.flatMap(response -> {
					ToolCallStatus status = (response.outcome() instanceof PermissionSelected) ? ToolCallStatus.COMPLETED
							: ToolCallStatus.FAILED;
					return agent
						.sendSessionUpdate(sessionId, new ToolCallUpdateNotification("tool_call_update", toolCallId, null, null, null,
								status, null, null, null, null, null))
						.thenReturn(response);
				});
		});
	}

	@Override
	public Mono<String> askChoice(String question, String... options) {
		if (options == null || options.length < 2) {
			return Mono.error(new IllegalArgumentException("At least 2 options are required"));
		}

		List<PermissionOption> permOptions = new ArrayList<>();
		for (int i = 0; i < options.length; i++) {
			permOptions.add(new PermissionOption(
					String.valueOf(i), options[i], PermissionOptionKind.ALLOW_ONCE));
		}

		return ask(question, ToolKind.OTHER, permOptions)
				.flatMap(response -> {
					if (response.outcome() instanceof PermissionSelected s) {
						for (int i = 0; i < options.length; i++) {
							if (String.valueOf(i).equals(s.optionId())) {
								return Mono.just(options[i]);
							}
						}
						return Mono.error(new AcpProtocolException(AcpErrorCodes.INTERNAL_ERROR,
								"The client chose option '" + s.optionId() + "', which askChoice did not offer (0 to "
										+ (options.length - 1) + ")"));
					}
					return Mono.empty();
				});
	}

	@Override
	public Mono<CommandResult> execute(String... commandAndArgs) {
		return execute(Command.of(commandAndArgs));
	}

	@Override
	public Mono<CommandResult> execute(Command command) {
		// Convert env map to list of EnvVariable; no variables leaves the wire field out
		List<EnvVariable> envList = null;
		if (!command.env().isEmpty()) {
			envList = command.env().entrySet().stream()
					.map(e -> new EnvVariable(e.getKey(), e.getValue()))
					.toList();
		}

		return createTerminal(new CreateTerminalRequest(
				sessionId, command.executable(), command.args(),
				command.cwd(), envList, command.outputByteLimit()))
			.flatMap(createResp -> {
				String terminalId = createResp.terminalId();
				ReleaseTerminalRequest releaseReq = new ReleaseTerminalRequest(sessionId, terminalId);
				// ACP: the agent MUST release every terminal it created (terminals.mdx), so the
				// terminal is released exactly once whether the command ends, a step fails, or the
				// caller cancels (the prompt was cancelled or timed out).
				AtomicBoolean releaseSent = new AtomicBoolean();
				Mono<Void> release = Mono.defer(() -> releaseSent.compareAndSet(false, true)
						? releaseTerminal(releaseReq).then() : Mono.empty());

				return waitForTerminalExit(new WaitForTerminalExitRequest(sessionId, terminalId))
						.flatMap(exitResp -> getTerminalOutput(new TerminalOutputRequest(sessionId, terminalId))
								.map(outputResp -> new CommandResult(outputResp.output(), exitResp.exitCode(),
										exitResp.signal(), outputResp.truncated())))
						// Release terminal after getting result, then return result
						.flatMap(result -> release.thenReturn(result))
						// On error, still release terminal before propagating error
						.onErrorResume(error -> release.then(Mono.error(error)))
						// Cancelled: nobody waits for the release any more, so send it on its own
						.doOnCancel(() -> release.subscribe(v -> {
						}, error -> logger.warn("Could not release terminal {} of a cancelled command: {}",
								terminalId, error.getMessage())));
			});
	}

}

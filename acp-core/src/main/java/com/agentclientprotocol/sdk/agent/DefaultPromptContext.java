/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
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
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallStatus;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallUpdate;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolKind;
import com.agentclientprotocol.sdk.spec.AcpSchema.WaitForTerminalExitRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.WriteTextFileRequest;
import org.jspecify.annotations.Nullable;
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

	private final AcpAsyncAgent agent;

	private final String sessionId;

	/**
	 * Creates a new prompt context wrapping the given agent.
	 * @param agent The agent to delegate to
	 * @param sessionId The session ID for this prompt invocation
	 */
	DefaultPromptContext(AcpAsyncAgent agent, String sessionId) {
		this.agent = agent;
		this.sessionId = sessionId;
	}

	// ========================================================================
	// Low-Level API
	// ========================================================================

	@Override
	public Mono<Void> sendUpdate(String sessionId, AcpSchema.SessionUpdate update) {
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
	// Convenience API
	// ========================================================================

	@Override
	public String getSessionId() {
		return sessionId;
	}

	@Override
	public Mono<Void> sendMessage(String text) {
		return sendUpdate(sessionId, new AgentMessageChunk(new TextContent(text)));
	}

	@Override
	public Mono<Void> sendThought(String text) {
		return sendUpdate(sessionId, new AgentThoughtChunk(new TextContent(text)));
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
		ToolCallUpdate toolCall = new ToolCallUpdate(UUID.randomUUID().toString(), action, ToolKind.EDIT, ToolCallStatus.PENDING);

		List<PermissionOption> options = List.of(
				new PermissionOption("allow", "Allow", PermissionOptionKind.ALLOW_ONCE),
				new PermissionOption("deny", "Deny", PermissionOptionKind.REJECT_ONCE));

		return requestPermission(new RequestPermissionRequest(sessionId, toolCall, options))
				.map(response -> response.outcome() instanceof PermissionSelected s
						&& "allow".equals(s.optionId()));
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

		ToolCallUpdate toolCall = new ToolCallUpdate(UUID.randomUUID().toString(), question, ToolKind.OTHER, ToolCallStatus.PENDING);

		return requestPermission(new RequestPermissionRequest(sessionId, toolCall, permOptions))
				.flatMap(response -> {
					if (response.outcome() instanceof PermissionSelected s) {
						int idx = Integer.parseInt(s.optionId());
						return Mono.just(options[idx]);
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

				return waitForTerminalExit(new WaitForTerminalExitRequest(sessionId, terminalId))
						.flatMap(exitResp -> getTerminalOutput(new TerminalOutputRequest(sessionId, terminalId))
								.map(outputResp -> new CommandResult(outputResp.output(), exitResp.exitCode(),
										exitResp.signal())))
						// Release terminal after getting result, then return result
						.flatMap(result -> releaseTerminal(releaseReq).thenReturn(result))
						// On error, still release terminal before propagating error
						.onErrorResume(error -> releaseTerminal(releaseReq).then(Mono.error(error)));
			});
	}

}

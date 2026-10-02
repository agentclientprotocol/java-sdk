/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.util.Optional;

import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.jspecify.annotations.Nullable;

/**
 * Default implementation of {@link SyncPromptContext} that wraps an async {@link PromptContext}
 * and provides blocking methods.
 *
 * <p>
 * This class is created internally by the sync-to-async handler converter in {@link AcpAgent.SyncAgentBuilder}.
 *
 * @author Mark Pollack
 * @since 0.9.1
 */
class DefaultSyncPromptContext implements SyncPromptContext {

	private final PromptContext asyncContext;

	/**
	 * Creates a new sync context wrapping the given async context.
	 * @param asyncContext The async context to wrap
	 */
	DefaultSyncPromptContext(PromptContext asyncContext) {
		this.asyncContext = asyncContext;
	}

	// ========================================================================
	// Low-Level API
	// ========================================================================

	@Override
	public void sendUpdate(String sessionId, AcpSchema.SessionUpdate update) {
		asyncContext.sendUpdate(sessionId, update).block();
	}

	@Override
	public AcpSchema.ReadTextFileResponse readTextFile(AcpSchema.ReadTextFileRequest request) {
		return SyncBlocking.awaitResponse(asyncContext.readTextFile(request));
	}

	@Override
	public AcpSchema.WriteTextFileResponse writeTextFile(AcpSchema.WriteTextFileRequest request) {
		return SyncBlocking.awaitResponse(asyncContext.writeTextFile(request));
	}

	@Override
	public AcpSchema.RequestPermissionResponse requestPermission(AcpSchema.RequestPermissionRequest request) {
		return SyncBlocking.awaitResponse(asyncContext.requestPermission(request));
	}

	@Override
	public AcpSchema.CreateTerminalResponse createTerminal(AcpSchema.CreateTerminalRequest request) {
		return SyncBlocking.awaitResponse(asyncContext.createTerminal(request));
	}

	@Override
	public AcpSchema.TerminalOutputResponse getTerminalOutput(AcpSchema.TerminalOutputRequest request) {
		return SyncBlocking.awaitResponse(asyncContext.getTerminalOutput(request));
	}

	@Override
	public AcpSchema.ReleaseTerminalResponse releaseTerminal(AcpSchema.ReleaseTerminalRequest request) {
		return SyncBlocking.awaitResponse(asyncContext.releaseTerminal(request));
	}

	@Override
	public AcpSchema.WaitForTerminalExitResponse waitForTerminalExit(AcpSchema.WaitForTerminalExitRequest request) {
		return SyncBlocking.awaitResponse(asyncContext.waitForTerminalExit(request));
	}

	@Override
	public AcpSchema.KillTerminalCommandResponse killTerminal(AcpSchema.KillTerminalCommandRequest request) {
		return SyncBlocking.awaitResponse(asyncContext.killTerminal(request));
	}

	@Override
	public AcpSchema.CreateElicitationResponse createElicitation(
			AcpSchema.CreateElicitationRequest request) {
		return SyncBlocking.awaitResponse(asyncContext.createElicitation(request));
	}

	@Override
	public void completeElicitation(AcpSchema.CompleteElicitationNotification notification) {
		asyncContext.completeElicitation(notification).block();
	}

	@Override
	public @Nullable NegotiatedCapabilities getClientCapabilities() {
		return asyncContext.getClientCapabilities();
	}

	@Override
	public PromptContext async() {
		return asyncContext;
	}

	// ========================================================================
	// Convenience API
	// ========================================================================

	@Override
	public String getSessionId() {
		return asyncContext.getSessionId();
	}

	@Override
	public void sendMessage(String text) {
		asyncContext.sendMessage(text).block();
	}

	@Override
	public void sendThought(String text) {
		asyncContext.sendThought(text).block();
	}

	@Override
	public String readFile(String path) {
		return SyncBlocking.awaitResponse(asyncContext.readFile(path));
	}

	@Override
	public String readFile(String path, @Nullable Integer startLine, @Nullable Integer lineCount) {
		return SyncBlocking.awaitResponse(asyncContext.readFile(path, startLine, lineCount));
	}

	@Override
	public Optional<String> tryReadFile(String path) {
		try {
			return Optional.of(readFile(path));
		}
		catch (Exception e) {
			return Optional.empty();
		}
	}

	@Override
	public void writeFile(String path, String content) {
		asyncContext.writeFile(path, content).block();
	}

	@Override
	public boolean askPermission(String action) {
		Boolean result = asyncContext.askPermission(action).block();
		return result != null && result;
	}

	@Override
	public Optional<String> askChoice(String question, String... options) {
		return Optional.ofNullable(asyncContext.askChoice(question, options).block());
	}

	@Override
	public CommandResult execute(String... commandAndArgs) {
		return SyncBlocking.awaitResponse(asyncContext.execute(commandAndArgs));
	}

	@Override
	public CommandResult execute(Command command) {
		return SyncBlocking.awaitResponse(asyncContext.execute(command));
	}

}

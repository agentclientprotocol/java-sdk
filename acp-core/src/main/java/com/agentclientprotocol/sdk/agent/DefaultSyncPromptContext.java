/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.util.Optional;
import java.util.concurrent.CancellationException;

import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.util.Assert;
import com.agentclientprotocol.sdk.spec.SyncCalls;
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
		SyncCalls.block(asyncContext.sendUpdate(sessionId, update));
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
		SyncCalls.block(asyncContext.completeElicitation(notification));
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
	// Cancellation
	// ========================================================================

	@Override
	public boolean isCancelled() {
		return asyncContext.isCancelled();
	}

	@Override
	public void onCancel(Runnable action) {
		Assert.notNull(action, "The action must not be null");
		asyncContext.whenCancelled().subscribe(null, null, action);
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
		SyncCalls.block(asyncContext.sendMessage(text));
	}

	@Override
	public void sendThought(String text) {
		SyncCalls.block(asyncContext.sendThought(text));
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
		catch (CancellationException e) {
			// The SDK cancels the handler by interrupting it: that is not a missing file.
			throw e;
		}
		catch (RuntimeException e) {
			return Optional.empty();
		}
	}

	@Override
	public void writeFile(String path, String content) {
		SyncCalls.block(asyncContext.writeFile(path, content));
	}

	@Override
	public boolean askPermission(String action) {
		Boolean result = SyncCalls.block(asyncContext.askPermission(action));
		return result != null && result;
	}

	@Override
	public boolean askPermission(String action, AcpSchema.ToolKind kind) {
		Boolean result = SyncCalls.block(asyncContext.askPermission(action, kind));
		return result != null && result;
	}

	@Override
	public Optional<String> askChoice(String question, String... options) {
		return Optional.ofNullable(SyncCalls.block(asyncContext.askChoice(question, options)));
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

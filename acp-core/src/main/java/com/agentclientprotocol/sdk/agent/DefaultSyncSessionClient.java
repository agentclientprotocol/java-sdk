/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import com.agentclientprotocol.sdk.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.SyncCalls;
import org.jspecify.annotations.Nullable;

/**
 * The {@link SyncSessionClient} of a {@link DefaultSyncPromptContext}: each call blocks on the
 * asynchronous one.
 */
final class DefaultSyncSessionClient implements SyncSessionClient {

	private final SessionClient client;

	DefaultSyncSessionClient(SessionClient client) {
		this.client = client;
	}

	@Override
	public AcpSchema.ReadTextFileResponse readTextFile(AcpSchema.ReadTextFileRequest request) {
		return SyncBlocking.awaitResponse(client.readTextFile(request));
	}

	@Override
	public AcpSchema.WriteTextFileResponse writeTextFile(AcpSchema.WriteTextFileRequest request) {
		return SyncBlocking.awaitResponse(client.writeTextFile(request));
	}

	@Override
	public AcpSchema.RequestPermissionResponse requestPermission(AcpSchema.RequestPermissionRequest request) {
		return SyncBlocking.awaitResponse(client.requestPermission(request));
	}

	@Override
	public AcpSchema.CreateTerminalResponse createTerminal(AcpSchema.CreateTerminalRequest request) {
		return SyncBlocking.awaitResponse(client.createTerminal(request));
	}

	@Override
	public AcpSchema.TerminalOutputResponse getTerminalOutput(AcpSchema.TerminalOutputRequest request) {
		return SyncBlocking.awaitResponse(client.getTerminalOutput(request));
	}

	@Override
	public AcpSchema.ReleaseTerminalResponse releaseTerminal(AcpSchema.ReleaseTerminalRequest request) {
		return SyncBlocking.awaitResponse(client.releaseTerminal(request));
	}

	@Override
	public AcpSchema.WaitForTerminalExitResponse waitForTerminalExit(AcpSchema.WaitForTerminalExitRequest request) {
		return SyncBlocking.awaitResponse(client.waitForTerminalExit(request));
	}

	@Override
	public AcpSchema.KillTerminalCommandResponse killTerminal(AcpSchema.KillTerminalCommandRequest request) {
		return SyncBlocking.awaitResponse(client.killTerminal(request));
	}

	@Override
	public AcpSchema.CreateElicitationResponse createElicitation(AcpSchema.CreateElicitationRequest request) {
		return SyncBlocking.awaitResponse(client.createElicitation(request));
	}

	@Override
	public void completeElicitation(AcpSchema.CompleteElicitationNotification notification) {
		SyncCalls.block(client.completeElicitation(notification));
	}

	@Override
	public <T> @Nullable T sendExtRequest(String method, Object params, TypeRef<T> resultType) {
		return SyncCalls.block(client.sendExtRequest(method, params, resultType));
	}

	@Override
	public @Nullable Object sendExtRequest(String method, Object params) {
		return SyncCalls.block(client.sendExtRequest(method, params));
	}

	@Override
	public void sendExtNotification(String method, Object params) {
		SyncCalls.block(client.sendExtNotification(method, params));
	}

}

/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import com.agentclientprotocol.sdk.spec.AcpSchema;
import reactor.core.publisher.Mono;

/**
 * The {@link SessionClient} of a {@link DefaultPromptContext}: each call is the agent's.
 */
final class DefaultSessionClient implements SessionClient {

	private final AcpAsyncAgent agent;

	DefaultSessionClient(AcpAsyncAgent agent) {
		this.agent = agent;
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
	public Mono<AcpSchema.WaitForTerminalExitResponse> waitForTerminalExit(AcpSchema.WaitForTerminalExitRequest request) {
		return agent.waitForTerminalExit(request);
	}

	@Override
	public Mono<AcpSchema.KillTerminalCommandResponse> killTerminal(AcpSchema.KillTerminalCommandRequest request) {
		return agent.killTerminal(request);
	}

	@Override
	public Mono<AcpSchema.CreateElicitationResponse> createElicitation(AcpSchema.CreateElicitationRequest request) {
		return agent.createElicitation(request);
	}

	@Override
	public Mono<Void> completeElicitation(AcpSchema.CompleteElicitationNotification notification) {
		return agent.completeElicitation(notification);
	}

}

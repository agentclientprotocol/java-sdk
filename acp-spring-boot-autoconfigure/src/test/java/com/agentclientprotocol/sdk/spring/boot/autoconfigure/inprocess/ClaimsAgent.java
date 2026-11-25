/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.inprocess;

import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Initialize;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;

/**
 * The agent of the in-process acceptance test: {@code @AcpAgent} and nothing of Spring's. A prompt
 * streams an update, asks permission, and streams what it was told.
 */
@AcpAgent(name = "claims-agent", version = "1.0")
public class ClaimsAgent implements AutoCloseable {

	static final AtomicInteger CLOSES = new AtomicInteger();

	@Initialize
	public InitializeResponse initialize(InitializeRequest request) {
		return InitializeResponse.ok();
	}

	@NewSession
	public NewSessionResponse newSession(NewSessionRequest request) {
		return new NewSessionResponse("claim-1", null, null);
	}

	@Prompt
	public PromptResponse prompt(PromptRequest request, SyncPromptContext context) {
		context.sendMessage("reviewing the claim");
		boolean approved = context.askPermission("approve the claim");
		context.sendMessage(approved ? "approved" : "refused");
		return PromptResponse.endTurn();
	}

	@Override
	public void close() {
		CLOSES.incrementAndGet();
	}

}

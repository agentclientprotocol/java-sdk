/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.it;

import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Initialize;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.spec.AcpSchema;

/**
 * The agent of the in-process acceptance test: {@code @AcpAgent} and nothing of Micronaut's. A prompt
 * streams an update, asks permission, and streams what it was told.
 */
@AcpAgent(name = "claims-agent", version = "1.0")
public class ClaimsAgent implements AutoCloseable {

	public static final AtomicInteger CLOSES = new AtomicInteger();

	@Initialize
	public AcpSchema.InitializeResponse initialize(AcpSchema.InitializeRequest request) {
		return AcpSchema.InitializeResponse.ok();
	}

	@NewSession
	public AcpSchema.NewSessionResponse newSession(AcpSchema.NewSessionRequest request) {
		return new AcpSchema.NewSessionResponse("claim-1", null, null);
	}

	@Prompt
	public AcpSchema.PromptResponse prompt(AcpSchema.PromptRequest request, SyncPromptContext context) {
		context.sendMessage("reviewing the claim");
		boolean approved = context.askPermission("approve the claim");
		context.sendMessage(approved ? "approved" : "refused");
		return AcpSchema.PromptResponse.endTurn();
	}

	@Override
	public void close() {
		CLOSES.incrementAndGet();
	}

}

/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.it;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.CloseSession;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import jakarta.inject.Inject;

/**
 * The sample agent: a bean with injected collaborators, served over the configured
 * transport. It greets, and keeps a count of prompts per session.
 */
@AcpAgent(name = "quarkus-greeter", version = "1.0.0")
public class GreeterAgent {

	private final Map<String, Integer> prompts = new ConcurrentHashMap<>();

	private final Greeting greeting;

	@Inject
	GreeterAgent(Greeting greeting) {
		this.greeting = greeting;
	}

	@NewSession
	AcpSchema.NewSessionResponse newSession(AcpSchema.NewSessionRequest request) {
		String id = UUID.randomUUID().toString();
		prompts.put(id, 0);
		return new AcpSchema.NewSessionResponse(id, null, null);
	}

	@Prompt
	AcpSchema.PromptResponse prompt(AcpSchema.PromptRequest request, SyncPromptContext context) {
		int count = prompts.merge(context.getSessionId(), 1, Integer::sum);
		context.sendMessage(greeting.greet(text(request)) + " (prompt " + count + ")");
		return AcpSchema.PromptResponse.endTurn();
	}

	@CloseSession
	AcpSchema.CloseSessionResponse close(AcpSchema.CloseSessionRequest request) {
		prompts.remove(request.sessionId());
		return new AcpSchema.CloseSessionResponse();
	}

	private static String text(AcpSchema.PromptRequest request) {
		StringBuilder text = new StringBuilder();
		for (AcpSchema.ContentBlock block : request.prompt()) {
			if (block instanceof AcpSchema.TextContent content) {
				text.append(content.text());
			}
		}
		return text.toString();
	}

}

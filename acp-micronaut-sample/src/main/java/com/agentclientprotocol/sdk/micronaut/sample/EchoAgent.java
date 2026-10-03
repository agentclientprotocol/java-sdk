/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.sample;

import java.util.UUID;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.NewSession;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

/**
 * Answers every prompt with two message chunks, the greeter's prefix and the prompt's text.
 * A Micronaut singleton like any other bean: its dependencies are injected, and Micronaut
 * finds it from its compile-time bean definition. One instance serves every session and, over
 * HTTP, every connection, so it keeps no per-session state in fields. {@code initialize} is
 * the SDK's default.
 */
@Singleton
@AcpAgent(name = "micronaut-echo-agent", version = "1.0.0")
public class EchoAgent {

	private final Greeter greeter;

	/**
	 * Creates the agent.
	 * @param greeter the injected greeter
	 */
	public EchoAgent(Greeter greeter) {
		this.greeter = greeter;
	}

	/**
	 * Opens a session; a {@code Publisher}, as Micronaut code often returns.
	 * @param request the request
	 * @return the new session's id
	 */
	@NewSession
	public Publisher<AcpSchema.NewSessionResponse> newSession(AcpSchema.NewSessionRequest request) {
		return Mono.fromSupplier(() -> new AcpSchema.NewSessionResponse(UUID.randomUUID().toString(), null, null));
	}

	/**
	 * Echoes the prompt.
	 * @param request the prompt
	 * @param context the turn's context, for sending updates
	 * @return end of turn
	 */
	@Prompt
	public AcpSchema.PromptResponse prompt(AcpSchema.PromptRequest request, SyncPromptContext context) {
		context.sendMessage(greeter.prefix());
		context.sendMessage(request.text());
		return AcpSchema.PromptResponse.endTurn();
	}

}

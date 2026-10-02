/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.time.Duration;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import reactor.core.publisher.Mono;

/**
 * A stdio agent run as a child process by {@link StdioAgentEndOfInputProcessTest}, written
 * as the README documents: start, await termination, exit. A prompt answers after a delay, so
 * that the client's input has ended by then; the prompt "ask" first asks the client for
 * permission, which it can no longer give, and reports how that ended.
 */
public final class EndOfInputAgent {

	static final Duration PROMPT_DELAY = Duration.ofMillis(300);

	private EndOfInputAgent() {
	}

	public static void main(String[] args) {
		AcpAsyncAgent agent = AcpAgent.async(new StdioAcpAgentTransport())
			.initializeHandler(request -> Mono.just(InitializeResponse.ok()))
			.newSessionHandler(request -> Mono.just(new NewSessionResponse("s-1", null)))
			.promptHandler((request, context) -> {
				Mono<String> text = "ask".equals(request.text())
						? context.askPermission("run the tests")
							.map(granted -> "permission " + granted)
							.onErrorResume(e -> Mono.just("permission failed: " + e.getMessage()))
						: Mono.just("hello");
				return Mono.delay(PROMPT_DELAY)
					.then(text)
					.flatMap(context::sendMessage)
					.then(Mono.just(PromptResponse.endTurn()));
			})
			.build();
		agent.start().then(agent.awaitTermination()).block();
		System.exit(0);
	}

}

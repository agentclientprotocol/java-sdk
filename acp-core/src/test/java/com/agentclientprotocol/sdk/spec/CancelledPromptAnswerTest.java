/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import com.agentclientprotocol.sdk.spec.AcpAgentSessionPromptLockTest.CapturingAgentTransport;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Once {@code session/cancel} arrived for a prompt, the session answers that prompt with stop
 * reason {@code cancelled}, whatever its handler then returns or fails with.
 *
 * <p>
 * ACP spec 7628b153 (agentclientprotocol/agent-client-protocol), docs/protocol/v1/prompt-turn.mdx:354,
 * "the Agent MUST respond to the original session/prompt request with the cancelled stop
 * reason", and :361, "Agents MUST catch these errors and return the semantically meaningful
 * cancelled stop reason".
 * </p>
 */
class CancelledPromptAnswerTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final AcpSchema.PromptResponse CANCELLED = new AcpSchema.PromptResponse(
			AcpSchema.StopReason.CANCELLED);

	// ACP spec 7628b153: prompt-turn.mdx:354, "MUST respond ... with the cancelled stop reason"
	@Test
	void aHandlerThatAnswersEndTurnAfterSessionCancelIsAnsweredCancelledKeepingMeta() {
		Map<String, Object> meta = Map.of("trace", "t-1");
		Sinks.Empty<Void> release = Sinks.empty();
		CapturingAgentTransport transport = new CapturingAgentTransport();
		new AcpAgentSession(TIMEOUT, transport, Map.of(AcpSchema.METHOD_SESSION_PROMPT,
				params -> release.asMono().then(Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.END_TURN, meta)))),
				Map.of());

		List<AcpSchema.JSONRPCMessage> answers = answers(transport, prompt("1"));
		sessionCancel(transport);
		release.tryEmitEmpty();

		AcpSchema.JSONRPCResponse response = single(answers);
		assertThat(response.error()).isNull();
		assertThat(response.result())
			.isEqualTo(new AcpSchema.PromptResponse(AcpSchema.StopReason.CANCELLED, meta));
	}

	// ACP spec 7628b153: prompt-turn.mdx:354, "MUST respond ... with the cancelled stop reason"
	@Test
	void anyOtherStopReasonAfterSessionCancelIsAnsweredCancelled() {
		Sinks.Empty<Void> release = Sinks.empty();
		CapturingAgentTransport transport = new CapturingAgentTransport();
		new AcpAgentSession(TIMEOUT, transport, Map.of(AcpSchema.METHOD_SESSION_PROMPT,
				params -> release.asMono().then(Mono.just(new AcpSchema.PromptResponse(AcpSchema.StopReason.MAX_TOKENS)))),
				Map.of());

		List<AcpSchema.JSONRPCMessage> answers = answers(transport, prompt("1"));
		sessionCancel(transport);
		release.tryEmitEmpty();

		assertThat(single(answers).result()).isEqualTo(CANCELLED);
	}

	// ACP spec 7628b153: prompt-turn.mdx:354; without a cancel the handler's stop reason stands
	@Test
	void withoutSessionCancelTheHandlersStopReasonIsSentUnchanged() {
		CapturingAgentTransport transport = new CapturingAgentTransport();
		new AcpAgentSession(TIMEOUT, transport,
				Map.of(AcpSchema.METHOD_SESSION_PROMPT, params -> Mono.just(AcpSchema.PromptResponse.endTurn())),
				Map.of());

		assertThat(single(answers(transport, prompt("1"))).result()).isEqualTo(AcpSchema.PromptResponse.endTurn());
	}

	// ACP spec 7628b153: prompt-turn.mdx:361, "Agents MUST catch these errors and return the
	// semantically meaningful cancelled stop reason"
	@Test
	void aHandlerThatFailsWithAnyExceptionAfterSessionCancelIsAnsweredCancelled() {
		Sinks.Empty<Void> release = Sinks.empty();
		CapturingAgentTransport transport = new CapturingAgentTransport();
		new AcpAgentSession(TIMEOUT, transport, Map.of(AcpSchema.METHOD_SESSION_PROMPT,
				params -> release.asMono().then(Mono.error(new IllegalStateException("stream closed")))), Map.of());

		List<AcpSchema.JSONRPCMessage> answers = answers(transport, prompt("1"));
		sessionCancel(transport);
		release.tryEmitEmpty();

		AcpSchema.JSONRPCResponse response = single(answers);
		assertThat(response.error()).isNull();
		assertThat(response.result()).isEqualTo(CANCELLED);
	}

	// ACP spec 7628b153: prompt-turn.mdx:361; a failure before any cancel keeps today's -32603
	@Test
	void aHandlerThatFailsWithoutSessionCancelIsStillAnInternalError() {
		CapturingAgentTransport transport = new CapturingAgentTransport();
		new AcpAgentSession(TIMEOUT, transport, Map.of(AcpSchema.METHOD_SESSION_PROMPT,
				params -> Mono.error(new IllegalStateException("stream closed"))), Map.of());

		AcpSchema.JSONRPCResponse response = single(answers(transport, prompt("1")));
		assertThat(response.error().code()).isEqualTo(AcpErrorCodes.INTERNAL_ERROR);
	}

	private static void sessionCancel(CapturingAgentTransport transport) {
		transport.deliver(new AcpSchema.JSONRPCNotification(AcpSchema.JSONRPC_VERSION,
				AcpSchema.METHOD_SESSION_CANCEL, Map.of("sessionId", "s1")))
			.block(TIMEOUT);
	}

	private static AcpSchema.JSONRPCRequest prompt(String id) {
		return new AcpSchema.JSONRPCRequest(AcpSchema.JSONRPC_VERSION, id, AcpSchema.METHOD_SESSION_PROMPT,
				new AcpSchema.PromptRequest("s1", List.of(new AcpSchema.TextContent("prompt " + id))));
	}

	private static List<AcpSchema.JSONRPCMessage> answers(CapturingAgentTransport transport,
			AcpSchema.JSONRPCMessage message) {
		List<AcpSchema.JSONRPCMessage> answers = new CopyOnWriteArrayList<>();
		transport.deliver(message).subscribe(answers::add);
		return answers;
	}

	private static AcpSchema.JSONRPCResponse single(List<AcpSchema.JSONRPCMessage> answers) {
		long deadline = System.nanoTime() + TIMEOUT.toNanos();
		while (answers.isEmpty()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("no answer within " + TIMEOUT);
			}
			Thread.onSpinWait();
		}
		assertThat(answers).hasSize(1);
		assertThat(answers.get(0)).isInstanceOf(AcpSchema.JSONRPCResponse.class);
		return (AcpSchema.JSONRPCResponse) answers.get(0);
	}

}

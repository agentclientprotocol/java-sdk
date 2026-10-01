/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spec;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import com.agentclientprotocol.sdk.error.AcpErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

/**
 * Enforces {@link PromptTimeouts} on a prompt: the handler's answer is the prompt's answer,
 * unless a deadline passes first, in which case the handler's subscription is cancelled and
 * the session answers. {@link PromptAnswer} decides which, so exactly one answer is given.
 *
 * <p>
 * The forced answer leaves through the same pipeline as the handler's would have: the turn
 * ends just before it is published, and it is queued on the transport behind every message
 * the handler had already sent, its {@code session/update}s included. A handler that keeps
 * running after its subscription is cancelled (a blocking sync handler does) and sends more
 * updates sends them after the answer, against the protocol; that is the handler's to stop.
 * </p>
 */
final class PromptDeadlines {

	private static final Logger logger = LoggerFactory.getLogger(PromptDeadlines.class);

	private final PromptTimeouts timeouts;

	/** Emits once after the given delay, off the timer's thread. */
	private final Function<Duration, Mono<?>> timer;

	PromptDeadlines(PromptTimeouts timeouts, Function<Duration, Mono<?>> timer) {
		this.timeouts = timeouts;
		this.timer = timer;
	}

	/**
	 * The prompt's response: {@code handled}'s, or the one a deadline forces.
	 * @param turn the prompt's turn, through which a cancel starts the grace period
	 * @param request the prompt request
	 * @param handled the handler's response
	 */
	Mono<AcpSchema.JSONRPCResponse> answer(ActivePrompts.Turn turn, AcpSchema.JSONRPCRequest request,
			Mono<AcpSchema.JSONRPCResponse> handled) {
		if (!this.timeouts.hasCancelGracePeriod() && !this.timeouts.hasMaxPromptDuration()) {
			return handled;
		}
		return Mono.create(sink -> {
			PromptAnswer answer = turn.answer();
			// Disposed once the response is out or the prompt is cancelled downstream: the
			// timers, and the handler's subscription when a deadline answered.
			Disposable.Composite resources = Disposables.composite();
			sink.onDispose(resources);
			if (this.timeouts.hasCancelGracePeriod()) {
				startGracePeriodOnCancel(turn, request, sink, resources);
			}
			if (this.timeouts.hasMaxPromptDuration()) {
				resources.add(after(this.timeouts.maxPromptDuration(), () -> maxDurationExpired(answer, request, sink)));
			}
			resources.add(handled.contextWrite(sink.contextView())
				.subscribe(response -> handlerAnswered(answer, () -> sink.success(response)),
						error -> handlerAnswered(answer, () -> sink.error(error))));
		});
	}

	private static void handlerAnswered(PromptAnswer answer, Runnable publish) {
		if (answer.handlerAnswered()) {
			publish.run();
		}
	}

	/** Starts the grace period when a cancel arrives, or at once if one already has; once. */
	private void startGracePeriodOnCancel(ActivePrompts.Turn turn, AcpSchema.JSONRPCRequest request,
			MonoSink<AcpSchema.JSONRPCResponse> sink, Disposable.Composite resources) {
		AtomicBoolean started = new AtomicBoolean();
		turn.onCancelRequested(() -> {
			if (started.compareAndSet(false, true)) {
				resources.add(after(this.timeouts.cancelGracePeriod(), () -> graceExpired(turn.answer(), request, sink)));
			}
		});
	}

	private void graceExpired(PromptAnswer answer, AcpSchema.JSONRPCRequest request,
			MonoSink<AcpSchema.JSONRPCResponse> sink) {
		if (answer.graceExpired()) {
			logger.warn("Prompt {} not answered {} after session/cancel; answering cancelled", request.id(),
					this.timeouts.cancelGracePeriod());
			sink.success(cancelled(request));
		}
	}

	private void maxDurationExpired(PromptAnswer answer, AcpSchema.JSONRPCRequest request,
			MonoSink<AcpSchema.JSONRPCResponse> sink) {
		PromptAnswer.Forced forced = answer.maxDurationExpired();
		if (forced == null) {
			return;
		}
		logger.warn("Prompt {} ran past maxPromptDuration {}; answering {}", request.id(),
				this.timeouts.maxPromptDuration(), forced);
		sink.success(forced == PromptAnswer.Forced.CANCELLED ? cancelled(request) : expired(request));
	}

	private Disposable after(Duration delay, Runnable action) {
		return this.timer.apply(delay).subscribe(tick -> action.run());
	}

	private static AcpSchema.JSONRPCResponse cancelled(AcpSchema.JSONRPCRequest request) {
		return InboundMessages.result(request, new AcpSchema.PromptResponse(AcpSchema.StopReason.CANCELLED));
	}

	private AcpSchema.JSONRPCResponse expired(AcpSchema.JSONRPCRequest request) {
		Duration max = this.timeouts.maxPromptDuration();
		return InboundMessages.error(request, AcpErrorCodes.REQUEST_CANCELLED,
				"Prompt exceeded maxPromptDuration of " + max, Map.of("maxPromptDuration", max.toString()));
	}

}

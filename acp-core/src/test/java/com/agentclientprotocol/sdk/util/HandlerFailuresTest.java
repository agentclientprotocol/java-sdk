/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.util;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How a handler's {@link Error} is turned into something Reactor signals, and how a
 * {@link VirtualMachineError} is still reported.
 */
class HandlerFailuresTest {

	@Test
	void anExceptionIsSignalledAsItIs() {
		IllegalStateException exception = new IllegalStateException("boom");
		assertThat(HandlerFailures.contain(exception)).isSameAs(exception);
	}

	@Test
	void anErrorIsCarriedByAHandlerErrorNamingOnlyItsType() {
		NoSuchMethodError error = new NoSuchMethodError("secret");
		Throwable contained = HandlerFailures.contain(error);
		assertThat(contained).isInstanceOf(HandlerFailures.HandlerError.class)
			.hasCause(error)
			.hasMessage(NoSuchMethodError.class.getName());
	}

	@Test
	void aSynchronousErrorFromAMonoHandlerBecomesItsErrorSignal() {
		StepVerifier.create(HandlerFailures.<String>invoke(() -> {
			throw new NoSuchMethodError("secret");
		})).expectErrorSatisfies(error -> assertThat(error).isInstanceOf(HandlerFailures.HandlerError.class)
			.hasCauseInstanceOf(NoSuchMethodError.class)).verify();
	}

	@Test
	void aBlockingCallPassesExceptionsAndWrapsErrors() throws Exception {
		assertThat(HandlerFailures.guard(() -> "ok").call()).isEqualTo("ok");
		assertThatThrownBy(() -> HandlerFailures.guard(() -> {
			throw new IOException("checked");
		}).call()).isInstanceOf(IOException.class);
		assertThatThrownBy(() -> HandlerFailures.guard(() -> {
			throw new AssertionError("secret");
		}).call()).isInstanceOf(HandlerFailures.HandlerError.class).hasCauseInstanceOf(AssertionError.class);
		assertThatThrownBy(() -> HandlerFailures.guard((Runnable) () -> {
			throw new LinkageError("secret");
		}).run()).isInstanceOf(HandlerFailures.HandlerError.class).hasCauseInstanceOf(LinkageError.class);
		Mono<String> wrapped = Mono.fromCallable(HandlerFailures.guard(() -> {
			throw new NoClassDefFoundError("secret");
		}));
		StepVerifier.create(wrapped).expectError(HandlerFailures.HandlerError.class).verify();
	}

	/** Answered like any error, and also handed to the thread's uncaught-exception handler. */
	@Test
	void aVirtualMachineErrorIsAlsoReportedToTheThreadsUncaughtExceptionHandler() throws Exception {
		List<Throwable> reported = new CopyOnWriteArrayList<>();
		AtomicReference<Throwable> contained = new AtomicReference<>();
		OutOfMemoryError outOfMemory = new OutOfMemoryError("test");
		Thread thread = new Thread(() -> contained.set(HandlerFailures.contain(outOfMemory)));
		thread.setUncaughtExceptionHandler((t, e) -> reported.add(e));
		thread.start();
		thread.join();

		assertThat(reported).containsExactly(outOfMemory);
		assertThat(contained.get()).isInstanceOf(HandlerFailures.HandlerError.class).hasCause(outOfMemory);
	}

	@Test
	void anErrorThatIsNotFatalIsNotReported() throws Exception {
		List<Throwable> reported = new CopyOnWriteArrayList<>();
		Thread thread = new Thread(() -> HandlerFailures.contain(new AssertionError()));
		thread.setUncaughtExceptionHandler((t, e) -> reported.add(e));
		thread.start();
		thread.join();

		assertThat(reported).isEmpty();
	}

}

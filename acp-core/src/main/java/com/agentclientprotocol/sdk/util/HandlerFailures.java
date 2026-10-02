/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.util;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

import reactor.core.publisher.Mono;

/**
 * Keeps a {@link Throwable} that is not an {@link Exception}, thrown by an application's
 * request or notification handler, from escaping the SDK.
 *
 * <p>
 * Reactor rethrows what it calls JVM-fatal errors ({@link VirtualMachineError} and
 * {@link LinkageError}, which includes the {@link NoSuchMethodError} of code compiled
 * against another SDK version) instead of signalling them, so a handler that throws one
 * left its request unanswered and the peer waiting out its timeout, and could unwind the
 * thread that delivered the request. Every handler invocation is guarded here: an
 * {@link Error} becomes a {@link HandlerError}, an ordinary exception that Reactor signals,
 * which the session answers with {@code -32603} (Internal error) and logs at ERROR.
 * </p>
 *
 * <p>
 * A {@link VirtualMachineError} ({@link OutOfMemoryError}, {@link StackOverflowError},
 * {@link InternalError}) is not hidden: it is answered like any other error, as far as the
 * JVM still can, and also handed to the uncaught-exception handler of the thread that ran
 * the handler, which is where it would have gone had it escaped. JVM options that act when
 * the error is thrown, such as {@code -XX:+ExitOnOutOfMemoryError}, are unaffected.
 * </p>
 *
 * <p>
 * What the SDK cannot reach: an asynchronous handler's {@code Mono} that throws a JVM-fatal
 * error from inside one of its own operators after the handler returned. Reactor rethrows
 * that on the thread that emitted, outside the handler call.
 * </p>
 */
public final class HandlerFailures {

	private HandlerFailures() {
	}

	/**
	 * An {@link Error} a handler threw, carried as an exception so it is signalled like one.
	 * Its message is the error's type only: the error's own message can carry details the
	 * peer must not see, and the cause keeps it for the log.
	 */
	public static final class HandlerError extends RuntimeException {

		private static final long serialVersionUID = 1L;

		HandlerError(Throwable error) {
			super(error.getClass().getName(), error);
		}

	}

	/**
	 * Calls a handler that returns a {@code Mono}: what it throws, an {@link Error} included,
	 * becomes the returned {@code Mono}'s error instead of escaping.
	 * @param <T> the result type
	 * @param handler the handler call
	 * @return the handler's {@code Mono}, or one failing with what it threw
	 */
	public static <T> Mono<T> invoke(Supplier<? extends Mono<T>> handler) {
		return Mono.defer(() -> {
			try {
				return handler.get();
			}
			catch (Throwable failure) {
				return Mono.error(contain(failure));
			}
		});
	}

	/**
	 * A blocking handler call that throws an {@link Error} as a {@link HandlerError}.
	 * @param <T> the result type
	 * @param handler the handler call
	 * @return the guarded call
	 */
	public static <T> Callable<T> guard(Callable<T> handler) {
		return () -> {
			try {
				return handler.call();
			}
			catch (Exception e) {
				throw e;
			}
			catch (Throwable failure) {
				throw (HandlerError) contain(failure);
			}
		};
	}

	/**
	 * A blocking handler call that throws an {@link Error} as a {@link HandlerError}.
	 * @param handler the handler call
	 * @return the guarded call
	 */
	public static Runnable guard(Runnable handler) {
		return () -> {
			try {
				handler.run();
			}
			catch (RuntimeException e) {
				throw e;
			}
			catch (Throwable failure) {
				throw (HandlerError) contain(failure);
			}
		};
	}

	/**
	 * A handler's failure as something Reactor signals: an exception as it is, any other
	 * throwable as a {@link HandlerError}. A {@link VirtualMachineError} is also handed to
	 * the current thread's uncaught-exception handler.
	 * @param failure what the handler threw or signalled
	 * @return the failure to signal
	 */
	public static Throwable contain(Throwable failure) {
		if (failure instanceof Exception) {
			return failure;
		}
		if (failure instanceof VirtualMachineError) {
			Thread thread = Thread.currentThread();
			try {
				thread.getUncaughtExceptionHandler().uncaughtException(thread, failure);
			}
			catch (RuntimeException ignored) {
				// The handler's own failure must not stop the request being answered.
			}
		}
		return new HandlerError(failure);
	}

}

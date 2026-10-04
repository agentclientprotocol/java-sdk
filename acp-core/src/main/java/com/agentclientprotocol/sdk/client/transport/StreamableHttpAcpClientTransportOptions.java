/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.util.concurrent.Executor;

import com.agentclientprotocol.sdk.util.Assert;
import org.jspecify.annotations.Nullable;

/**
 * The resource limits and threads of a {@link StreamableHttpAcpClientTransport}: how many SSE
 * streams it may hold open, and which threads its HTTP work runs on. Pass one to the
 * transport's constructor when a default does not fit, most often to raise
 * {@link #maxSseStreams()} for a client that keeps many ACP sessions open, or to run the
 * transport on the application's own threads. Start from {@link #builder()}, or use
 * {@link #defaults()}:
 *
 * <pre>{@code
 * var options = StreamableHttpAcpClientTransportOptions.builder().maxSseStreams(256).build();
 * // The transport's work on the application's executor:
 * var shared = StreamableHttpAcpClientTransportOptions.builder().executor(appExecutor).build();
 * }</pre>
 *
 * <p>Every limit is fixed, so an agent that opens many sessions or answers slowly cannot make
 * the client's SSE streams, threads or queues grow without bound: work beyond a limit fails at
 * once instead of waiting.
 *
 * <p>Where the work runs: with an {@link #executor()}, on that executor, which the transport
 * never shuts down. Without one, on JDK 21 and later, on a virtual thread per task
 * ({@code acp-streamable-http}), which the transport stops when it closes. In both cases
 * {@link #httpWorkerThreads()}, {@link #httpSignalThreads()} and {@link #httpQueueCapacity()}
 * do not apply, and {@link #maxSseStreams()} still bounds the streams. Without an executor on
 * JDK 17, or with {@link #virtualThreads()} false, the transport creates three bounded pools of
 * daemon platform threads, sized by those limits and shut down when it closes: the default HTTP
 * client's
 * ({@code acp-streamable-http-client}), the one that hands HTTP results to the transport
 * ({@code acp-streamable-http-signal}) and the SSE readers ({@code acp-streamable-http-sse}).
 *
 * @param maxSseStreams the most SSE streams open at once, each read on a thread of its own:
 * one for the connection plus one per ACP session in use; a stream beyond it fails with an
 * {@link com.agentclientprotocol.sdk.error.AcpConnectionException}; default 64
 * @param httpWorkerThreads the threads of the default HTTP client on JDK 17; they do not apply
 * to an HTTP client passed to the transport, with an executor, or on virtual threads; default 8
 * @param httpSignalThreads the threads that hand the results of HTTP calls to the transport
 * on JDK 17, so that the HTTP client's own threads are never held by the transport's work;
 * they do not apply with an executor or on virtual threads; default 4
 * @param httpQueueCapacity the tasks that each of those two thread pools may queue before it
 * refuses more, which fails the HTTP call; default 256
 * @param executor the application's executor that the transport's HTTP work runs on, or null
 * (the default) for virtual threads of the transport's own on JDK 21 and later, and bounded
 * pools of its own before. Each open SSE stream holds one of its threads
 * for as long as the stream is open, and its tasks block, so it must allow blocking and not cap
 * its threads below {@code maxSseStreams} plus a few: a virtual-thread executor or a
 * framework's worker pool. The application owns it; the transport does not shut it down. It
 * also becomes the executor of the default HTTP client, not of one passed to the transport
 * @param virtualThreads whether, without an executor, the transport's work runs on virtual
 * threads where the JDK has them (21 and later); false keeps it on bounded pools of platform
 * threads on every JDK, for an application that has not opted into virtual threads; default
 * true
 */
public record StreamableHttpAcpClientTransportOptions(int maxSseStreams, int httpWorkerThreads,
		int httpSignalThreads, int httpQueueCapacity, @Nullable Executor executor, boolean virtualThreads) {

	private static final int DEFAULT_MAX_SSE_STREAMS = 64;

	private static final int DEFAULT_HTTP_WORKER_THREADS = 8;

	private static final int DEFAULT_HTTP_SIGNAL_THREADS = 4;

	private static final int DEFAULT_HTTP_QUEUE_CAPACITY = 256;

	/**
	 * Creates options with these limits; prefer {@link #builder()}, which starts from the
	 * defaults.
	 * @param maxSseStreams the most SSE streams open at once
	 * @param httpWorkerThreads the threads of the default HTTP client
	 * @param httpSignalThreads the threads that hand results of HTTP calls to the transport
	 * @param httpQueueCapacity the tasks each thread pool may queue
	 * @param executor the executor the transport's HTTP work runs on, or null for its own
	 * @param virtualThreads whether its own work runs on virtual threads where the JDK has them
	 * @throws IllegalArgumentException if a limit is not positive
	 */
	public StreamableHttpAcpClientTransportOptions {
		Assert.isTrue(maxSseStreams > 0, "maxSseStreams must be positive");
		Assert.isTrue(httpWorkerThreads > 0, "httpWorkerThreads must be positive");
		Assert.isTrue(httpSignalThreads > 0, "httpSignalThreads must be positive");
		Assert.isTrue(httpQueueCapacity > 0, "httpQueueCapacity must be positive");
	}

	/**
	 * Creates options with these limits and no executor of the application's.
	 * @param maxSseStreams the most SSE streams open at once
	 * @param httpWorkerThreads the threads of the default HTTP client
	 * @param httpSignalThreads the threads that hand results of HTTP calls to the transport
	 * @param httpQueueCapacity the tasks each thread pool may queue
	 * @throws IllegalArgumentException if a value is not positive
	 */
	public StreamableHttpAcpClientTransportOptions(int maxSseStreams, int httpWorkerThreads, int httpSignalThreads,
			int httpQueueCapacity) {
		this(maxSseStreams, httpWorkerThreads, httpSignalThreads, httpQueueCapacity, null, true);
	}

	/**
	 * Returns the default limits: 64 SSE streams, 8 worker threads, 4 signal threads and a
	 * queue capacity of 256, and no executor of the application's.
	 * @return the default options
	 */
	public static StreamableHttpAcpClientTransportOptions defaults() {
		return builder().build();
	}

	/**
	 * Returns a builder that starts from the default limits.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builds {@link StreamableHttpAcpClientTransportOptions}, starting from the defaults; set only
	 * the limits that should differ. Get one from
	 * {@link StreamableHttpAcpClientTransportOptions#builder()}.
	 */
	public static final class Builder {

		private int maxSseStreams = DEFAULT_MAX_SSE_STREAMS;

		private int httpWorkerThreads = DEFAULT_HTTP_WORKER_THREADS;

		private int httpSignalThreads = DEFAULT_HTTP_SIGNAL_THREADS;

		private int httpQueueCapacity = DEFAULT_HTTP_QUEUE_CAPACITY;

		private @Nullable Executor executor;

		private boolean virtualThreads = true;

		private Builder() {
		}

		/**
		 * Sets the most SSE streams open at once; default 64.
		 * @param maxSseStreams the stream limit; positive
		 * @return this builder
		 */
		public Builder maxSseStreams(int maxSseStreams) {
			this.maxSseStreams = maxSseStreams;
			return this;
		}

		/**
		 * Sets the threads of the default HTTP client on JDK 17 without an executor; default 8.
		 * @param httpWorkerThreads the thread count; positive
		 * @return this builder
		 */
		public Builder httpWorkerThreads(int httpWorkerThreads) {
			this.httpWorkerThreads = httpWorkerThreads;
			return this;
		}

		/**
		 * Sets the threads that hand the results of HTTP calls to the transport, on JDK 17
		 * without an executor; default 4.
		 * @param httpSignalThreads the thread count; positive
		 * @return this builder
		 */
		public Builder httpSignalThreads(int httpSignalThreads) {
			this.httpSignalThreads = httpSignalThreads;
			return this;
		}

		/**
		 * Sets the tasks each of the transport's HTTP thread pools may queue; default 256.
		 * @param httpQueueCapacity the queue capacity; positive
		 * @return this builder
		 */
		public Builder httpQueueCapacity(int httpQueueCapacity) {
			this.httpQueueCapacity = httpQueueCapacity;
			return this;
		}

		/**
		 * Sets the application's executor that the transport's HTTP work runs on, in place of
		 * the pools it would create; the transport does not shut it down. See
		 * {@link StreamableHttpAcpClientTransportOptions#executor()} for what it must allow.
		 * @param executor the executor, such as a virtual-thread executor
		 * @return this builder
		 */
		public Builder executor(Executor executor) {
			Assert.notNull(executor, "executor must not be null");
			this.executor = executor;
			return this;
		}

		/**
		 * Sets whether, without an executor, the transport's work runs on virtual threads
		 * where the JDK has them; default true. False keeps the bounded pools of platform
		 * threads on every JDK.
		 * @param virtualThreads false for platform threads
		 * @return this builder
		 */
		public Builder virtualThreads(boolean virtualThreads) {
			this.virtualThreads = virtualThreads;
			return this;
		}

		/**
		 * Returns the options.
		 * @return the options
		 * @throws IllegalArgumentException if a value is not positive
		 */
		public StreamableHttpAcpClientTransportOptions build() {
			return new StreamableHttpAcpClientTransportOptions(maxSseStreams, httpWorkerThreads, httpSignalThreads,
					httpQueueCapacity, executor, virtualThreads);
		}

	}

}

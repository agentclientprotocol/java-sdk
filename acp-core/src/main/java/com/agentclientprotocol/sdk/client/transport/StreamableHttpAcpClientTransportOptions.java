/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import com.agentclientprotocol.sdk.util.Assert;

/**
 * The resource limits of a {@link StreamableHttpAcpClientTransport}: how many SSE streams it
 * may hold open, and how many threads and queued tasks its HTTP work may use. Pass one to the
 * transport's constructor when a default does not fit, most often to raise
 * {@link #maxSseStreams()} for a client that keeps many ACP sessions open. Start from
 * {@link #builder()}, or use {@link #defaults()}:
 *
 * <pre>{@code
 * var options = StreamableHttpAcpClientTransportOptions.builder().maxSseStreams(256).build();
 * }</pre>
 *
 * <p>Every limit is fixed, so an agent that opens many sessions or answers slowly cannot make
 * the client's threads or queues grow without bound: work beyond a limit fails at once
 * instead of waiting.
 *
 * @param maxSseStreams the most SSE streams open at once, each read on a thread of its own:
 * one for the connection plus one per ACP session in use; a stream beyond it fails with an
 * {@link com.agentclientprotocol.sdk.error.AcpConnectionException}; default 64
 * @param httpWorkerThreads the threads of the default HTTP client; they do not apply to an
 * HTTP client passed to the transport; default 8
 * @param httpSignalThreads the threads that hand the results of HTTP calls to the transport,
 * so that the HTTP client's own threads are never held by the transport's work; default 4
 * @param httpQueueCapacity the tasks that each of those two thread pools may queue before it
 * refuses more, which fails the HTTP call; default 256
 */
public record StreamableHttpAcpClientTransportOptions(int maxSseStreams, int httpWorkerThreads,
		int httpSignalThreads, int httpQueueCapacity) {

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
	 * @throws IllegalArgumentException if a value is not positive
	 */
	public StreamableHttpAcpClientTransportOptions {
		Assert.isTrue(maxSseStreams > 0, "maxSseStreams must be positive");
		Assert.isTrue(httpWorkerThreads > 0, "httpWorkerThreads must be positive");
		Assert.isTrue(httpSignalThreads > 0, "httpSignalThreads must be positive");
		Assert.isTrue(httpQueueCapacity > 0, "httpQueueCapacity must be positive");
	}

	/**
	 * Returns the default limits: 64 SSE streams, 8 worker threads, 4 signal threads and a
	 * queue capacity of 256.
	 * @return the default options
	 */
	public static StreamableHttpAcpClientTransportOptions defaults() {
		return new StreamableHttpAcpClientTransportOptions(DEFAULT_MAX_SSE_STREAMS, DEFAULT_HTTP_WORKER_THREADS,
				DEFAULT_HTTP_SIGNAL_THREADS, DEFAULT_HTTP_QUEUE_CAPACITY);
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
		 * Sets the threads of the default HTTP client; default 8.
		 * @param httpWorkerThreads the thread count; positive
		 * @return this builder
		 */
		public Builder httpWorkerThreads(int httpWorkerThreads) {
			this.httpWorkerThreads = httpWorkerThreads;
			return this;
		}

		/**
		 * Sets the threads that hand the results of HTTP calls to the transport; default 4.
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
		 * Returns the options.
		 * @return the options
		 * @throws IllegalArgumentException if a value is not positive
		 */
		public StreamableHttpAcpClientTransportOptions build() {
			return new StreamableHttpAcpClientTransportOptions(maxSseStreams, httpWorkerThreads, httpSignalThreads,
					httpQueueCapacity);
		}

	}

}

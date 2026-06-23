/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import com.agentclientprotocol.sdk.util.Assert;

/**
 * Resource limits for {@link StreamableHttpAcpClientTransport}.
 *
 * <p>SSE readers are long-lived tasks, so {@link #maxSseStreams()} limits active
 * connection and session streams rather than queueing them indefinitely.</p>
 */
public record StreamableHttpAcpClientTransportOptions(int maxSseStreams, int httpWorkerThreads,
		int httpSignalThreads, int httpQueueCapacity) {

	private static final int DEFAULT_MAX_SSE_STREAMS = 64;

	private static final int DEFAULT_HTTP_WORKER_THREADS = 8;

	private static final int DEFAULT_HTTP_SIGNAL_THREADS = 4;

	private static final int DEFAULT_HTTP_QUEUE_CAPACITY = 256;

	public StreamableHttpAcpClientTransportOptions {
		Assert.isTrue(maxSseStreams > 0, "maxSseStreams must be positive");
		Assert.isTrue(httpWorkerThreads > 0, "httpWorkerThreads must be positive");
		Assert.isTrue(httpSignalThreads > 0, "httpSignalThreads must be positive");
		Assert.isTrue(httpQueueCapacity > 0, "httpQueueCapacity must be positive");
	}

	/**
	 * Returns the default resource limits.
	 * @return default transport options
	 */
	public static StreamableHttpAcpClientTransportOptions defaults() {
		return new StreamableHttpAcpClientTransportOptions(DEFAULT_MAX_SSE_STREAMS, DEFAULT_HTTP_WORKER_THREADS,
				DEFAULT_HTTP_SIGNAL_THREADS, DEFAULT_HTTP_QUEUE_CAPACITY);
	}

	/**
	 * Creates a builder initialized with the default resource limits.
	 * @return options builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for {@link StreamableHttpAcpClientTransportOptions}.
	 */
	public static final class Builder {

		private int maxSseStreams = DEFAULT_MAX_SSE_STREAMS;

		private int httpWorkerThreads = DEFAULT_HTTP_WORKER_THREADS;

		private int httpSignalThreads = DEFAULT_HTTP_SIGNAL_THREADS;

		private int httpQueueCapacity = DEFAULT_HTTP_QUEUE_CAPACITY;

		private Builder() {
		}

		public Builder maxSseStreams(int maxSseStreams) {
			this.maxSseStreams = maxSseStreams;
			return this;
		}

		public Builder httpWorkerThreads(int httpWorkerThreads) {
			this.httpWorkerThreads = httpWorkerThreads;
			return this;
		}

		public Builder httpSignalThreads(int httpSignalThreads) {
			this.httpSignalThreads = httpSignalThreads;
			return this;
		}

		public Builder httpQueueCapacity(int httpQueueCapacity) {
			this.httpQueueCapacity = httpQueueCapacity;
			return this;
		}

		public StreamableHttpAcpClientTransportOptions build() {
			return new StreamableHttpAcpClientTransportOptions(maxSseStreams, httpWorkerThreads, httpSignalThreads,
					httpQueueCapacity);
		}

	}

}

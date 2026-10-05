/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.agentclientprotocol.sdk.client.transport.StreamableHttpRequests.HttpClientBundle;
import com.agentclientprotocol.sdk.util.VirtualThreads;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The threads the Streamable HTTP client transport runs on without an executor of the
 * application's: a virtual thread per task where the JDK has them, unless the options say
 * platform threads; else bounded pools of daemon platform threads. Opting out of virtual threads
 * only changes anything on JDK 21 and later, so that is where these tests matter most.
 */
class StreamableHttpThreadsTest {

	private static final URI ENDPOINT = URI.create("http://127.0.0.1:1/acp");

	@Test
	void byDefaultTheWorkRunsOnVirtualThreadsWhereTheJdkHasThem() throws Exception {
		HttpClientBundle bundle = HttpClientBundle.createDefault(StreamableHttpAcpClientTransportOptions.defaults());
		try {
			if (!VirtualThreads.isSupported()) {
				assertThat(bundle.workExecutor()).isNull();
				assertThat(bundle.ownedExecutor()).isInstanceOf(ThreadPoolExecutor.class);
				return;
			}
			assertThat(bundle.workExecutor()).isSameAs(bundle.ownedExecutor());
			Thread thread = runOn(bundle.ownedExecutor());
			assertThat(VirtualThreads.isVirtual(thread)).isTrue();
			assertThat(thread.getName()).isEqualTo("acp-streamable-http");
		}
		finally {
			bundle.ownedExecutor().shutdownNow();
		}
	}

	@Test
	void withVirtualThreadsOffTheWorkRunsOnBoundedPoolsOfPlatformThreadsOnEveryJdk() throws Exception {
		StreamableHttpAcpClientTransportOptions options = StreamableHttpAcpClientTransportOptions.builder()
			.virtualThreads(false)
			.httpWorkerThreads(2)
			.httpSignalThreads(1)
			.build();
		HttpClientBundle bundle = HttpClientBundle.createDefault(options);
		assertThat(bundle.workExecutor()).isNull();
		assertThat(bundle.ownedExecutor()).isInstanceOf(ThreadPoolExecutor.class);
		assertThat(((ThreadPoolExecutor) bundle.ownedExecutor()).getMaximumPoolSize()).isEqualTo(2);
		assertThat(bundle.httpClient().executor()).containsSame(bundle.ownedExecutor());
		Thread thread = runOn(bundle.ownedExecutor());
		assertThat(VirtualThreads.isVirtual(thread)).isFalse();
		assertThat(thread.isDaemon()).isTrue();
		assertThat(thread.getName()).isEqualTo("acp-streamable-http-client");

		StreamableHttpRequests requests = new StreamableHttpRequests(ENDPOINT, bundle, options);
		requests.shutdown();
		assertThat(bundle.ownedExecutor().isShutdown()).isTrue();
	}

	@Test
	void anApplicationsClientWithVirtualThreadsOffGetsNoExecutorOfTheTransports() {
		StreamableHttpAcpClientTransportOptions options = StreamableHttpAcpClientTransportOptions.builder()
			.virtualThreads(false)
			.build();
		HttpClient client = HttpClient.newHttpClient();
		HttpClientBundle bundle = HttpClientBundle.of(client, options);
		assertThat(bundle.httpClient()).isSameAs(client);
		assertThat(bundle.ownedExecutor()).isNull();
		assertThat(bundle.workExecutor()).isNull();
		new StreamableHttpRequests(ENDPOINT, bundle, options).shutdown();
	}

	private static Thread runOn(ExecutorService executor) throws Exception {
		return executor.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
	}

}

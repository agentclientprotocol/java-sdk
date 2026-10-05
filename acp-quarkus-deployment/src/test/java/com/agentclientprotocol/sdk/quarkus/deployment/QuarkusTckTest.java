/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.deployment;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.transport.StreamableHttpAcpAgentTransportOptions;
import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.annotation.Prompt;
import com.agentclientprotocol.sdk.quarkus.runtime.AcpHttpAgentHost;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.http.AcpHttpTransportTck;
import io.quarkus.test.QuarkusUnitTest;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The Quarkus extension's host (the SDK endpoint on a Vert.x route of the Quarkus router) under
 * the shared transport TCK. The application runs the TCK's standard options, with a two second
 * WebSocket idle timeout and initialize timeout for the timeout cases, and its
 * {@code @AcpAgent} behaves as the TCK's agent; the shutdown case drains the running
 * application's endpoint, as the application's own shutdown does.
 *
 * <p>The extension maps no property to the two timeouts, so the application produces its
 * options as a bean ({@link TckOptions}), which the extension's host takes in place of the
 * configured limits.
 */
class QuarkusTckTest extends AcpHttpTransportTck {

	@RegisterExtension
	static final QuarkusUnitTest app = new QuarkusUnitTest()
		.withApplicationRoot(jar -> jar.addClasses(TckAgent.class, TckOptions.class))
		.overrideConfigKey("quarkus.acp.agent.transport.type", "http");

	@TestHTTPResource("/acp")
	URI endpoint;

	@Inject
	AcpHttpAgentHost host;

	@Override
	protected Host startHost(HostConfig config) {
		URI uri = URI.create(endpoint.toString().replace("localhost", "127.0.0.1"));
		return new Host() {

			@Override
			public URI endpoint() {
				return uri;
			}

			@Override
			public void stop() {
				host.drain();
			}

		};
	}

	/** The TCK's standard options with short WebSocket timeouts, as a bean. */
	public static class TckOptions {

		@Produces
		@Singleton
		StreamableHttpAcpAgentTransportOptions options() {
			return standardOptions().webSocketIdleTimeout(Duration.ofSeconds(2))
				.initializeTimeout(Duration.ofSeconds(2))
				.build();
		}

	}

	/** The TCK's agent, as an annotated bean. */
	@AcpAgent
	public static class TckAgent {

		@Prompt
		AcpSchema.PromptResponse prompt(AcpSchema.PromptRequest request, SyncPromptContext context) throws Exception {
			String text = ((AcpSchema.TextContent) request.prompt().get(0)).text();
			if (text.startsWith("updates:")) {
				int count = Integer.parseInt(text.substring("updates:".length()));
				ExecutorService senders = Executors.newFixedThreadPool(4);
				try {
					List<Future<?>> sent = new ArrayList<>();
					for (int i = 0; i < count; i++) {
						String update = "u" + i;
						sent.add(senders.submit(() -> context.sendMessage(update)));
					}
					for (Future<?> future : sent) {
						future.get();
					}
				}
				finally {
					senders.shutdownNow();
				}
				return AcpSchema.PromptResponse.endTurn();
			}
			if (text.equals("hold")) {
				Thread.sleep(60_000);
				return AcpSchema.PromptResponse.endTurn();
			}
			context.sendMessage("echo " + text);
			return AcpSchema.PromptResponse.endTurn();
		}

	}

}

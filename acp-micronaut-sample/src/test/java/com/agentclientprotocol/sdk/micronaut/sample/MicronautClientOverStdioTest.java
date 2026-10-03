/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.sample;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.micronaut.client.AcpClientCustomizer;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.micronaut.context.annotation.Requires;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Micronaut client application, configured by {@code acp.client.transport.stdio.*}, that
 * starts the sample agent as a process, talks to it, and closes it: the agent exits 0 by
 * itself on the end of its input, without needing a SIGTERM.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MicronautClientOverStdioTest implements TestPropertyProvider {

	@Inject
	AcpSyncClient client;

	@Inject
	AcpAsyncClient asyncClient;

	@Inject
	Chunks chunks;

	@Override
	public Map<String, String> getProperties() {
		List<String> command = AgentProcess.command();
		Map<String, String> properties = new java.util.HashMap<>();
		// This application is a client: the sample's own agent bean stays off here.
		properties.put("acp.agent.enabled", "false");
		properties.put("test.chunks", "true");
		properties.put("acp.client.transport.stdio.command", command.get(0));
		properties.put("acp.client.transport.stdio.args", String.join(",", command.subList(1, command.size())));
		// Micronaut reads SAMPLE_PREFIX as sample.prefix: the variable reaches the agent
		properties.put("acp.client.transport.stdio.env.SAMPLE_PREFIX", "env: ");
		return properties;
	}

	@Test
	void talksToTheAgentProcessAndClosesItWithoutASignal() {
		ListAppender<ILoggingEvent> transportLog = new ListAppender<>();
		Logger logger = (Logger) LoggerFactory.getLogger(StdioAcpClientTransport.class);
		Level previous = logger.getLevel();
		logger.setLevel(Level.DEBUG);
		transportLog.start();
		logger.addAppender(transportLog);
		try {
			assertThat(client.initialize().protocolVersion()).isEqualTo(1);
			String sessionId = client.newSession(new AcpSchema.NewSessionRequest("/tmp", List.of())).sessionId();
			AcpSchema.PromptResponse response = client
				.prompt(new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent("from Micronaut"))));
			assertThat(response.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
			eventually(() -> assertThat(String.join("", chunks.texts)).isEqualTo("env: from Micronaut"));

			asyncClient.closeGracefully().block(Duration.ofSeconds(30));

			assertThat(transportLog.list).extracting(ILoggingEvent::getFormattedMessage)
				.contains("ACP agent process stopped (exit code 0)")
				.noneMatch(message -> message.contains("sending TERM"));
		}
		finally {
			logger.detachAppender(transportLog);
			logger.setLevel(previous);
		}
	}

	private static void eventually(Runnable assertion) {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (true) {
			try {
				assertion.run();
				return;
			}
			catch (AssertionError ex) {
				if (System.nanoTime() > deadline) {
					throw ex;
				}
				Thread.onSpinWait();
			}
		}
	}

	/** Records the text chunks, through the customizer contract. */
	@Singleton
	@Requires(property = "test.chunks", value = "true")
	static class Chunks implements AcpClientCustomizer {

		final List<String> texts = new CopyOnWriteArrayList<>();

		@Override
		public void customize(com.agentclientprotocol.sdk.client.AcpClient.AsyncSpec spec) {
			spec.sessionUpdateConsumer(notification -> {
				if (notification.update() instanceof AcpSchema.AgentMessageChunk chunk
						&& chunk.content() instanceof AcpSchema.TextContent text) {
					texts.add(text.text());
				}
				return Mono.empty();
			});
		}

	}

}

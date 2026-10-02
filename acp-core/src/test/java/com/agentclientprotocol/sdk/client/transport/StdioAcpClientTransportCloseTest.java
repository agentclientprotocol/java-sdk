/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agentclientprotocol.sdk.agent.transport.EndOfInputAgent;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How {@link StdioAcpClientTransport#closeGracefully()} ends the agent process: it closes the
 * agent's standard input and lets an agent that exits by itself at the end of its input do so,
 * exit code 0, before it falls back to SIGTERM; and it reports the end once, however often the
 * transport is closed.
 */
class StdioAcpClientTransportCloseTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(30);

	private final Logger logger = (Logger) LoggerFactory.getLogger(StdioAcpClientTransport.class);

	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

	@BeforeEach
	void captureLogs() {
		this.logs.start();
		this.logger.addAppender(this.logs);
	}

	@AfterEach
	void releaseLogs() {
		this.logger.detachAppender(this.logs);
		this.logs.stop();
	}

	private List<String> stops() {
		return this.logs.list.stream()
			.map(ILoggingEvent::getFormattedMessage)
			.filter(message -> message.startsWith("ACP agent process stopped")
					|| message.startsWith("Process terminated"))
			.toList();
	}

	private static AgentParameters javaAgent(Class<?> main) {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		return AgentParameters.builder(java)
			.arg("-Dlogback.configurationFile=logback-stdio-agent.xml")
			.arg("-cp")
			.arg(System.getProperty("java.class.path"))
			.arg(main.getName())
			.build();
	}

	/**
	 * An SDK stdio agent, written as the README shows, answers what it received once its input
	 * ends and exits 0: the graceful close lets it, rather than SIGTERM it (exit 143).
	 */
	@Test
	void aJavaStdioAgentExitsByItselfAfterAGracefulClose() {
		StdioAcpClientTransport transport = new StdioAcpClientTransport(javaAgent(EndOfInputAgent.class));
		transport.setStdErrorHandler(line -> {
		});
		AcpSyncClient client = AcpClient.sync(transport).requestTimeout(TIMEOUT).build();
		client.initialize();
		client.newSession(new AcpSchema.NewSessionRequest("/tmp", List.of()));
		client.prompt(new AcpSchema.PromptRequest("s-1", List.of(new AcpSchema.TextContent("hi"))));

		assertThat(client.closeGracefully()).isTrue();

		assertThat(stops()).containsExactly("ACP agent process stopped (exit code 0)");
	}

	/** An agent that goes on running after its input ends is sent SIGTERM. */
	@Test
	@DisabledOnOs(OS.WINDOWS)
	void anAgentThatIgnoresTheEndOfItsInputIsTerminated() {
		StdioAcpClientTransport transport = new StdioAcpClientTransport(javaAgent(StubbornAgent.class));
		transport.connect(message -> message.then(Mono.empty())).block(TIMEOUT);

		transport.closeGracefully().block(TIMEOUT);

		assertThat(stops()).containsExactly("ACP agent process stopped (exit code 143)");
	}

	/** {@code closeGracefully()} then {@code close()}, as try-with-resources does: one report. */
	@Test
	void closingTwiceReportsTheStopOnce() {
		StdioAcpClientTransport transport = new StdioAcpClientTransport(javaAgent(EndOfInputAgent.class));
		transport.setStdErrorHandler(line -> {
		});
		AcpSyncClient client = AcpClient.sync(transport).requestTimeout(TIMEOUT).build();
		client.initialize();

		client.closeGracefully();
		client.close();
		transport.closeGracefully().block(TIMEOUT);

		assertThat(stops()).hasSize(1);
	}

	/** Never reads its input, and never exits by itself. */
	public static final class StubbornAgent {

		private StubbornAgent() {
		}

		public static void main(String[] args) throws InterruptedException {
			Thread.sleep(Long.MAX_VALUE);
		}

	}

}

/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.it;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The packaged application, launched as an editor launches an agent: the SDK's stdio
 * client starts {@code java -jar quarkus-run.jar}, initializes, opens a session and
 * prompts; when the client closes the agent's standard input the application exits by
 * itself, with code 0 and no ERROR log.
 */
class StdioAgentIT {

	private static final Duration TIMEOUT = Duration.ofSeconds(30);

	@TempDir
	Path tempDir;

	@Test
	void packagedAgentAnswersOverStdioAndExitsCleanlyWhenInputEnds() throws Exception {
		Path exitCode = tempDir.resolve("exit-code");
		Path log = tempDir.resolve("stderr.log");
		List<String> messages = new CopyOnWriteArrayList<>();

		StdioAcpClientTransport transport = new StdioAcpClientTransport(launch(exitCode, log));
		AcpSyncClient client = AcpClient.sync(transport)
			.requestTimeout(TIMEOUT)
			.sessionUpdateHandler(notification -> {
				if (notification.update() instanceof AcpSchema.AgentMessageChunk chunk
						&& chunk.content() instanceof AcpSchema.TextContent text) {
					messages.add(text.text());
				}
			})
			.build();

		AcpSchema.InitializeResponse initialized = client.initialize();
		assertThat(initialized.protocolVersion()).isEqualTo(AcpSchema.LATEST_PROTOCOL_VERSION);
		GreeterAgentAdvertisement.assertAdvertised(initialized);

		String sessionId = client.newSession(new AcpSchema.NewSessionRequest(tempDir.toString(), List.of()))
			.sessionId();
		AcpSchema.PromptResponse first = client
			.prompt(new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent("stdio"))));
		AcpSchema.PromptResponse second = client
			.prompt(new AcpSchema.PromptRequest(sessionId, List.of(new AcpSchema.TextContent("again"))));

		assertThat(first.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
		assertThat(second.stopReason()).isEqualTo(AcpSchema.StopReason.END_TURN);
		assertThat(messages).containsExactly("Hello from Quarkus, stdio! (prompt 1)",
				"Hello from Quarkus, again! (prompt 2)");

		long closing = System.nanoTime();
		client.closeGracefully();
		Duration closed = Duration.ofNanos(System.nanoTime() - closing);

		// The agent exits on the end of its input, before the client would send TERM.
		assertThat(closed).isLessThan(Duration.ofMillis(StdioAcpClientTransport.END_OF_INPUT_WAIT_MILLIS));
		assertThat(Files.readString(exitCode).strip()).isEqualTo("0");
		List<String> stderr = Files.readAllLines(log);
		assertThat(stderr).noneMatch(line -> line.contains("ERROR") || line.contains("WARN"));
		assertThat(stderr).anyMatch(line -> line.contains("ACP agent transport ended; stopping the application"));
		assertThat(stderr).anyMatch(line -> line.contains("stopped in"));
	}

	/**
	 * {@code java -jar} the packaged application, its standard error and exit code in files
	 * (the client stops reading standard error once it closes).
	 */
	private static AgentParameters launch(Path exitCode, Path log) {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		String app = System.getProperty("acp.quarkus.app");
		assertThat(app).as("acp.quarkus.app (set by failsafe)").isNotNull();
		return AgentParameters.builder("sh")
			.arg("-c")
			.arg("'" + java + "' -jar '" + app + "' 2> '" + log + "'; echo $? > '" + exitCode + "'")
			.build();
	}

}

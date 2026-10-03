/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.sample;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sample run as an agent process: standard output carries only the protocol, the process
 * exits 0 by itself when its input ends, and SIGTERM stops it gracefully over stdio and over
 * HTTP.
 */
class AgentProcessTest {

	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
			+ "\"params\":{\"protocolVersion\":1,\"clientCapabilities\":{}}}\n";

	@Test
	void stdioExitsZeroWhenItsInputEndsAndWritesOnlyProtocolToStdout() throws Exception {
		Process agent = AgentProcess.start();
		List<String> stderr = AgentProcess.collectStderr(agent);
		BufferedReader stdout = new BufferedReader(new InputStreamReader(agent.getInputStream(), StandardCharsets.UTF_8));
		OutputStream stdin = agent.getOutputStream();

		stdin.write(INITIALIZE.getBytes(StandardCharsets.UTF_8));
		stdin.flush();
		assertThat(stdout.readLine()).startsWith("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":1");

		long closed = System.nanoTime();
		stdin.close();
		assertThat(agent.waitFor(10, TimeUnit.SECONDS)).as("exits after the end of its input").isTrue();
		assertThat(Duration.ofNanos(System.nanoTime() - closed)).isLessThan(Duration.ofSeconds(5));
		assertThat(agent.exitValue()).isZero();
		assertThat(stdout.readLine()).as("nothing else on stdout").isNull();
		assertThat(AgentProcess.drained(stderr)).anyMatch(line -> line.contains("ACP agent transport ended; closing the application context"))
			.noneMatch(line -> line.contains(" ERROR ") || line.contains(" WARN "));
	}

	@Test
	void stdioStopsGracefullyOnSigterm() throws Exception {
		Process agent = AgentProcess.start();
		List<String> stderr = AgentProcess.collectStderr(agent);
		BufferedReader stdout = new BufferedReader(new InputStreamReader(agent.getInputStream(), StandardCharsets.UTF_8));
		agent.getOutputStream().write(INITIALIZE.getBytes(StandardCharsets.UTF_8));
		agent.getOutputStream().flush();
		assertThat(stdout.readLine()).contains("\"protocolVersion\":1");

		// SIGTERM. Not Process.destroy(), which also closes this side's pipes, losing the
		// agent's last log lines.
		agent.toHandle().destroy();

		assertThat(agent.waitFor(15, TimeUnit.SECONDS)).isTrue();
		assertThat(AgentProcess.drained(stderr)).anyMatch(line -> line.contains("Stopping the ACP agent"))
			.noneMatch(line -> line.contains(" ERROR "));
	}

	@Test
	void httpServesUntilSigtermThenStopsGracefully() throws Exception {
		Process agent = AgentProcess.start("--acp.agent.transport.type=http", "--acp.agent.transport.http.port=0",
				"--acp.agent.transport.http.shutdown-timeout=2s");
		List<String> stderr = AgentProcess.collectStderr(agent);
		int port = awaitPort(stderr);

		try (Socket socket = new Socket("localhost", port)) {
			assertThat(socket.isConnected()).isTrue();
		}
		// SIGTERM. Not Process.destroy(), which also closes this side's pipes, losing the
		// agent's last log lines.
		agent.toHandle().destroy();

		assertThat(agent.waitFor(15, TimeUnit.SECONDS)).isTrue();
		assertThat(AgentProcess.drained(stderr)).anyMatch(line -> line.contains("Stopping the ACP agent")).noneMatch(line -> line.contains(" ERROR "));
	}

	static int awaitPort(List<String> stderr) throws InterruptedException {
		Pattern listening = Pattern.compile("ACP agent listening on port (\\d+)");
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (System.nanoTime() < deadline) {
			for (String line : stderr) {
				Matcher matcher = listening.matcher(line);
				if (matcher.find()) {
					return Integer.parseInt(matcher.group(1));
				}
			}
			Thread.sleep(50);
		}
		throw new AssertionError("the agent did not report its port: " + stderr);
	}

}

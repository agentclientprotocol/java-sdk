/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real agent process, started as the README shows, whose client writes its requests and
 * closes the agent's standard input at once, then reads standard output to its end: every
 * request is answered, and the process exits. Before, the process exited as soon as its input
 * ended, with the replies still to come unwritten.
 */
class StdioAgentEndOfInputProcessTest {

	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1}}";

	private static final String NEW_SESSION = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/new\",\"params\":{\"cwd\":\"/tmp\",\"mcpServers\":[]}}";

	private static String prompt(String text) {
		return "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"session/prompt\",\"params\":{\"sessionId\":\"s-1\","
				+ "\"prompt\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}}";
	}

	@Test
	void everyRequestWrittenBeforeClosingStandardInputIsAnswered() throws Exception {
		List<String> output = runAgent(INITIALIZE, NEW_SESSION, prompt("hi"));

		assertThat(output).filteredOn(line -> line.contains("\"id\":1")).singleElement().asString().contains("\"result\"");
		assertThat(output).filteredOn(line -> line.contains("\"id\":2")).singleElement().asString().contains("\"s-1\"");
		assertThat(output).filteredOn(line -> line.contains("session/update")).singleElement().asString().contains("hello");
		assertThat(output).last().asString().contains("\"id\":3").contains("end_turn");
	}

	/**
	 * A prompt that asks the client for permission after the client closed its input: the
	 * request fails at once instead of waiting out the request timeout, and the prompt is
	 * still answered.
	 */
	@Test
	void aRequestToTheClientAfterItsInputEndedFailsAndThePromptIsAnswered() throws Exception {
		List<String> output = runAgent(INITIALIZE, NEW_SESSION, prompt("ask"));

		// askPermission announces its tool call first (a tool_call update), then asks.
		assertThat(output).filteredOn(line -> line.contains("session/update") && line.contains("agent_message_chunk"))
			.singleElement()
			.asString()
			.contains("permission failed")
			.contains("closed its input");
		assertThat(output).last().asString().contains("\"id\":3").contains("end_turn");
	}

	private static List<String> runAgent(String... requests) throws IOException, InterruptedException {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		Process process = new ProcessBuilder(java, "-Dlogback.configurationFile=logback-stdio-agent.xml", "-cp",
				System.getProperty("java.class.path"), EndOfInputAgent.class.getName())
			.redirectError(ProcessBuilder.Redirect.DISCARD)
			.start();
		try {
			try (OutputStream stdin = process.getOutputStream()) {
				for (String request : requests) {
					stdin.write((request + "\n").getBytes(StandardCharsets.UTF_8));
				}
			}
			List<String> output = new ArrayList<>();
			try (BufferedReader stdout = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				for (String line = stdout.readLine(); line != null; line = stdout.readLine()) {
					output.add(line);
				}
			}
			assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("agent exited").isTrue();
			assertThat(process.exitValue()).isZero();
			return output;
		}
		finally {
			process.destroyForcibly();
		}
	}

}

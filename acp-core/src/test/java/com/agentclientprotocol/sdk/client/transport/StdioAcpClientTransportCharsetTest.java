/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The agent writes UTF-8 (ACP requires it), so the client must read the agent's stdout as
 * UTF-8 whatever the JVM's default charset. It decoded with the default charset, which on
 * Java 17 follows the platform (Cp1252 on Windows, US-ASCII under a POSIX locale), turning
 * non-ASCII text into mojibake. The client runs in a child JVM whose default charset is
 * ISO-8859-1, since a running JVM's default charset cannot be changed.
 */
class StdioAcpClientTransportCharsetTest {

	@Test
	void readsAgentOutputAsUtf8WhateverTheDefaultCharset() throws Exception {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		Process probe = new ProcessBuilder(java, "-Dfile.encoding=ISO-8859-1", "-cp",
				System.getProperty("java.class.path"), StdioCharsetProbe.class.getName())
			.redirectErrorStream(true)
			.start();
		String output = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(probe.waitFor(60, TimeUnit.SECONDS)).as(output).isTrue();

		List<String> results = output.lines()
			.filter(line -> line.startsWith(StdioCharsetProbe.PREFIX))
			.map(line -> line.substring(StdioCharsetProbe.PREFIX.length()))
			.toList();
		assumeThat(results).as("the child JVM honours file.encoding").contains("charset=ISO-8859-1");
		assertThat(results).as(output).contains("text=" + StdioCharsetProbe.escape(StdioCharsetProbe.TEXT));
	}

}

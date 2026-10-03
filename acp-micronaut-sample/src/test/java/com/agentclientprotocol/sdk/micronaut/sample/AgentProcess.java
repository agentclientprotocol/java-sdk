/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.sample;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** The sample application, started as its own JVM, the way an ACP client starts an agent. */
final class AgentProcess {

	private AgentProcess() {
	}

	/** The test JVM's classpath, which holds the application and its dependencies. */
	static String classpath() {
		return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
	}

	/** The command line that starts the application with the given arguments. */
	static List<String> command(String... args) {
		List<String> command = new ArrayList<>(List.of(java(), "-cp", classpath(), Application.class.getName()));
		command.addAll(List.of(args));
		return command;
	}

	static String java() {
		return System.getProperty("java.home") + "/bin/java";
	}

	static Process start(String... args) {
		try {
			return new ProcessBuilder(command(args)).start();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Collects a process's standard error lines on a daemon thread. The list is complete once
	 * {@link #drained} returns.
	 */
	static List<String> collectStderr(Process process) {
		List<String> lines = new CopyOnWriteArrayList<>();
		Thread reader = new Thread(() -> {
			try (BufferedReader in = new BufferedReader(
					new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = in.readLine()) != null) {
					lines.add(line);
				}
			}
			catch (IOException ex) {
				// the process ended
			}
		}, "agent-stderr");
		reader.setDaemon(true);
		reader.start();
		READERS.put(System.identityHashCode(lines), reader);
		return lines;
	}

	private static final java.util.Map<Integer, Thread> READERS = new java.util.concurrent.ConcurrentHashMap<>();

	/** Waits until the process's standard error has been read to its end. */
	static List<String> drained(List<String> lines) throws InterruptedException {
		Thread reader = READERS.remove(System.identityHashCode(lines));
		if (reader != null) {
			reader.join(10_000);
		}
		return lines;
	}

}

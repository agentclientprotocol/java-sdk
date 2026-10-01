/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import org.jspecify.annotations.Nullable;

/**
 * Result of executing a terminal command via the convenience API.
 *
 * <p>
 * This record wraps the output and exit code from a terminal command execution,
 * providing a clean interface for the common case of running a command and
 * checking its result.
 *
 * <p>
 * Example usage:
 * <pre>{@code
 * CommandResult result = context.execute("make", "build");
 * if (result.success()) {
 *     context.sendMessage("Build succeeded!");
 * } else {
 *     context.sendMessage("Build failed: " + result.output());
 * }
 * }</pre>
 *
 * @param output The combined stdout/stderr output from the command
 * @param exitCode The exit code (0 typically means success), or null if the process was
 * terminated by a signal
 * @param signal The signal that terminated the process, or null if it exited normally
 * @author Mark Pollack
 * @since 0.9.2
 * @see SyncPromptContext#execute(String...)
 * @see PromptContext#execute(String...)
 */
public record CommandResult(
		String output,
		@Nullable Integer exitCode,
		@Nullable String signal
) {

	/**
	 * Creates a CommandResult for a process that exited with the given code.
	 * @param output The command output
	 * @param exitCode The exit code
	 */
	public CommandResult(String output, int exitCode) {
		this(output, exitCode, null);
	}

	/**
	 * Returns true if the command completed successfully (exit code 0).
	 * @return true if the process exited with code 0
	 */
	public boolean success() {
		return exitCode != null && exitCode == 0;
	}

}

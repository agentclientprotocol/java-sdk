/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import org.jspecify.annotations.Nullable;

/**
 * What a command run by {@link SyncPromptContext#execute(Command)} or
 * {@link PromptContext#execute(Command)} left behind: its output and how it ended, as the client's
 * terminal reported them. Check {@link #success()} for exit code 0, and read {@link #output()} for
 * what it printed.
 *
 * <p>{@code execute} builds it from two of the client's answers: the exit code or signal from
 * {@code terminal/wait_for_exit}, then the output from {@code terminal/output}, read after the
 * command ended. A process ends with an exit code or with a signal, so one of the two is normally
 * {@code null}. The output is what the client kept: past the command's output limit the client
 * drops output from the start, and {@link #truncated()} says whether it did. Whether standard error
 * is part of the output is up to the client.
 *
 * <pre>{@code
 * CommandResult result = context.execute("make", "build");
 * if (result.success()) {
 *     context.sendMessage("Build succeeded");
 * } else {
 *     context.sendMessage("Build failed:\n" + result.output());
 * }
 * }</pre>
 *
 * @param output the terminal output the client returned
 * @param exitCode the exit code, or {@code null} if a signal ended the process
 * @param signal the signal that ended the process, or {@code null} if it exited
 * @param truncated whether the client cut the output to the command's output limit, so that
 * {@code output} lacks its beginning
 * @author Mark Pollack
 * @since 0.9.2
 * @see SyncPromptContext#execute(String...)
 * @see PromptContext#execute(String...)
 */
public record CommandResult(
		String output,
		@Nullable Integer exitCode,
		@Nullable String signal,
		boolean truncated
) {

	/**
	 * Creates a result whose output is complete (not truncated).
	 * @param output the terminal output
	 * @param exitCode the exit code, or {@code null} if a signal ended the process
	 * @param signal the signal that ended the process, or {@code null} if it exited
	 */
	public CommandResult(String output, @Nullable Integer exitCode, @Nullable String signal) {
		this(output, exitCode, signal, false);
	}

	/**
	 * Creates a result for a process that exited with the given code, with no signal and complete
	 * output.
	 * @param output the terminal output
	 * @param exitCode the exit code
	 */
	public CommandResult(String output, int exitCode) {
		this(output, exitCode, null, false);
	}

	/**
	 * Returns whether the process exited with code 0. A process ended by a signal did not succeed.
	 * @return {@code true} if the exit code is 0
	 */
	public boolean success() {
		return exitCode != null && exitCode == 0;
	}

}

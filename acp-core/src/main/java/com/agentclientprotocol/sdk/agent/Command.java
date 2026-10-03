/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * A command for a prompt handler to run in a terminal on the client, with the options
 * {@code execute(String...)} cannot express: a working directory, environment variables and an
 * output limit. Pass it to {@link SyncPromptContext#execute(Command)} or
 * {@link PromptContext#execute(Command)}, which run it and return a {@link CommandResult}.
 *
 * <p>Start with {@link #of(String...)} and add options with {@link #cwd(String)}, {@link #env(Map)}
 * and {@link #outputByteLimit(long)}; each returns a new command. The values go to the client as
 * they are, in a {@code terminal/create} request, so the client decides what they mean: ACP asks
 * for an absolute working directory, and a client that reaches the output limit drops output from
 * the start. The client must have advertised the {@code terminal} capability.
 *
 * <pre>{@code
 * CommandResult result = context.execute(Command.of("mvn", "-q", "test")
 *     .cwd("/workspace/project")
 *     .env(Map.of("CI", "true"))
 *     .outputByteLimit(100_000));
 * }</pre>
 *
 * @param executable the program to run
 * @param args the arguments after the executable; empty for none
 * @param cwd the working directory, an absolute path, or {@code null} for the client's default
 * @param env the environment variables to set; empty for none
 * @param outputByteLimit the most bytes of output the client keeps, or {@code null} for the
 * client's default
 * @author Mark Pollack
 * @since 0.9.2
 * @see SyncPromptContext#execute(Command)
 * @see PromptContext#execute(Command)
 */
public record Command(
		String executable,
		List<String> args,
		@Nullable String cwd,
		Map<String, String> env,
		@Nullable Long outputByteLimit
) {

	/**
	 * Creates a command from an executable and its arguments, with no working directory, no
	 * environment variables and no output limit. The arguments are a view of
	 * {@code commandAndArgs}, not a copy.
	 * @param commandAndArgs the executable, then its arguments
	 * @return the command
	 * @throws IllegalArgumentException if {@code commandAndArgs} is empty
	 */
	public static Command of(String... commandAndArgs) {
		if (commandAndArgs == null || commandAndArgs.length == 0) {
			throw new IllegalArgumentException("At least one argument (the command) is required");
		}
		return new Command(
				commandAndArgs[0],
				commandAndArgs.length > 1
						? Arrays.asList(commandAndArgs).subList(1, commandAndArgs.length)
						: List.of(),
				null, Map.of(), null);
	}

	/**
	 * Returns a copy of this command that runs in the given working directory.
	 * @param cwd the working directory, an absolute path
	 * @return the new command
	 */
	public Command cwd(String cwd) {
		return new Command(executable, args, cwd, env, outputByteLimit);
	}

	/**
	 * Returns a copy of this command with these environment variables, in place of any set before.
	 * @param env the variable names and values
	 * @return the new command
	 */
	public Command env(Map<String, String> env) {
		return new Command(executable, args, cwd, env, outputByteLimit);
	}

	/**
	 * Returns a copy of this command with an output limit: the most bytes of output the client
	 * keeps.
	 * @param limit the limit in bytes
	 * @return the new command
	 */
	public Command outputByteLimit(long limit) {
		return new Command(executable, args, cwd, env, limit);
	}

}

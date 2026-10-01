/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Builder for terminal command execution via the convenience API.
 *
 * <p>
 * This record allows configuring command execution with options like
 * working directory, environment variables, and output limits.
 *
 * <p>
 * Example usage:
 * <pre>{@code
 * // Simple command
 * CommandResult result = context.execute("echo", "hello");
 *
 * // Command with options
 * CommandResult result = context.execute(
 *     Command.of("make", "build")
 *         .cwd("/workspace")
 *         .env(Map.of("DEBUG", "true"))
 *         .outputLimit(10000));
 * }</pre>
 *
 * @param executable The command to execute
 * @param args The arguments to pass to the command
 * @param cwd The working directory (null for default)
 * @param env Environment variables to set (empty for none)
 * @param outputByteLimit Maximum bytes of output to capture (null for default)
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
	 * Creates a Command from command-line arguments.
	 * The first argument is the executable, remaining arguments are passed as args.
	 * @param commandAndArgs The command and its arguments
	 * @return A new Command instance
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
	 * Returns a new Command with the specified working directory.
	 * @param cwd The working directory
	 * @return A new Command with the working directory set
	 */
	public Command cwd(String cwd) {
		return new Command(executable, args, cwd, env, outputByteLimit);
	}

	/**
	 * Returns a new Command with the specified environment variables.
	 * @param env The environment variables
	 * @return A new Command with the environment variables set
	 */
	public Command env(Map<String, String> env) {
		return new Command(executable, args, cwd, env, outputByteLimit);
	}

	/**
	 * Returns a new Command with the specified output byte limit.
	 * @param limit The maximum bytes of output to capture
	 * @return A new Command with the output byte limit set
	 */
	public Command outputByteLimit(long limit) {
		return new Command(executable, args, cwd, env, limit);
	}

}

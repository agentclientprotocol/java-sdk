/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.agentclientprotocol.sdk.util.Assert;

/**
 * The command, arguments and environment variables that {@link StdioAcpClientTransport} starts
 * an agent process with. Build one with {@link #builder(String)} and pass it to the
 * transport's constructor:
 *
 * <pre>{@code
 * AgentParameters params = AgentParameters.builder("gemini")
 *     .arg("--experimental-acp")
 *     .addEnvVar("GEMINI_API_KEY", System.getenv("GEMINI_API_KEY"))
 *     .build();
 * }</pre>
 *
 * <p>By default the agent process inherits the client's whole environment, with {@link #getEnv()}
 * added, so the agent can read every secret in it. {@link #getEnv()} starts with a few variables
 * copied from the client's environment when the parameters are built (HOME, LOGNAME, PATH, SHELL,
 * TERM and USER; on Windows PATH, SYSTEMROOT, TEMP, USERPROFILE and a few more), and the
 * variables added on the builder replace them. To give the agent only those, call
 * {@link Builder#inheritEnvironment(boolean) inheritEnvironment(false)}: the process then starts
 * from an empty environment, and an agent that needs a key, for example an API key, must be given
 * it with {@link Builder#addEnvVar(String, String)}.
 *
 * <p>The parameters are not a snapshot: {@link #getArgs()} and {@link #getEnv()} return the
 * collections themselves, and an argument added with {@link Builder#arg(String)} after
 * {@link Builder#build()} also appears in parameters already built from that builder.
 *
 * @author Mark Pollack
 * @author Christian Tzolov (MCP Java SDK)
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
public class AgentParameters {

	// Environment variables to inherit by default
	private static final List<String> DEFAULT_INHERITED_ENV_VARS = System.getProperty("os.name")
		.toLowerCase(Locale.ROOT)
		.contains("win")
				? Arrays.asList("APPDATA", "HOMEDRIVE", "HOMEPATH", "LOCALAPPDATA", "PATH", "PROCESSOR_ARCHITECTURE",
						"SYSTEMDRIVE", "SYSTEMROOT", "TEMP", "USERNAME", "USERPROFILE")
				: Arrays.asList("HOME", "LOGNAME", "PATH", "SHELL", "TERM", "USER");

	@JsonProperty("command")
	private final String command;

	@JsonProperty("args")
	private final List<String> args;

	@JsonProperty("env")
	private final Map<String, String> env;

	@JsonProperty("inheritEnvironment")
	private final boolean inheritEnvironment;

	private AgentParameters(String command, List<String> args, Map<String, String> env, boolean inheritEnvironment) {
		Assert.notNull(command, "The command can not be null");
		Assert.notNull(args, "The args can not be null");

		this.command = command;
		this.args = args;
		this.env = new HashMap<>(getDefaultEnvironment());
		this.env.putAll(env);
		this.inheritEnvironment = inheritEnvironment;
	}

	/**
	 * Returns the program to start: a path, or a name the operating system looks up on the
	 * PATH.
	 * @return the command
	 */
	public String getCommand() {
		return this.command;
	}

	/**
	 * Returns the arguments passed to the command, in order.
	 * @return the arguments; the list itself, not a copy
	 */
	public List<String> getArgs() {
		return this.args;
	}

	/**
	 * Returns the environment variables set for the agent process: the defaults copied from
	 * the client's environment, replaced where the builder added a variable of the same name.
	 * The transport adds them to the environment the process inherits, or, without inheriting
	 * ({@link #isInheritEnvironment()}), gives the process only these.
	 * @return the variables, by name; the map itself, not a copy
	 */
	public Map<String, String> getEnv() {
		return this.env;
	}

	/**
	 * Returns whether the agent process inherits the client's whole environment, with
	 * {@link #getEnv()} added (the default), or gets only {@link #getEnv()}.
	 * @return {@code true} if the process inherits the client's environment
	 */
	public boolean isInheritEnvironment() {
		return this.inheritEnvironment;
	}

	/**
	 * Returns a builder for parameters that start {@code command}.
	 * @param command the program to start: a path, or a name looked up on the PATH
	 * @return a new builder
	 * @throws IllegalArgumentException if {@code command} is null
	 */
	public static Builder builder(String command) {
		return new Builder(command);
	}

	/**
	 * Builds {@link AgentParameters}. Get one from {@link AgentParameters#builder(String)}.
	 * Arguments keep the order they are added in; an environment variable added again
	 * replaces the earlier value.
	 */
	public static class Builder {

		private final String command;

		private List<String> args = new ArrayList<>();

		private final Map<String, String> env = new HashMap<>();

		private boolean inheritEnvironment = true;

		/**
		 * Creates a builder for parameters that start {@code command}; the same as
		 * {@link AgentParameters#builder(String)}.
		 * @param command the program to start
		 * @throws IllegalArgumentException if {@code command} is null
		 */
		public Builder(String command) {
			Assert.notNull(command, "The command can not be null");
			this.command = command;
		}

		/**
		 * Replaces the arguments with these.
		 * @param args the arguments, in order
		 * @return this builder
		 * @throws IllegalArgumentException if {@code args} is null
		 */
		public Builder args(String... args) {
			Assert.notNull(args, "The args can not be null");
			this.args = new ArrayList<>(Arrays.asList(args));
			return this;
		}

		/**
		 * Replaces the arguments with a copy of these.
		 * @param args the arguments, in order
		 * @return this builder
		 * @throws IllegalArgumentException if {@code args} is null
		 */
		public Builder args(List<String> args) {
			Assert.notNull(args, "The args can not be null");
			this.args = new ArrayList<>(args);
			return this;
		}

		/**
		 * Adds one argument after those already set.
		 * @param arg the argument
		 * @return this builder
		 * @throws IllegalArgumentException if {@code arg} is null
		 */
		public Builder arg(String arg) {
			Assert.notNull(arg, "The arg can not be null");
			this.args.add(arg);
			return this;
		}

		/**
		 * Adds these environment variables, replacing any of the same name added before. An
		 * empty map adds nothing.
		 * @param env the variables, by name
		 * @return this builder
		 */
		public Builder env(Map<String, String> env) {
			if (env != null && !env.isEmpty()) {
				this.env.putAll(env);
			}
			return this;
		}

		/**
		 * Adds one environment variable, replacing one of the same name added before.
		 * @param key the variable's name
		 * @param value the variable's value
		 * @return this builder
		 * @throws IllegalArgumentException if {@code key} or {@code value} is null
		 */
		public Builder addEnvVar(String key, String value) {
			Assert.notNull(key, "The key can not be null");
			Assert.notNull(value, "The value can not be null");
			this.env.put(key, value);
			return this;
		}

		/**
		 * Sets whether the agent process inherits the client's whole environment. Default:
		 * {@code true}, the process gets the client's environment with the variables of
		 * {@link AgentParameters#getEnv()} added, every secret in it included. With
		 * {@code false} the process gets only {@link AgentParameters#getEnv()}: the safe defaults
		 * (HOME, PATH, USER and the like) and the variables added on this builder.
		 * @param inheritEnvironment {@code false} to start the agent from the safe defaults only
		 * @return this builder
		 */
		public Builder inheritEnvironment(boolean inheritEnvironment) {
			this.inheritEnvironment = inheritEnvironment;
			return this;
		}

		/**
		 * Returns the parameters, with the default environment variables read from the
		 * client's environment now.
		 * @return the parameters
		 */
		public AgentParameters build() {
			return new AgentParameters(command, args, env, inheritEnvironment);
		}

	}

	/**
	 * Returns a default environment object including only environment variables deemed
	 * safe to inherit.
	 */
	private static Map<String, String> getDefaultEnvironment() {
		return System.getenv()
			.entrySet()
			.stream()
			.filter(entry -> DEFAULT_INHERITED_ENV_VARS.contains(entry.getKey()))
			.filter(entry -> entry.getValue() != null)
			.filter(entry -> !entry.getValue().startsWith("()"))
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

}

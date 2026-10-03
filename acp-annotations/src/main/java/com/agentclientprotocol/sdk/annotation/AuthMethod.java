/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One authentication method an agent advertises in its {@code initialize} response
 * ({@code authMethods}), declared in {@link AcpAgent#authMethods()}.
 *
 * <p>An {@link Type#AGENT} method is one the client passes to {@code authenticate}, so an
 * agent that declares one must have an {@link Authenticate} handler. A {@link Type#TERMINAL}
 * method is one the client runs itself, by starting the agent program again in an
 * interactive terminal with {@link #args()} and {@link #env()} added; the client never passes
 * it to {@code authenticate}, and it is advertised only to a client that announced
 * {@code clientCapabilities.auth.terminal}.
 *
 * <pre>{@code
 * @AcpAgent(name = "my-agent", version = "1.0", authMethods = {
 *     @AuthMethod(id = "api-key", name = "API key", description = "Reads MY_API_KEY"),
 *     @AuthMethod(id = "login", name = "Log in", type = AuthMethod.Type.TERMINAL, args = "--login") })
 * class MyAgent {
 *
 *     @Authenticate
 *     AuthenticateResponse authenticate(AuthenticateRequest request) { ... }
 *
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @since 0.80.0
 * @see AcpAgent#authMethods()
 * @see Authenticate
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuthMethod {

	/**
	 * The method id, which the client passes to {@code authenticate}.
	 * @return the id, unique among the agent's methods
	 */
	String id();

	/**
	 * The human-readable name a client shows.
	 * @return the name
	 */
	String name();

	/**
	 * An optional description; empty for none.
	 * @return the description
	 */
	String description() default "";

	/**
	 * Who runs the authentication.
	 * @return {@link Type#AGENT} by default
	 */
	Type type() default Type.AGENT;

	/**
	 * For a {@link Type#TERMINAL} method, the arguments the client adds to the agent program.
	 * @return the extra arguments
	 */
	String[] args() default {};

	/**
	 * For a {@link Type#TERMINAL} method, the environment variables the client sets for the
	 * agent program, each written {@code NAME=value}.
	 * @return the extra environment variables
	 */
	String[] env() default {};

	/**
	 * Who runs an authentication method.
	 */
	enum Type {

		/** The agent authenticates, in its {@link Authenticate} handler. */
		AGENT,

		/** The client runs the agent program in an interactive terminal. */
		TERMINAL

	}

}

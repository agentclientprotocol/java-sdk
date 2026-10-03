/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as an ACP agent that can handle client requests.
 *
 * <p>Classes annotated with {@code @AcpAgent} can define handler methods
 * using annotations like {@link Prompt}, {@link Initialize}, and {@link NewSession}.
 *
 * <p>A request handler returns its method's response (or a {@code Mono} of it); only a
 * {@link Prompt} handler may return {@code void}, which ends the turn. A request handler that
 * returns nothing, or {@code null}, is answered with an internal error ({@code -32603}),
 * because a request must get a result.
 *
 * <p><b>Connection parameters.</b> Besides the parameters its annotation lists, every handler
 * method, extension handlers included, can take any of these, resolved for the connection the
 * request or notification arrived on (with one handler bean serving many connections, each call
 * gets its own connection's):
 * <ul>
 *   <li>{@code NegotiatedCapabilities} - the capabilities the client offered in its
 *   {@code initialize} request, available from the {@code @Initialize} handler on, to gate
 *   options and calls on what the client supports</li>
 *   <li>{@code AcpSyncAgent} or {@code AcpAsyncAgent} - the connection's agent, to send session
 *   updates (such as a {@code ConfigOptionUpdate}) and requests to the client outside a prompt
 *   turn</li>
 * </ul>
 *
 * <p><b>Discovery.</b> Handler methods are found on the class and its superclasses, so handlers
 * can live on an abstract base class, and a subclass that a framework generates for a bean
 * (a Spring CGLIB, Quarkus ArC or Micronaut AOP proxy, which carries no annotations) is found
 * through the annotated class it extends and invoked on the proxy, so its interceptors run. An
 * annotated override replaces the method it overrides.
 *
 * <p><b>Advertising.</b> The agent's {@code initialize} response is derived from the class:
 * each handler annotation advertises the capability its method needs ({@link LoadSession} sets
 * {@code loadSession}, {@link ListSessions} sets {@code sessionCapabilities.list},
 * {@link Logout} sets {@code auth.logout}, and so on), {@link #name()} and {@link #version()}
 * become {@code agentInfo}, {@link #authMethods()} become {@code authMethods}, and the
 * attributes of {@link #mcpHttp()}, {@link #mcpSse()} and {@link Prompt} declare the MCP
 * transports and prompt content the agent accepts. An {@link Initialize} method is not needed
 * to advertise; one that is present adds to the derived response (see {@link Initialize}).
 *
 * <p>Example usage:
 * <pre>{@code
 * @AcpAgent(name = "support-agent", version = "1.0")
 * public class SupportAgent {
 *
 *     @LoadSession
 *     public LoadSessionResponse load(LoadSessionRequest req) {
 *         return new LoadSessionResponse(null);
 *     }
 *
 *     @Prompt
 *     public PromptResponse handlePrompt(PromptRequest req, SyncPromptContext context) {
 *         context.sendMessage("Hello!");
 *         return PromptResponse.endTurn();
 *     }
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @since 1.0.0
 * @see Prompt
 * @see Initialize
 * @see NewSession
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AcpAgent {

	/**
	 * The agent's name, sent to the client as {@code agentInfo.name} in the {@code initialize}
	 * response. Empty (the default) sends the class's simple name.
	 * @return the agent name
	 */
	String name() default "";

	/**
	 * The agent's version, sent as {@code agentInfo.version}. Empty (the default) sends the
	 * {@code Implementation-Version} of the class's jar manifest, or {@code "unknown"} when it
	 * has none.
	 * @return the agent version
	 */
	String version() default "";

	/**
	 * A human-readable title for the agent, sent as {@code agentInfo.title}. Empty (the
	 * default) sends none.
	 * @return the agent title
	 */
	String title() default "";

	/**
	 * The authentication methods the agent advertises in its {@code initialize} response
	 * ({@code authMethods}). An agent that declares an {@link AuthMethod.Type#AGENT} method
	 * must have an {@link Authenticate} handler. Terminal methods are advertised only to a
	 * client that announced {@code clientCapabilities.auth.terminal}.
	 * @return the authentication methods; none by default
	 */
	AuthMethod[] authMethods() default {};

	/**
	 * Whether the agent connects to MCP servers over HTTP, advertised as
	 * {@code agentCapabilities.mcpCapabilities.http}: a client then may pass HTTP MCP servers
	 * in {@code session/new}, {@code session/load} and {@code session/resume}.
	 * @return false by default
	 */
	boolean mcpHttp() default false;

	/**
	 * Whether the agent connects to MCP servers over SSE, advertised as
	 * {@code agentCapabilities.mcpCapabilities.sse}.
	 * @return false by default
	 */
	boolean mcpSse() default false;

}

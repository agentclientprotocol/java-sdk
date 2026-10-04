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
 * Marks a class as an ACP agent written with annotations: each of its handler methods answers
 * one protocol method, such as {@link Initialize}, {@link NewSession} or {@link Prompt}. Use it
 * when one method per request reads better than lambdas on the {@code AcpAgent.sync(...)} builder
 * of {@code acp-core}. {@code AcpAgentSupport}, in the {@code acp-agent-support} module, finds the
 * methods and serves them over a transport; the example below shows a whole agent.
 *
 * <p>This annotation and the builder entry point {@code com.agentclientprotocol.sdk.agent.AcpAgent}
 * have the same simple name. A file that uses both imports one and writes the other fully
 * qualified.
 *
 * <p><b>Finding the handler methods.</b> {@code AcpAgentSupport} looks for them once, when the
 * object is given to its builder, and rejects a class with this annotation neither on it nor on a
 * superclass with an {@code IllegalArgumentException}. It finds methods of any visibility declared
 * in the class and its superclasses, so handlers can live on an abstract base class, and a
 * subclass that a framework generates for a bean (a Spring CGLIB, Quarkus ArC or Micronaut AOP
 * proxy, which carries no annotations) is found through the annotated class it extends and
 * invoked on the proxy, so its interceptors run. An annotated override replaces the method it
 * overrides. Declare one method for each annotation, and one annotation on each method: two
 * methods for one annotation, or two handler annotations on one method, are rejected with an
 * {@code IllegalArgumentException} naming them. With no {@link Initialize} method the agent
 * answers {@code initialize} with the response derived from its annotations (see Capabilities
 * below); with no {@link NewSession} method it answers {@code session/new} with a random UUID as
 * the session id and no modes or config options, so an agent with a {@link SetSessionMode} or
 * {@link SetSessionConfigOption} method must also have a {@link NewSession} method that offers
 * them (building the agent fails otherwise). Any other request without a handler method is answered
 * "Method not found" ({@code -32601}).
 *
 * <p><b>Capabilities.</b> ACP lets a client call an optional method, such as {@code session/load}
 * or {@code logout}, only when the agent advertised it in its {@code initialize} response. The SDK
 * derives that response from the class, so no {@link Initialize} method is needed to advertise:
 * each handler annotation advertises the capability its method needs ({@link LoadSession} sets
 * {@code loadSession}, {@link ListSessions} sets {@code sessionCapabilities.list}, {@link Logout}
 * sets {@code auth.logout}, and so on; each annotation names its own), {@link #name()},
 * {@link #version()} and {@link #title()} become {@code agentInfo}, {@link #authMethods()} become
 * {@code authMethods}, and {@link #mcpHttp()}, {@link #mcpSse()} and the attributes of
 * {@link Prompt} declare the MCP transports and the prompt content the agent accepts, and
 * {@link #additionalDirectories()} declares {@code sessionCapabilities.additionalDirectories}. The protocol
 * version answered is the client's when the SDK speaks it, otherwise the latest it speaks. An
 * {@link Initialize} method, when present, adds to the derived response (see {@link Initialize}).
 *
 * <p><b>Parameters.</b> Each annotation lists the parameters its methods can take, all optional
 * and in any order. Besides those, every handler method, extension handlers included, can take
 * these, resolved for the connection the request or notification arrived on (with one handler bean
 * serving many connections, each call gets its own connection's):
 * <ul>
 *   <li>{@code NegotiatedCapabilities}: the capabilities the client offered in its
 *   {@code initialize} request, available from the {@link Initialize} method on, to check what
 *   the client supports before offering an option or calling it</li>
 *   <li>{@code AcpSyncAgent} or {@code AcpAsyncAgent}: the connection's agent, to send session
 *   updates (such as a {@code ConfigOptionUpdate}) and requests to the client outside a prompt
 *   turn</li>
 * </ul>
 * A parameter that a call cannot fill is rejected when the agent is built
 * ({@code build()} or {@code buildFactory()} throws an {@code IllegalStateException} naming the
 * class, the method and the fix): a type no argument resolver supplies, the request type of
 * another method, a {@link SessionId} parameter on a method without a session (extension methods
 * included), a prompt context outside a {@link Prompt} method, or a {@link ConfigId} or
 * {@link ConfigValue} parameter outside a {@link SetSessionConfigOption} method. A parameter a
 * custom argument resolver supplies is the application's to check.
 *
 * <p><b>Return values.</b> A request handler returns its method's response, or a {@code Mono}, a
 * {@code CompletionStage} or a single-value Reactive Streams {@code Publisher} of it, which the
 * runtime waits for on the handler's thread. A request must get a result, so a request handler declared
 * {@code void}, or declared to return another type than its response, is rejected when the agent
 * is built, and one that returns {@code null} or an empty {@code Mono} is answered with an
 * internal error ({@code -32603}). Of the request handlers, only a {@link Prompt} method may be
 * {@code void}. A notification handler ({@link Cancel}) returns {@code void}.
 *
 * <p><b>Errors.</b> To answer a request with an error, throw an {@code AcpProtocolException} (from
 * {@code acp-core}) with a code from {@code AcpErrorCodes}: the client receives its code, message
 * and data, so keep secrets out of what you put in it. Any other exception is answered with an
 * internal error ({@code -32603}) whose message is only "Internal error"; the exception itself is
 * logged at the agent. An exception from a notification handler is logged and dropped.
 *
 * <p><b>Threads and state.</b> One instance of the class serves every request: from every session,
 * and, with {@code AcpAgentSupport.Builder.buildFactory()}, from every connection. Its handler
 * methods run on the SDK's handler threads, so they may block, but they are called concurrently
 * and must be thread-safe. Keep per-session state in a concurrent map keyed by session id, and
 * remove it in a {@link CloseSession} or {@link DeleteSession} method.
 *
 * <p>Example usage:
 * <pre>{@code
 * @AcpAgent(name = "greeting-agent", version = "1.0")
 * public class GreetingAgent {
 *
 *     @Prompt
 *     public PromptResponse prompt(PromptRequest request, SyncPromptContext context) {
 *         context.sendMessage("Hello!");
 *         return PromptResponse.endTurn();
 *     }
 *
 *     public static void main(String[] args) {
 *         AcpAgentSupport.create(GreetingAgent.class)
 *             .transport(new StdioAcpAgentTransport())
 *             .build()
 *             .run();
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

	/**
	 * Whether the agent accepts additional workspace directories, advertised as
	 * {@code agentCapabilities.sessionCapabilities.additionalDirectories}: a client then may send
	 * {@code additionalDirectories} in {@code session/new}, {@code session/load},
	 * {@code session/resume} and {@code session/fork}, and the SDK's client refuses to send them
	 * to an agent that does not advertise it. Declare it only when the agent's handlers use the
	 * directories they receive: ACP forbids an agent to drop them silently. The agent needs a
	 * {@link NewSession} method to receive them; building one without it fails.
	 * @return false by default
	 */
	boolean additionalDirectories() default false;

}

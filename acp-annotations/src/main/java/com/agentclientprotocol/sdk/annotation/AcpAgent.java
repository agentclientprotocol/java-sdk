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
 * object is given to its builder, and rejects a class without this annotation with an
 * {@code IllegalArgumentException}. It finds methods of any visibility declared in the class
 * itself; methods inherited from a superclass are not found. Declare at most one method for each
 * annotation: with two, one of them is used and the other is ignored without an error. With no
 * {@link Initialize} method the agent answers {@code initialize} with default capabilities, which
 * advertise no optional method; with no {@link NewSession} method it answers {@code session/new}
 * with a random UUID as the session id. Any other request without a handler method is answered
 * "Method not found" ({@code -32601}).
 *
 * <p><b>Capabilities.</b> ACP lets a client call an optional method, such as {@code session/load}
 * or {@code logout}, only when the agent advertised it in its {@code initialize} response. The SDK
 * neither adds these capabilities nor checks them: a handler method is served whether or not its
 * capability was advertised. Return the capabilities your handlers need from your
 * {@link Initialize} method; each annotation names the one it needs.
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
 * A parameter that a call cannot fill, such as the request type of another method, or a
 * {@link SessionId} parameter on a method without a session, is not rejected up front: every call
 * of that method fails with an internal error ({@code -32603}).
 *
 * <p><b>Return values.</b> A request handler returns its method's response, or a {@code Mono} of
 * it, which the runtime waits for. A request must get a result, so a request handler that returns
 * {@code null}, an empty {@code Mono} or another method's response, or is declared {@code void}, is
 * answered with an internal error ({@code -32603}). Of the request handlers, only a {@link Prompt}
 * method may be {@code void}. A notification handler ({@link Cancel}) returns {@code void}.
 *
 * <p><b>Errors.</b> To answer a request with an error, throw an {@code AcpProtocolException} (from
 * {@code acp-core}) with a code from {@code AcpErrorCodes}: the client receives its code, message
 * and data. Any other exception is answered with an internal error ({@code -32603}) that carries
 * the exception's message, so keep secrets out of exception messages. An exception from a
 * notification handler is logged and dropped.
 *
 * <p><b>Threads and state.</b> One instance of the class serves every request: from every session,
 * and, with {@code AcpAgentSupport.Builder.buildFactory()}, from every connection. Its handler
 * methods run on the SDK's handler threads, so they may block, but they are called concurrently
 * and must be thread-safe. Keep per-session state in a concurrent map keyed by session id, and
 * remove it in a {@link CloseSession} or {@link DeleteSession} method.
 *
 * <p>Example usage:
 * <pre>{@code
 * @AcpAgent
 * public class GreetingAgent {
 *
 *     @Initialize
 *     public InitializeResponse initialize(InitializeRequest request) {
 *         return InitializeResponse.ok();
 *     }
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
	 * A name for the agent, for people reading the code. {@code AcpAgentSupport} neither reads
	 * it nor sends it to the client: set the name the client sees as {@code agentInfo} in the
	 * response of the {@link Initialize} method.
	 * @return the agent name, empty by default
	 */
	String name() default "";

	/**
	 * A version for the agent, for people reading the code; like {@link #name()}, it is not
	 * sent to the client.
	 * @return the agent version, empty by default
	 */
	String version() default "";

}

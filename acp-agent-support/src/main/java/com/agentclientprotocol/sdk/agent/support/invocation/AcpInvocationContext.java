/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support.invocation;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.agent.PromptContext;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import org.jspecify.annotations.Nullable;

/**
 * What one call of an annotated agent's handler method is about: the ACP method, the request, the
 * session id, the prompt turn's context, and the connection's capabilities and agent. The
 * annotation runtime creates one for each call and passes it to every
 * {@link com.agentclientprotocol.sdk.agent.support.interceptor.AcpInterceptor interceptor},
 * {@link com.agentclientprotocol.sdk.agent.support.resolver.ArgumentResolver argument resolver} and
 * {@link com.agentclientprotocol.sdk.agent.support.handler.ReturnValueHandler return value handler}
 * of that call. Read it to decide what to do, and use its attributes to pass state between the
 * steps of one call, such as a start time from {@code preInvoke} to {@code afterCompletion}.
 *
 * <p>What is present depends on the ACP method. {@link #getSessionId()} is present for the methods
 * of a session: {@code session/load}, {@code session/prompt}, {@code session/set_mode},
 * {@code session/set_config_option}, {@code session/close}, {@code session/delete},
 * {@code session/resume}, {@code session/fork} and {@code session/cancel}. The prompt contexts are
 * present in {@code session/prompt} only. In a context the runtime creates, {@link #getAgent()} is
 * always present and {@link #getCapabilities()} is present once the client has sent
 * {@code initialize}; under {@code AcpAgentSupport.Builder.buildFactory()} they are those of the
 * connection the request arrived on.
 *
 * <p>A context belongs to one call and is used on that call's handler thread; it is not
 * thread-safe. Its {@link Builder} lets a test of a custom resolver, handler or interceptor create
 * one.
 *
 * @author Mark Pollack
 */
public final class AcpInvocationContext {

	private final String acpMethod;

	private final Object request;

	private final @Nullable String sessionId;

	private final @Nullable PromptContext promptContext;

	private final @Nullable SyncPromptContext syncPromptContext;

	private final @Nullable NegotiatedCapabilities capabilities;

	private final @Nullable AcpSyncAgent agent;

	private final Map<String, Object> attributes = new HashMap<>();

	private AcpInvocationContext(Builder builder) {
		this.acpMethod = Objects.requireNonNull(builder.acpMethod, "acpMethod is required");
		this.request = Objects.requireNonNull(builder.request, "request is required");
		this.sessionId = builder.sessionId;
		this.promptContext = builder.promptContext;
		this.syncPromptContext = builder.syncPromptContext;
		this.capabilities = builder.capabilities;
		this.agent = builder.agent;
	}

	/**
	 * Starts a builder, for tests of a custom resolver, return value handler or interceptor; the
	 * runtime builds the contexts it passes itself.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Returns the ACP method being called, such as {@code "session/prompt"}, or the name of an
	 * extension method, which starts with {@code _}.
	 * @return the ACP method name
	 */
	public String getAcpMethod() {
		return acpMethod;
	}

	/**
	 * Returns the request: the ACP method's request record, such as a {@code PromptRequest}; the
	 * {@code CancelNotification} for {@code session/cancel}; or, for an extension method, its
	 * params, read as the type of the method's params parameter (the raw JSON value, as maps,
	 * lists, strings and numbers, when it takes none).
	 * @return the request
	 */
	public Object getRequest() {
		return request;
	}

	/**
	 * Returns the request as a {@code type}. The cast is not checked in this method: a request of
	 * another type fails with a {@link ClassCastException} where the result is used as a {@code T}.
	 * Check {@link #getAcpMethod()}, or {@code type.isInstance(getRequest())}, first.
	 * @param type the request type
	 * @param <T> the request type
	 * @return the request, cast to {@code type} without a check
	 */
	@SuppressWarnings("unchecked")
	public <T> T getRequest(Class<T> type) {
		return (T) request;
	}

	/**
	 * Returns the session id of a session method's request (see the class comment for the methods).
	 * @return the session id, or empty for {@code initialize}, {@code authenticate},
	 * {@code logout}, {@code session/new}, {@code session/list}, the provider methods and extension
	 * methods
	 */
	public Optional<String> getSessionId() {
		return Optional.ofNullable(sessionId);
	}

	/**
	 * Returns the prompt turn's context with calls that return a {@code Mono}. It is the same turn
	 * as {@link #getSyncPromptContext()}.
	 * @return the prompt context, present only in {@code session/prompt}
	 */
	public Optional<PromptContext> getPromptContext() {
		return Optional.ofNullable(promptContext);
	}

	/**
	 * Returns the prompt turn's context with blocking calls, such as {@code sendMessage} and
	 * {@code readFile}.
	 * @return the prompt context, present only in {@code session/prompt}
	 */
	public Optional<SyncPromptContext> getSyncPromptContext() {
		return Optional.ofNullable(syncPromptContext);
	}

	/**
	 * Returns the capabilities negotiated on this call's connection: what the client offered in its
	 * {@code initialize} request.
	 * @return the capabilities, present for every call once the client has sent {@code initialize},
	 * the {@code initialize} call included
	 */
	public Optional<NegotiatedCapabilities> getCapabilities() {
		return Optional.ofNullable(capabilities);
	}

	/**
	 * Returns the agent serving this call's connection; under
	 * {@code AcpAgentSupport.Builder.buildFactory()}, the agent of the connection the request
	 * arrived on. Use it to send session updates and requests to that client.
	 * @return the agent, present in every context the runtime creates
	 */
	public Optional<AcpSyncAgent> getAgent() {
		return Optional.ofNullable(agent);
	}

	/**
	 * Stores {@code value} under {@code name} for the rest of this call, replacing what was stored
	 * there. Attributes live as long as the context, one call; no other call sees them.
	 * @param name the attribute name
	 * @param value the value
	 */
	public void setAttribute(String name, Object value) {
		attributes.put(name, value);
	}

	/**
	 * Returns the value stored under {@code name} in this call.
	 * @param name the attribute name
	 * @return the value, or empty if none is stored
	 */
	public Optional<Object> getAttribute(String name) {
		return Optional.ofNullable(attributes.get(name));
	}

	/**
	 * Returns the value stored under {@code name} in this call, if it is a {@code type}.
	 * @param name the attribute name
	 * @param type the expected type
	 * @param <T> the expected type
	 * @return the value, or empty if none is stored or it is of another type
	 */
	@SuppressWarnings("unchecked")
	public <T> Optional<T> getAttribute(String name, Class<T> type) {
		Object value = attributes.get(name);
		if (type.isInstance(value)) {
			return Optional.of((T) value);
		}
		return Optional.empty();
	}

	/**
	 * Builds an {@link AcpInvocationContext}, for tests. The ACP method and the request are
	 * required; everything else is absent unless set.
	 */
	public static class Builder {

		private @Nullable String acpMethod;

		private @Nullable Object request;

		private @Nullable String sessionId;

		private @Nullable PromptContext promptContext;

		private @Nullable SyncPromptContext syncPromptContext;

		private @Nullable NegotiatedCapabilities capabilities;

		private @Nullable AcpSyncAgent agent;

		/**
		 * Sets the ACP method; required.
		 * @param acpMethod the ACP method name, such as {@code "session/prompt"}
		 * @return this builder
		 */
		public Builder acpMethod(String acpMethod) {
			this.acpMethod = acpMethod;
			return this;
		}

		/**
		 * Sets the request; required.
		 * @param request the request, such as a {@code PromptRequest}
		 * @return this builder
		 */
		public Builder request(Object request) {
			this.request = request;
			return this;
		}

		/**
		 * Sets the session id.
		 * @param sessionId the session id, or null for none
		 * @return this builder
		 */
		public Builder sessionId(@Nullable String sessionId) {
			this.sessionId = sessionId;
			return this;
		}

		/**
		 * Sets the prompt turn's context with calls that return a {@code Mono}.
		 * @param promptContext the context, or null for none
		 * @return this builder
		 */
		public Builder promptContext(@Nullable PromptContext promptContext) {
			this.promptContext = promptContext;
			return this;
		}

		/**
		 * Sets the prompt turn's context with blocking calls.
		 * @param syncPromptContext the context, or null for none
		 * @return this builder
		 */
		public Builder syncPromptContext(@Nullable SyncPromptContext syncPromptContext) {
			this.syncPromptContext = syncPromptContext;
			return this;
		}

		/**
		 * Sets the capabilities negotiated on the connection.
		 * @param capabilities the capabilities, or null for none
		 * @return this builder
		 */
		public Builder capabilities(@Nullable NegotiatedCapabilities capabilities) {
			this.capabilities = capabilities;
			return this;
		}

		/**
		 * Sets the agent serving the connection.
		 * @param agent the agent, or null for none
		 * @return this builder
		 */
		public Builder agent(@Nullable AcpSyncAgent agent) {
			this.agent = agent;
			return this;
		}

		/**
		 * Builds the context.
		 * @return the context, with no attributes
		 * @throws NullPointerException if the ACP method or the request is not set
		 */
		public AcpInvocationContext build() {
			return new AcpInvocationContext(this);
		}

	}

}

/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.capabilities;

import com.agentclientprotocol.sdk.annotation.UnstableAcpApi;
import com.agentclientprotocol.sdk.error.AcpCapabilityException;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.ClientCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.FileSystemCapability;
import com.agentclientprotocol.sdk.spec.AcpSchema.McpCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.ElicitationCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionCapabilities;
import org.jspecify.annotations.Nullable;

/**
 * What the other side of a connection advertised in the {@code initialize} exchange, as yes or no
 * answers: call a {@code supportsX()} method before offering or calling a feature, or a
 * {@code requireX()} method to fail with {@link AcpCapabilityException} when it is missing. An
 * agent reads the client's from
 * {@link com.agentclientprotocol.sdk.agent.AcpAsyncAgent#getClientCapabilities()}, from its prompt
 * context or from a {@code NegotiatedCapabilities} parameter of an annotated handler method; a
 * client reads the agent's from
 * {@link com.agentclientprotocol.sdk.client.AcpAsyncClient#getAgentCapabilities()}. Both are
 * {@code null} until {@code initialize} has been exchanged.
 *
 * <p>One instance describes one side. An instance from {@link #fromClient} answers the client
 * checks ({@code supportsReadTextFile} to {@code supportsBooleanConfigOptions}, which say what an
 * agent may ask of the client); one from {@link #fromAgent} answers the agent checks
 * ({@code supportsLoadSession} to {@code supportsLogout}, which say what a client may call or
 * send). Every check of the other side is false. A capability counts as advertised when its boolean
 * is {@code true}, or, for one ACP expresses as an object (such as {@code sessionCapabilities.list}
 * or {@code elicitation}), when the object is present.
 *
 * <p>The SDK already checks what it can: a client call the agent did not advertise
 * ({@code session/load}, {@code session/list}, {@code session/close}, {@code session/delete},
 * {@code session/resume}, {@code logout} and the unstable fork and provider methods) fails with
 * {@link AcpCapabilityException} without being sent, and so does an agent's file, terminal or
 * elicitation request the client did not advertise. It does not check prompt content, MCP server
 * types, additional directories or config option kinds: check those here, for example by offering
 * image prompts only when {@link #supportsImageContent()} is true.
 *
 * <p>Instances are immutable and safe to share between threads.
 *
 * <p>Example, an agent's prompt handler that reads a file only when the client allows it:
 * <pre>{@code
 * NegotiatedCapabilities client = context.getClientCapabilities();
 * String readme = (client != null && client.supportsReadTextFile())
 *         ? context.readFile("/workspace/README.md")
 *         : "";
 * }</pre>
 *
 * <p>Example, a client that resumes an earlier session only when the agent can load it:
 * <pre>{@code
 * NegotiatedCapabilities agent = client.getAgentCapabilities();
 * if (agent != null && agent.supportsLoadSession()) {
 *     client.loadSession(new AcpSchema.LoadSessionRequest(sessionId, "/workspace", List.of()));
 * }
 * }</pre>
 *
 * @author Mark Pollack
 * @see ClientCapabilities
 * @see AgentCapabilities
 */
public final class NegotiatedCapabilities {

	// Client capabilities (what client offers to agent)
	private final boolean readTextFile;

	private final boolean writeTextFile;

	private final boolean terminal;

	// Client elicitation capabilities
	private final boolean elicitation;

	private final boolean elicitationForm;

	private final boolean elicitationUrl;

	// Agent capabilities (what agent offers to client)
	private final boolean loadSession;

	private final boolean listSessions;

	private final boolean closeSession;

	private final boolean resumeSession;

	private final boolean deleteSession;

	private final boolean additionalDirectories;

	private final boolean forkSession;

	private final boolean providers;

	private final boolean imageContent;

	private final boolean audioContent;

	private final boolean embeddedContext;

	private final boolean mcpHttp;

	private final boolean mcpSse;

	private final boolean logout;

	private final boolean booleanConfigOptions;

	private final boolean terminalAuth;

	private NegotiatedCapabilities(Builder builder) {
		this.readTextFile = builder.readTextFile;
		this.writeTextFile = builder.writeTextFile;
		this.terminal = builder.terminal;
		this.elicitation = builder.elicitation;
		this.elicitationForm = builder.elicitationForm;
		this.elicitationUrl = builder.elicitationUrl;
		this.loadSession = builder.loadSession;
		this.listSessions = builder.listSessions;
		this.closeSession = builder.closeSession;
		this.resumeSession = builder.resumeSession;
		this.deleteSession = builder.deleteSession;
		this.additionalDirectories = builder.additionalDirectories;
		this.forkSession = builder.forkSession;
		this.providers = builder.providers;
		this.imageContent = builder.imageContent;
		this.audioContent = builder.audioContent;
		this.embeddedContext = builder.embeddedContext;
		this.mcpHttp = builder.mcpHttp;
		this.mcpSse = builder.mcpSse;
		this.logout = builder.logout;
		this.booleanConfigOptions = builder.booleanConfigOptions;
		this.terminalAuth = builder.terminalAuth;
	}

	/**
	 * Reads what a client advertised in its {@code initialize} request. The SDK calls it when the
	 * request arrives; call it yourself only to check capabilities you hold, for example in a test.
	 * @param caps the client's capabilities, or {@code null}, which gives an instance where every
	 * check is false
	 * @return the client's capabilities as checks
	 */
	public static NegotiatedCapabilities fromClient(@Nullable ClientCapabilities caps) {
		if (caps == null) {
			return new Builder().build();
		}

		Builder builder = new Builder();

		FileSystemCapability fs = caps.fs();
		if (fs != null) {
			builder.readTextFile(Boolean.TRUE.equals(fs.readTextFile()));
			builder.writeTextFile(Boolean.TRUE.equals(fs.writeTextFile()));
		}

		builder.terminal(Boolean.TRUE.equals(caps.terminal()));

		ElicitationCapabilities elicit = caps.elicitation();
		if (elicit != null) {
			builder.elicitation(true);
			builder.elicitationForm(elicit.form() != null);
			builder.elicitationUrl(elicit.url() != null);
		}

		var session = caps.session();
		var configOptions = session != null ? session.configOptions() : null;
		builder.booleanConfigOptions(configOptions != null && configOptions.booleanOptions() != null);

		var auth = caps.auth();
		builder.terminalAuth(auth != null && Boolean.TRUE.equals(auth.terminal()));

		return builder.build();
	}

	/**
	 * Reads what an agent advertised in its {@code initialize} response. The SDK calls it when the
	 * response arrives; call it yourself only to check capabilities you hold, for example in a
	 * test.
	 * @param caps the agent's capabilities, or {@code null}, which gives an instance where every
	 * check is false
	 * @return the agent's capabilities as checks
	 */
	public static NegotiatedCapabilities fromAgent(@Nullable AgentCapabilities caps) {
		if (caps == null) {
			return new Builder().build();
		}

		Builder builder = new Builder();
		builder.loadSession(Boolean.TRUE.equals(caps.loadSession()));

		SessionCapabilities sc = caps.sessionCapabilities();
		if (sc != null) {
			builder.listSessions(sc.list() != null);
			builder.closeSession(sc.close() != null);
			builder.resumeSession(sc.resume() != null);
			builder.deleteSession(sc.delete() != null);
			builder.additionalDirectories(sc.additionalDirectories() != null);
			builder.forkSession(sc.fork() != null);
		}

		builder.providers(caps.providers() != null);

		PromptCapabilities prompt = caps.promptCapabilities();
		if (prompt != null) {
			builder.imageContent(Boolean.TRUE.equals(prompt.image()));
			builder.audioContent(Boolean.TRUE.equals(prompt.audio()));
			builder.embeddedContext(Boolean.TRUE.equals(prompt.embeddedContext()));
		}

		McpCapabilities mcp = caps.mcpCapabilities();
		if (mcp != null) {
			builder.mcpHttp(Boolean.TRUE.equals(mcp.http()));
			builder.mcpSse(Boolean.TRUE.equals(mcp.sse()));
		}

		var auth = caps.auth();
		builder.logout(auth != null && auth.logout() != null);

		return builder.build();
	}

	// --------------------------
	// Client Capability Checks (for agents)
	// --------------------------

	/**
	 * Returns whether the client advertised {@code fs.readTextFile}: an agent may read text files
	 * through the client ({@code fs/read_text_file}).
	 * @return true if {@code fs.readTextFile} was advertised
	 */
	public boolean supportsReadTextFile() {
		return readTextFile;
	}

	/**
	 * Returns whether the client advertised {@code fs.writeTextFile}: an agent may write text files
	 * through the client ({@code fs/write_text_file}).
	 * @return true if {@code fs.writeTextFile} was advertised
	 */
	public boolean supportsWriteTextFile() {
		return writeTextFile;
	}

	/**
	 * Returns whether the client advertised {@code terminal}: an agent may run commands in
	 * terminals on the client ({@code terminal/*}).
	 * @return true if {@code terminal} was advertised
	 */
	public boolean supportsTerminal() {
		return terminal;
	}

	/**
	 * Checks that the client advertised {@code fs.readTextFile}, and throws if it did not (see
	 * {@link #supportsReadTextFile()}).
	 * @throws AcpCapabilityException if it did not; its capability is {@code "fs.readTextFile"}
	 */
	public void requireReadTextFile() {
		require(readTextFile, "fs.readTextFile");
	}

	/**
	 * Checks that the client advertised {@code fs.writeTextFile}, and throws if it did not (see
	 * {@link #supportsWriteTextFile()}).
	 * @throws AcpCapabilityException if it did not; its capability is {@code "fs.writeTextFile"}
	 */
	public void requireWriteTextFile() {
		require(writeTextFile, "fs.writeTextFile");
	}

	/**
	 * Checks that the client advertised {@code terminal}, and throws if it did not (see
	 * {@link #supportsTerminal()}).
	 * @throws AcpCapabilityException if it did not; its capability is {@code "terminal"}
	 */
	public void requireTerminal() {
		require(terminal, "terminal");
	}

	/**
	 * Returns whether the client advertised {@code elicitation} at all, in any mode: an agent may
	 * ask the user for input through the client ({@code elicitation/create}). Check
	 * {@link #supportsElicitationForm()} or {@link #supportsElicitationUrl()} for the mode you
	 * need.
	 * @return true if {@code elicitation} was advertised
	 */
	public boolean supportsElicitation() {
		return elicitation;
	}

	/**
	 * Returns whether the client advertised {@code elicitation.form}: an agent may ask the user to
	 * fill in a form.
	 * @return true if {@code elicitation.form} was advertised
	 */
	public boolean supportsElicitationForm() {
		return elicitationForm;
	}

	/**
	 * Returns whether the client advertised {@code elicitation.url}: an agent may ask the user to
	 * visit a URL.
	 * @return true if {@code elicitation.url} was advertised
	 */
	public boolean supportsElicitationUrl() {
		return elicitationUrl;
	}

	/**
	 * Checks that the client advertised {@code elicitation} in some mode, and throws if it did not.
	 * It does not check the mode; the SDK checks that when the agent sends the elicitation.
	 * @throws AcpCapabilityException if it did not; its capability is {@code "elicitation"}
	 */
	public void requireElicitation() {
		require(elicitation, "elicitation");
	}

	/**
	 * Returns whether the client advertised {@code auth.terminal}: it can run the agent's program
	 * itself for an interactive login, so an agent may offer terminal authentication methods.
	 * @return true if {@code auth.terminal} was advertised
	 */
	public boolean supportsTerminalAuth() {
		return terminalAuth;
	}

	/**
	 * Checks that the client advertised {@code auth.terminal}, and throws if it did not (see
	 * {@link #supportsTerminalAuth()}).
	 * @throws AcpCapabilityException if it did not; its capability is {@code "auth.terminal"}
	 */
	public void requireTerminalAuth() {
		require(terminalAuth, "auth.terminal");
	}

	/**
	 * Returns whether the client advertised {@code session.configOptions.boolean}: an agent may
	 * offer boolean config options, not only selects.
	 * @return true if {@code session.configOptions.boolean} was advertised
	 */
	public boolean supportsBooleanConfigOptions() {
		return booleanConfigOptions;
	}

	/**
	 * Checks that the client advertised {@code session.configOptions.boolean}, and throws if it did
	 * not (see {@link #supportsBooleanConfigOptions()}).
	 * @throws AcpCapabilityException if it did not; its capability is
	 * {@code "session.configOptions.boolean"}
	 */
	public void requireBooleanConfigOptions() {
		require(booleanConfigOptions, "session.configOptions.boolean");
	}

	// --------------------------
	// Agent Capability Checks (for clients)
	// --------------------------

	/**
	 * Returns whether the agent advertised {@code loadSession}: a client may call
	 * {@code session/load}.
	 * @return true if {@code loadSession} was advertised
	 */
	public boolean supportsLoadSession() {
		return loadSession;
	}

	/**
	 * Returns whether the agent advertised {@code sessionCapabilities.list}: a client may call
	 * {@code session/list}.
	 * @return true if {@code sessionCapabilities.list} was advertised
	 */
	public boolean supportsListSessions() {
		return listSessions;
	}

	/**
	 * Returns whether the agent advertised {@code sessionCapabilities.close}: a client may call
	 * {@code session/close}.
	 * @return true if {@code sessionCapabilities.close} was advertised
	 */
	public boolean supportsCloseSession() {
		return closeSession;
	}

	/**
	 * Returns whether the agent advertised {@code sessionCapabilities.resume}: a client may call
	 * {@code session/resume}.
	 * @return true if {@code sessionCapabilities.resume} was advertised
	 */
	public boolean supportsResumeSession() {
		return resumeSession;
	}

	/**
	 * Returns whether the agent advertised {@code sessionCapabilities.delete}: a client may call
	 * {@code session/delete}.
	 * @return true if {@code sessionCapabilities.delete} was advertised
	 */
	public boolean supportsDeleteSession() {
		return deleteSession;
	}

	/**
	 * Returns whether the agent advertised {@code sessionCapabilities.additionalDirectories}: a
	 * client may send additional workspace directories with {@code session/new},
	 * {@code session/load} and {@code session/resume}.
	 * @return true if {@code sessionCapabilities.additionalDirectories} was advertised
	 */
	public boolean supportsAdditionalDirectories() {
		return additionalDirectories;
	}

	/**
	 * Returns whether the agent advertised {@code sessionCapabilities.fork}: a client may call
	 * {@code session/fork}.
	 * @return true if {@code sessionCapabilities.fork} was advertised
	 */
	@UnstableAcpApi
	public boolean supportsForkSession() {
		return forkSession;
	}

	/**
	 * Returns whether the agent advertised {@code providers}: a client may call the
	 * {@code providers/*} methods.
	 * @return true if {@code providers} was advertised
	 */
	@UnstableAcpApi
	public boolean supportsProviders() {
		return providers;
	}

	/**
	 * Returns whether the agent advertised {@code promptCapabilities.image}: a client may send
	 * image content in a prompt.
	 * @return true if {@code promptCapabilities.image} was advertised
	 */
	public boolean supportsImageContent() {
		return imageContent;
	}

	/**
	 * Returns whether the agent advertised {@code promptCapabilities.audio}: a client may send
	 * audio content in a prompt.
	 * @return true if {@code promptCapabilities.audio} was advertised
	 */
	public boolean supportsAudioContent() {
		return audioContent;
	}

	/**
	 * Returns whether the agent advertised {@code promptCapabilities.embeddedContext}: a client may
	 * embed resources in a prompt.
	 * @return true if {@code promptCapabilities.embeddedContext} was advertised
	 */
	public boolean supportsEmbeddedContext() {
		return embeddedContext;
	}

	/**
	 * Returns whether the agent advertised {@code mcpCapabilities.http}: a client may pass it MCP
	 * servers that use HTTP.
	 * @return true if {@code mcpCapabilities.http} was advertised
	 */
	public boolean supportsMcpHttp() {
		return mcpHttp;
	}

	/**
	 * Returns whether the agent advertised {@code mcpCapabilities.sse}: a client may pass it MCP
	 * servers that use SSE.
	 * @return true if {@code mcpCapabilities.sse} was advertised
	 */
	public boolean supportsMcpSse() {
		return mcpSse;
	}

	/**
	 * Checks that the agent advertised {@code loadSession}, and throws if it did not (see
	 * {@link #supportsLoadSession()}).
	 * @throws AcpCapabilityException if it did not; its capability is {@code "loadSession"}
	 */
	public void requireLoadSession() {
		require(loadSession, "loadSession");
	}

	/**
	 * Checks that the agent advertised {@code sessionCapabilities.list}, and throws if it did not
	 * (see {@link #supportsListSessions()}).
	 * @throws AcpCapabilityException if it did not; its capability is
	 * {@code "sessionCapabilities.list"}
	 */
	public void requireListSessions() {
		require(listSessions, "sessionCapabilities.list");
	}

	/**
	 * Checks that the agent advertised {@code sessionCapabilities.close}, and throws if it did not
	 * (see {@link #supportsCloseSession()}).
	 * @throws AcpCapabilityException if it did not; its capability is
	 * {@code "sessionCapabilities.close"}
	 */
	public void requireCloseSession() {
		require(closeSession, "sessionCapabilities.close");
	}

	/**
	 * Checks that the agent advertised {@code sessionCapabilities.resume}, and throws if it did not
	 * (see {@link #supportsResumeSession()}).
	 * @throws AcpCapabilityException if it did not; its capability is
	 * {@code "sessionCapabilities.resume"}
	 */
	public void requireResumeSession() {
		require(resumeSession, "sessionCapabilities.resume");
	}

	/**
	 * Checks that the agent advertised {@code sessionCapabilities.delete}, and throws if it did not
	 * (see {@link #supportsDeleteSession()}).
	 * @throws AcpCapabilityException if it did not; its capability is
	 * {@code "sessionCapabilities.delete"}
	 */
	public void requireDeleteSession() {
		require(deleteSession, "sessionCapabilities.delete");
	}

	/**
	 * Checks that the agent advertised {@code sessionCapabilities.additionalDirectories}, and
	 * throws if it did not (see {@link #supportsAdditionalDirectories()}).
	 * @throws AcpCapabilityException if it did not; its capability is
	 * {@code "sessionCapabilities.additionalDirectories"}
	 */
	public void requireAdditionalDirectories() {
		require(additionalDirectories, "sessionCapabilities.additionalDirectories");
	}

	/**
	 * Checks that the agent advertised {@code sessionCapabilities.fork}, and throws if it did not
	 * (see {@link #supportsForkSession()}).
	 * @throws AcpCapabilityException if it did not; its capability is
	 * {@code "sessionCapabilities.fork"}
	 */
	@UnstableAcpApi
	public void requireForkSession() {
		require(forkSession, "sessionCapabilities.fork");
	}

	/**
	 * Checks that the agent advertised {@code providers}, and throws if it did not (see
	 * {@link #supportsProviders()}).
	 * @throws AcpCapabilityException if it did not; its capability is {@code "providers"}
	 */
	@UnstableAcpApi
	public void requireProviders() {
		require(providers, "providers");
	}

	/**
	 * Checks that the agent advertised {@code promptCapabilities.image}, and throws if it did not
	 * (see {@link #supportsImageContent()}).
	 * @throws AcpCapabilityException if it did not; its capability is
	 * {@code "promptCapabilities.image"}
	 */
	public void requireImageContent() {
		require(imageContent, "promptCapabilities.image");
	}

	/**
	 * Checks that the agent advertised {@code promptCapabilities.audio}, and throws if it did not
	 * (see {@link #supportsAudioContent()}).
	 * @throws AcpCapabilityException if it did not; its capability is
	 * {@code "promptCapabilities.audio"}
	 */
	public void requireAudioContent() {
		require(audioContent, "promptCapabilities.audio");
	}

	/**
	 * Returns whether the agent advertised {@code auth.logout}: a client may call {@code logout}.
	 * @return true if {@code auth.logout} was advertised
	 */
	public boolean supportsLogout() {
		return logout;
	}

	/**
	 * Checks that the agent advertised {@code auth.logout}, and throws if it did not (see
	 * {@link #supportsLogout()}).
	 * @throws AcpCapabilityException if it did not; its capability is {@code "auth.logout"}
	 */
	public void requireLogout() {
		require(logout, "auth.logout");
	}

	private static void require(boolean supported, String capability) {
		if (!supported) {
			throw new AcpCapabilityException(capability);
		}
	}

	@Override
	public String toString() {
		return "NegotiatedCapabilities{" + "readTextFile=" + readTextFile + ", writeTextFile=" + writeTextFile
				+ ", terminal=" + terminal + ", elicitation=" + elicitation + ", elicitationForm="
				+ elicitationForm + ", elicitationUrl=" + elicitationUrl + ", loadSession=" + loadSession
				+ ", listSessions=" + listSessions
				+ ", closeSession=" + closeSession + ", resumeSession=" + resumeSession + ", deleteSession="
				+ deleteSession + ", additionalDirectories=" + additionalDirectories + ", forkSession="
				+ forkSession + ", providers=" + providers + ", imageContent="
				+ imageContent + ", audioContent=" + audioContent + ", embeddedContext=" + embeddedContext
				+ ", mcpHttp=" + mcpHttp + ", mcpSse=" + mcpSse + ", terminalAuth=" + terminalAuth + ", booleanConfigOptions=" + booleanConfigOptions + ", logout=" + logout + '}';
	}

	/**
	 * Builds an instance by hand, every capability false until set. The SDK reads capabilities with
	 * {@link #fromClient} and {@link #fromAgent}; the builder is for tests and for code that has
	 * capabilities in another form. Each setter names the capability it stands for. Not
	 * thread-safe.
	 */
	public static class Builder {

		private boolean readTextFile = false;

		private boolean writeTextFile = false;

		private boolean terminal = false;

		private boolean elicitation = false;

		private boolean elicitationForm = false;

		private boolean elicitationUrl = false;

		private boolean loadSession = false;

		private boolean listSessions = false;

		private boolean closeSession = false;

		private boolean resumeSession = false;

		private boolean deleteSession = false;

		private boolean additionalDirectories = false;

		private boolean forkSession = false;

		private boolean providers = false;

		private boolean imageContent = false;

		private boolean audioContent = false;

		private boolean embeddedContext = false;

		private boolean mcpHttp = false;

		private boolean mcpSse = false;

		private boolean logout = false;

		private boolean booleanConfigOptions = false;

		private boolean terminalAuth = false;

		/**
		 * Sets whether {@code fs.readTextFile} counts as advertised.
		 * @param value true if it is advertised
		 * @return this builder
		 */
		public Builder readTextFile(boolean value) {
			this.readTextFile = value;
			return this;
		}

		/**
		 * Sets whether {@code fs.writeTextFile} counts as advertised.
		 * @param value true if it is advertised
		 * @return this builder
		 */
		public Builder writeTextFile(boolean value) {
			this.writeTextFile = value;
			return this;
		}

		/**
		 * Sets whether {@code terminal} counts as advertised.
		 * @param value true if it is advertised
		 * @return this builder
		 */
		public Builder terminal(boolean value) {
			this.terminal = value;
			return this;
		}

		/**
		 * Sets whether {@code elicitation} counts as advertised.
		 * @param value true if it is advertised
		 * @return this builder
		 */
		public Builder elicitation(boolean value) {
			this.elicitation = value;
			return this;
		}

		/**
		 * Sets whether {@code elicitation.form} counts as advertised.
		 * @param value true if it is advertised
		 * @return this builder
		 */
		public Builder elicitationForm(boolean value) {
			this.elicitationForm = value;
			return this;
		}

		/**
		 * Sets whether {@code elicitation.url} counts as advertised.
		 * @param value true if it is advertised
		 * @return this builder
		 */
		public Builder elicitationUrl(boolean value) {
			this.elicitationUrl = value;
			return this;
		}

		/**
		 * Sets whether {@code loadSession} counts as advertised.
		 * @param value true if it is advertised
		 * @return this builder
		 */
		public Builder loadSession(boolean value) {
			this.loadSession = value;
			return this;
		}

		/**
		 * Sets whether {@code sessionCapabilities.list} counts as advertised.
		 * @param value true if it is advertised
		 * @return this builder
		 */
		public Builder listSessions(boolean value) {
			this.listSessions = value;
			return this;
		}

		/**
		 * Sets whether {@code sessionCapabilities.close} counts as advertised.
		 * @param value true if it is advertised
		 * @return this builder
		 */
		public Builder closeSession(boolean value) {
			this.closeSession = value;
			return this;
		}

		/**
		 * Sets whether {@code sessionCapabilities.resume} counts as advertised.
		 * @param value true if it is advertised
		 * @return this builder
		 */
		public Builder resumeSession(boolean value) {
			this.resumeSession = value;
			return this;
		}

		/**
		 * Sets whether {@code sessionCapabilities.delete} counts as advertised.
		 * @param value true if it is advertised
		 * @return this builder
		 */
		public Builder deleteSession(boolean value) {
			this.deleteSession = value;
			return this;
		}

		/**
		 * Sets whether {@code sessionCapabilities.additionalDirectories} counts as advertised.
		 * @param value true if it is advertised
		 * @return this builder
		 */
		public Builder additionalDirectories(boolean value) {
			this.additionalDirectories = value;
			return this;
		}

		/**
		 * Sets whether {@code sessionCapabilities.fork} counts as advertised.
		 * @param value true if it is advertised
		 * @return this builder
		 */
		@UnstableAcpApi
		public Builder forkSession(boolean value) {
			this.forkSession = value;
			return this;
		}

		/**
		 * Sets whether {@code providers} counts as advertised.
		 * @param value true if it is advertised
		 * @return this builder
		 */
		@UnstableAcpApi
		public Builder providers(boolean value) {
			this.providers = value;
			return this;
		}

		/**
		 * Sets whether {@code promptCapabilities.image} counts as advertised.
		 * @param value true if it is advertised
		 * @return this builder
		 */
		public Builder imageContent(boolean value) {
			this.imageContent = value;
			return this;
		}

		/**
		 * Sets whether {@code promptCapabilities.audio} counts as advertised.
		 * @param value true if it is advertised
		 * @return this builder
		 */
		public Builder audioContent(boolean value) {
			this.audioContent = value;
			return this;
		}

		/**
		 * Sets whether {@code promptCapabilities.embeddedContext} counts as advertised.
		 * @param value true if it is advertised
		 * @return this builder
		 */
		public Builder embeddedContext(boolean value) {
			this.embeddedContext = value;
			return this;
		}

		/**
		 * Sets whether {@code mcpCapabilities.http} counts as advertised.
		 * @param value true if it is advertised
		 * @return this builder
		 */
		public Builder mcpHttp(boolean value) {
			this.mcpHttp = value;
			return this;
		}

		/**
		 * Sets whether {@code mcpCapabilities.sse} counts as advertised.
		 * @param value true if it is advertised
		 * @return this builder
		 */
		public Builder mcpSse(boolean value) {
			this.mcpSse = value;
			return this;
		}

		/**
		 * Sets whether {@code auth.terminal} counts as advertised.
		 * @param value true if it is advertised
		 * @return this builder
		 */
		public Builder terminalAuth(boolean value) {
			this.terminalAuth = value;
			return this;
		}

		/**
		 * Sets whether {@code session.configOptions.boolean} counts as advertised.
		 * @param value true if it is advertised
		 * @return this builder
		 */
		public Builder booleanConfigOptions(boolean value) {
			this.booleanConfigOptions = value;
			return this;
		}

		/**
		 * Sets whether {@code auth.logout} counts as advertised.
		 * @param value true if it is advertised
		 * @return this builder
		 */
		public Builder logout(boolean value) {
			this.logout = value;
			return this;
		}

		/**
		 * Returns the capabilities set so far.
		 * @return a new instance
		 */
		public NegotiatedCapabilities build() {
			return new NegotiatedCapabilities(this);
		}

	}

}

/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.integration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Builds the application's ACP client the same way in every framework: one
 * {@link AcpAsyncClient} on the transport from {@link AcpClientTransports}, configured from the
 * {@link AcpClientSettings} and the application's {@link AcpClientCustomizer}s, and one
 * {@link AcpSyncClient} facade over that same client. A framework exposes both as beans, and
 * closes the async one through an {@link AcpClientHost} when the container shuts down.
 *
 * <p>What the shared code does: it advertises the settings' capabilities, applies the request
 * and prompt timeouts, sets a default session-update consumer that logs each update at DEBUG
 * (so the SDK does not warn about updates nobody handles), applies the customizers in order,
 * and turns a missing-handler error into one that names the framework's capability settings.
 * What the framework does: create the transport, collect the customizer beans in its bean
 * order, and pass its own property prefix so errors name its keys.
 */
public final class AcpClients {

	private static final Logger logger = LoggerFactory.getLogger(AcpClients.class);

	private AcpClients() {
	}

	/**
	 * Returns the async client as
	 * {@link #async(AcpClientTransport, AcpClientSettings, List, String)} builds it, with errors
	 * that name {@code acp.client.*} keys.
	 * @param transport the client transport
	 * @param settings the client settings
	 * @param customizers the customizers, in order
	 * @return the client, not yet initialized
	 * @throws IllegalStateException if a capability setting is true but no customizer registers
	 * the handlers that serve it, or the transport refuses to connect
	 */
	public static AcpAsyncClient async(AcpClientTransport transport, AcpClientSettings settings,
			List<? extends AcpClientCustomizer> customizers) {
		return async(transport, settings, customizers, AcpClientTransports.DEFAULT_PREFIX);
	}

	/**
	 * Returns the async client on the transport: the settings' capabilities, their request
	 * timeout (unset keeps the SDK default, 60 seconds) and prompt timeout (unset: none), a
	 * default session-update consumer, then the customizers in order. The default consumer logs
	 * each update at DEBUG; the first consumer a customizer adds with
	 * {@code sessionUpdateConsumer} replaces it, rather than running beside it. Building the
	 * client connects the transport (for stdio, starts the agent process); the ACP handshake
	 * waits for the application's {@code initialize()}.
	 * @param transport the client transport, not yet used by another client
	 * @param settings the client settings
	 * @param customizers the customizers, in order
	 * @param prefix the framework's prefix of the client keys, such as {@code spring.acp.client},
	 * which errors name
	 * @return the client, not yet initialized
	 * @throws IllegalStateException if a capability setting is true but no customizer registers
	 * the handlers that serve it; the message names the SDK's missing setters and the settings,
	 * such as {@code spring.acp.client.capabilities.terminal=true}. A customizer that calls
	 * {@code defaultSessionUpdateConsumer} also fails, since the default is already set, and so
	 * does a transport that refuses to connect: one already connected, or a stdio command that
	 * cannot be started
	 */
	public static AcpAsyncClient async(AcpClientTransport transport, AcpClientSettings settings,
			List<? extends AcpClientCustomizer> customizers, String prefix) {
		AcpClient.AsyncSpec spec = AcpClient.async(transport)
			.clientCapabilities(settings.capabilities().toClientCapabilities())
			// Session updates always have a consumer, so the SDK does not warn about an unhandled
			// one. This one only logs; a consumer a customizer adds replaces it.
			.defaultSessionUpdateConsumer(AcpClients::logSessionUpdate);
		Duration requestTimeout = settings.requestTimeout();
		if (requestTimeout != null) {
			spec.requestTimeout(requestTimeout);
		}
		Duration promptTimeout = settings.promptTimeout();
		if (promptTimeout != null) {
			spec.promptTimeout(promptTimeout);
		}
		customizers.forEach(customizer -> customizer.customize(spec));
		try {
			return spec.build();
		}
		catch (IllegalStateException ex) {
			throw namingTheSettings(ex, prefix, settings.capabilities());
		}
	}

	/**
	 * Adds to the SDK's error about an advertised capability without its handler which of the
	 * capability settings it came from: register the handler in an {@link AcpClientCustomizer}, or
	 * set the setting to false. Any other error is returned as it is.
	 */
	static IllegalStateException namingTheSettings(IllegalStateException error, String prefix,
			AcpClientSettings.Capabilities capabilities) {
		String message = String.valueOf(error.getMessage());
		// The SDK's message says "<capability> needs <setters>" for each capability short of handlers.
		Map<String, Boolean> settings = new LinkedHashMap<>();
		settings.put("read-text-file", capabilities.readTextFile() && message.contains("fs.readTextFile needs"));
		settings.put("write-text-file", capabilities.writeTextFile() && message.contains("fs.writeTextFile needs"));
		settings.put("terminal", capabilities.terminal() && message.contains("terminal needs"));
		settings.put("elicitation-form", capabilities.elicitationForm() && message.contains("elicitation needs"));
		settings.put("elicitation-url", capabilities.elicitationUrl() && message.contains("elicitation needs"));
		List<String> named = new ArrayList<>();
		settings.forEach((setting, missing) -> {
			if (missing) {
				named.add(prefix + ".capabilities." + setting + "=true");
			}
		});
		if (named.isEmpty()) {
			return error;
		}
		return new IllegalStateException(message + ". The capabilities come from " + String.join(", ", named)
				+ ": register the handlers in an AcpClientCustomizer, or set the settings to false", error);
	}

	/**
	 * Returns a sync facade over the async client, so the application can inject either and both
	 * use one connection. Build the sync client this way, never a second client on the same
	 * transport: that would connect the transport a second time, which the SDK refuses. Closing
	 * the async client (through {@link AcpClientHost}) also ends the facade.
	 * @param async the async client from {@link #async}
	 * @return the sync client
	 */
	public static AcpSyncClient sync(AcpAsyncClient async) {
		return new AcpSyncClient(async);
	}

	static Mono<Void> logSessionUpdate(AcpSchema.SessionNotification notification) {
		logger.debug("Session update for {}: {}", notification.sessionId(), notification.update());
		return Mono.empty();
	}

}

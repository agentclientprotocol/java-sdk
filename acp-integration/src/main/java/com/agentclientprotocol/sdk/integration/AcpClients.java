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
 * Builds the application's client the same way in every framework: one async client on the
 * transport, and a sync facade over that same client.
 */
public final class AcpClients {

	private static final Logger logger = LoggerFactory.getLogger(AcpClients.class);

	private AcpClients() {
	}

	/**
	 * The async client, naming {@code acp.client.*} keys in errors; see
	 * {@link #async(AcpClientTransport, AcpClientSettings, List, String)}.
	 * @param transport the client transport
	 * @param settings the client settings
	 * @param customizers the customizers, in order
	 * @return the client
	 */
	public static AcpAsyncClient async(AcpClientTransport transport, AcpClientSettings settings,
			List<? extends AcpClientCustomizer> customizers) {
		return async(transport, settings, customizers, AcpClientTransports.DEFAULT_PREFIX);
	}

	/**
	 * The async client: the settings' capabilities, request timeout (unset keeps the SDK
	 * default) and prompt timeout (unset: none), a default session-update consumer that logs at
	 * DEBUG, which a consumer a customizer adds replaces, then the customizers in order. The
	 * client connects when the application calls {@code initialize()}.
	 * @param transport the client transport
	 * @param settings the client settings
	 * @param customizers the customizers, in order
	 * @param prefix the framework's prefix of the client keys, such as {@code spring.acp.client},
	 * which errors name
	 * @return the client
	 * @throws IllegalStateException if a capability setting is true but no customizer registers
	 * the handlers that serve it; the message names the setting and the missing setters
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
	 * The sync facade over the one async client: one session on one transport connection.
	 * Building a sync client from the transport instead would connect it a second time, which the
	 * SDK refuses.
	 * @param async the async client
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

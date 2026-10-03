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
 * Marks the {@link AcpAgent} method that answers {@code session/set_config_option}: the client
 * changes one of the config options the agent offered for a session, such as the model, and the
 * method applies the change and returns all of the session's options with their current values.
 * Declare one when the agent returns config options from its {@link NewSession},
 * {@link LoadSession} or {@link ResumeSession} method; the agent must then also have a {@link
 * NewSession} method, since the default {@code session/new} offers none (building the agent fails
 * otherwise); a model picker is a select option with category {@code "model"}. Without a {@code
 * @SetSessionConfigOption} method the agent answers {@code session/set_config_option} with "Method
 * not found" ({@code -32601}).
 *
 * <p>The request names the option ({@code configId}) and its new {@code value}: a
 * {@code String} for a select option, a {@code Boolean} for a boolean one. A {@link ConfigId}
 * {@code String} parameter receives the id, and a {@link ConfigValue} parameter the value, typed
 * by the parameter ({@code String}, {@code boolean} or {@code Object}); a value of the other kind
 * is answered with {@code -32602} without calling the method. The SDK does not check that the id
 * and value name an option and a value the session offered,
 * so answer an unknown option or a value that was not offered with an {@code AcpProtocolException}
 * with {@code AcpErrorCodes.INVALID_PARAMS} ({@code -32602}). Offer boolean options only to clients
 * that support them: take a {@code NegotiatedCapabilities} parameter and check
 * {@code supportsBooleanConfigOptions()}. When the agent changes options on its own, it tells the
 * client with a {@code ConfigOptionUpdate} session update.
 *
 * <p>The method can take a {@code SetSessionConfigOptionRequest}, a {@link SessionId @SessionId}
 * {@code String}, {@link ConfigId @ConfigId} and {@link ConfigValue @ConfigValue} parameters, and
 * the connection parameters (see {@link AcpAgent}). It returns a
 * {@code SetSessionConfigOptionResponse} with the full list of options, or a {@code Mono} of one.
 *
 * <p>Example usage:
 * <pre>{@code
 * private final Map<String, String> models = new ConcurrentHashMap<>();
 *
 * @SetSessionConfigOption
 * public SetSessionConfigOptionResponse set(@SessionId String sessionId, @ConfigId String id,
 *         @ConfigValue String model) {
 *     if (!"model".equals(id) || !Set.of("fast", "deep").contains(model)) {
 *         throw new AcpProtocolException(AcpErrorCodes.INVALID_PARAMS, "No option " + id + " = " +
 * model);     }     models.put(sessionId, model);     return new
 * SetSessionConfigOptionResponse(List.of(SessionConfigSelect.model("model", "Model",
 * model, List.of(new SessionConfigSelectOption("fast", "Fast"),                     new
 * SessionConfigSelectOption("deep", "Deep"))))); } }</pre>
 *
 * @author Mark Pollack
 * @since 0.12.0
 * @see AcpAgent
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SetSessionConfigOption {

}

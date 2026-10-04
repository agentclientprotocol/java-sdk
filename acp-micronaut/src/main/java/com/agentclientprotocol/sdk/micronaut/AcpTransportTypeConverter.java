/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut;

import java.util.Optional;

import com.agentclientprotocol.sdk.integration.AcpTransportType;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.TypeConverter;
import jakarta.inject.Singleton;

/**
 * Binds {@code acp.agent.transport.type} and {@code acp.client.transport.type} by
 * {@link AcpTransportType#parse(String)}: in any case, ignoring surrounding white space, as the
 * Spring Boot and Quarkus integrations do. Micronaut's own enum conversion accepted only the
 * constant's name or its lower-case form, so {@code WebSocket} failed to bind.
 */
@Singleton
final class AcpTransportTypeConverter implements TypeConverter<CharSequence, AcpTransportType> {

	@Override
	public Optional<AcpTransportType> convert(CharSequence value, Class<AcpTransportType> targetType,
			ConversionContext context) {
		// Thrown, not rejected: Micronaut skips a rejected value of a @Nullable property, and the
		// client's type would then be inferred as if it were unset.
		return Optional.of(AcpTransportType.parse(value.toString()));
	}

}

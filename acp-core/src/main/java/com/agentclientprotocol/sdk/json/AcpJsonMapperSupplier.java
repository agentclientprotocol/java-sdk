/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

import java.util.function.Supplier;

/**
 * Strategy interface for providing an {@link AcpJsonMapper} implementation.
 *
 * <p>
 * Implementations are discovered at runtime via {@link java.util.ServiceLoader}.
 * To register an implementation, create a file at
 * {@code META-INF/services/com.agentclientprotocol.sdk.json.AcpJsonMapperSupplier}
 * containing the fully qualified class name of the supplier.
 * </p>
 *
 * <p>
 * When several suppliers are on the classpath, {@link AcpJsonMapper#createDefault()}
 * picks the one with the highest {@link #priority()}; see there for the full rule.
 * </p>
 *
 * @author Mark Pollack
 * @see AcpJsonMapper#createDefault()
 */
public interface AcpJsonMapperSupplier extends Supplier<AcpJsonMapper> {

	/**
	 * The priority of a supplier that does not override {@link #priority()}. It is higher
	 * than the priority of both SDK-provided suppliers, so an application's own supplier
	 * wins over them without further configuration.
	 */
	int DEFAULT_PRIORITY = 0;

	/**
	 * This supplier's priority when more than one supplier is on the classpath: the
	 * highest value wins. The SDK's own suppliers use negative values ({@code
	 * acp-json-jackson2} is {@code -200}, {@code acp-json-jackson3} is {@code -100}).
	 * @return the priority, {@link #DEFAULT_PRIORITY} unless overridden
	 */
	default int priority() {
		return DEFAULT_PRIORITY;
	}

}

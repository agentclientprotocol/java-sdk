/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

import java.util.function.Supplier;

/**
 * Supplies the {@link AcpJsonMapper} that {@link AcpJsonMapper#createDefault()} returns. Each JSON
 * module registers one with {@link java.util.ServiceLoader}, and {@code createDefault()} picks one
 * by {@link #priority()}. Implement it to plug in a JSON library the SDK does not ship, or a
 * Jackson mapper configured your way for every transport that creates its own mapper.
 *
 * <p>To register an implementation, list its fully qualified class name in
 * {@code META-INF/services/com.agentclientprotocol.sdk.json.AcpJsonMapperSupplier}; ServiceLoader
 * needs a public class with a public no-argument constructor. With the default priority it wins
 * over the SDK's own suppliers. The system property {@code acp.json.mapper.supplier} chooses a
 * supplier by class name instead (see {@link AcpJsonMapper#createDefault()}).
 *
 * <p>Implementations: {@link #get()} is called on every {@code createDefault()} call, and the
 * mapper it returns must meet the {@link AcpJsonMapper} requirements, including use from several
 * threads at once. {@link #priority()} returns {@link #DEFAULT_PRIORITY} unless overridden.
 *
 * @author Mark Pollack
 * @see AcpJsonMapper#createDefault()
 */
public interface AcpJsonMapperSupplier extends Supplier<AcpJsonMapper> {

	/**
	 * The priority of a supplier that does not override {@link #priority()}: {@value}. It is higher
	 * than the priority of both SDK suppliers, so an application's own supplier wins over them
	 * without further configuration.
	 */
	int DEFAULT_PRIORITY = 0;

	/**
	 * Returns this supplier's rank when {@link AcpJsonMapper#createDefault()} finds more than one:
	 * the highest wins, ties broken by class name. The SDK's suppliers use negative values,
	 * {@code -200} for {@code acp-json-jackson2} and {@code -100} for {@code acp-json-jackson3}.
	 * The system property {@code acp.json.mapper.supplier}, when set, decides instead.
	 * @implSpec Returns {@link #DEFAULT_PRIORITY}.
	 * @return the priority
	 */
	default int priority() {
		return DEFAULT_PRIORITY;
	}

}

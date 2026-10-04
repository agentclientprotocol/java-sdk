/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.json.jackson3;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.AcpJsonMapperSupplier;

/**
 * The {@link AcpJsonMapperSupplier} of the {@code acp-json-jackson3} module, registered with
 * {@link java.util.ServiceLoader} so that {@link AcpJsonMapper#createDefault()} finds it. Each
 * {@link #get()} returns a new {@link Jackson3AcpJsonMapper} on
 * {@link Jackson3AcpJsonMapper#defaultJsonMapper()}. Applications do not call it: they call
 * {@code createDefault()}, or create the mapper themselves.
 *
 * <p>Unlike the Jackson 2 module's {@code JacksonAcpJsonMapperSupplier} ({@code -200}), its
 * {@link #priority()} is {@value #PRIORITY}, so adding {@code acp-json-jackson3} next to the
 * {@code acp-json-jackson2} that other SDK modules bring in switches to Jackson 3 without
 * exclusions. An application supplier that keeps the default priority still wins over it.
 *
 * @author Mark Pollack
 */
public class Jackson3AcpJsonMapperSupplier implements AcpJsonMapperSupplier {

	/**
	 * This supplier's priority, {@value}: above the Jackson 2 supplier's and below
	 * {@link AcpJsonMapperSupplier#DEFAULT_PRIORITY}.
	 */
	public static final int PRIORITY = -100;

	@Override
	public int priority() {
		return PRIORITY;
	}

	/**
	 * Returns a new {@link Jackson3AcpJsonMapper} built on
	 * {@link Jackson3AcpJsonMapper#defaultJsonMapper()}. The Jackson version is checked before the
	 * {@code JsonMapper} is built.
	 * @return a new mapper
	 * @throws IllegalStateException if the jackson-core or jackson-databind on the classpath is
	 * older than 3.0.0
	 */
	@Override
	public AcpJsonMapper get() {
		// Before building the JsonMapper: an unsupported Jackson may fail inside it.
		Jackson3Versions.requireSupported();
		return new Jackson3AcpJsonMapper(Jackson3AcpJsonMapper.defaultJsonMapper());
	}

}

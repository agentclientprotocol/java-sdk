/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

/**
 * The {@link AcpJsonMapperSupplier} of the {@code acp-json-jackson2} module, registered with
 * {@link java.util.ServiceLoader} so that {@link AcpJsonMapper#createDefault()} finds it. Each
 * {@link #get()} returns a new {@link JacksonAcpJsonMapper} on
 * {@link JacksonAcpJsonMapper#defaultObjectMapper()}. Applications do not call it: they call
 * {@code createDefault()}, or create the mapper themselves.
 *
 * <p>Its {@link #priority()} is {@value #PRIORITY}, the lowest of the SDK's: the Jackson 3 supplier
 * and any application supplier that keeps the default priority win over it. To use it while
 * {@code acp-json-jackson3} is on the classpath too, set the system property
 * {@code acp.json.mapper.supplier} to this class's name.
 *
 * @author Mark Pollack
 */
public class JacksonAcpJsonMapperSupplier implements AcpJsonMapperSupplier {

	/**
	 * This supplier's priority, {@value}: below the Jackson 3 supplier's and below
	 * {@link AcpJsonMapperSupplier#DEFAULT_PRIORITY}.
	 */
	public static final int PRIORITY = -200;

	@Override
	public int priority() {
		return PRIORITY;
	}

	/**
	 * Returns a new {@link JacksonAcpJsonMapper} built on
	 * {@link JacksonAcpJsonMapper#defaultObjectMapper()}. The Jackson version is checked before the
	 * {@code ObjectMapper} is built.
	 * @return a new mapper
	 * @throws IllegalStateException if the jackson-core or jackson-databind on the classpath is
	 * older than 2.18.1
	 */
	@Override
	public AcpJsonMapper get() {
		// Before building the ObjectMapper: an unsupported Jackson may fail inside it.
		JacksonVersions.requireSupported();
		return new JacksonAcpJsonMapper(JacksonAcpJsonMapper.defaultObjectMapper());
	}

}

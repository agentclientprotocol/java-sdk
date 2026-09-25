/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

/**
 * Jackson 2 supplier of {@link AcpJsonMapper} instances, registered via
 * {@link java.util.ServiceLoader}. Its {@link #priority()} is {@value #PRIORITY}: below
 * the Jackson 3 supplier and below any application supplier that keeps the default.
 *
 * @author Mark Pollack
 */
public class JacksonAcpJsonMapperSupplier implements AcpJsonMapperSupplier {

	/** The priority of this supplier. */
	public static final int PRIORITY = -200;

	@Override
	public int priority() {
		return PRIORITY;
	}

	@Override
	public AcpJsonMapper get() {
		return new JacksonAcpJsonMapper(JacksonAcpJsonMapper.defaultObjectMapper());
	}

}

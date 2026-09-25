/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.json.jackson3;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.AcpJsonMapperSupplier;

/**
 * Jackson 3 supplier of {@link AcpJsonMapper} instances, registered via
 * {@link java.util.ServiceLoader}. Its {@link #priority()} is {@value #PRIORITY}: above
 * the Jackson 2 supplier, so adding {@code acp-json-jackson3} next to the
 * {@code acp-json-jackson2} that the transport modules bring in switches to Jackson 3
 * without exclusions, and below any application supplier that keeps the default.
 *
 * @author Mark Pollack
 */
public class Jackson3AcpJsonMapperSupplier implements AcpJsonMapperSupplier {

	/** The priority of this supplier. */
	public static final int PRIORITY = -100;

	@Override
	public int priority() {
		return PRIORITY;
	}

	@Override
	public AcpJsonMapper get() {
		return new Jackson3AcpJsonMapper(Jackson3AcpJsonMapper.defaultJsonMapper());
	}

}

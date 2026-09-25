/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

/**
 * Messages every JSON module's strict-mapper test reads, so each implementation is held
 * to the same fixtures.
 */
public final class StrictMapperFixtures {

	/** An agent capability this SDK does not know. */
	public static final String CAPABILITIES_WITH_DRIFT = """
			{"loadSession": true, "fabricatedFeature": true}
			""";

	/** Only fields the spec defines, including {@code _meta}. */
	public static final String ON_SPEC_INITIALIZE_RESPONSE = """
			{"protocolVersion": 1, "agentCapabilities": {"loadSession": true,
			 "promptCapabilities": {"image": true, "audio": false, "embeddedContext": true}},
			 "authMethods": [], "agentInfo": {"name": "x", "version": "1"}, "_meta": {"k": "v"}}
			""";

	private StrictMapperFixtures() {
	}

}

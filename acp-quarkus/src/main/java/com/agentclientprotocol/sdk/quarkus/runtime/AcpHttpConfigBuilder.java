/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.util.Map;

import io.quarkus.runtime.configuration.ConfigBuilder;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;

/**
 * Defaults for an application that serves its agent over HTTP, so the Quarkus HTTP
 * server admits what the SDK's own listener admits: a 16 MB POST body and WebSocket
 * message, sent as one frame or many, and 1024 concurrent HTTP/2 streams per connection (every SSE stream is one,
 * and a client holds one per session). The application's own configuration still wins.
 *
 * @author Mark Pollack
 */
public class AcpHttpConfigBuilder implements ConfigBuilder {

	static final Map<String, String> DEFAULTS = Map.of("quarkus.http.limits.max-body-size", "16M",
			"quarkus.http.websocket-server.max-message-size", "16777216",
			"quarkus.http.websocket-server.max-frame-size", "16777216",
			"quarkus.http.limits.max-concurrent-streams", "1024");

	@Override
	public SmallRyeConfigBuilder configBuilder(SmallRyeConfigBuilder builder) {
		return builder
			.withSources(new PropertiesConfigSource(DEFAULTS, "ACP HTTP agent defaults", AcpStdioConfigBuilder.ORDINAL));
	}

}

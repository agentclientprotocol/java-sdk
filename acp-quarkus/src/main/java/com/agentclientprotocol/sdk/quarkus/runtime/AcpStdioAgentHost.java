/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.time.Duration;

import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.integration.AcpAgentHost;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves the {@code @AcpAgent} bean over one agent transport, stdio unless the application provides
 * its own {@link AcpAgentTransport} bean, on an {@link AcpAgentHost}. The extension adds it when
 * {@code quarkus.acp.agent.transport.type} is {@code stdio} and the application has an
 * {@code @AcpAgent} class. The agent starts with the application; when the transport ends (the
 * client closed standard input and every answer has been written) the application exits, unless
 * {@code quarkus.acp.agent.shutdown-on-transport-end} is off; when the application stops, the agent
 * closes gracefully, waiting at most 30 seconds. An application does not call it; a test can inject
 * it to reach the running agent.
 *
 * @author Mark Pollack
 */
@Singleton
public class AcpStdioAgentHost {

	private static final Logger logger = LoggerFactory.getLogger(AcpStdioAgentHost.class);

	/** How long stopping waits for the agent's graceful close. */
	private static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);

	private final AcpAgentAssembly assembly;

	private final AcpAgentTransport transport;

	private volatile @Nullable AcpAgentSupport agent;

	private volatile @Nullable AcpAgentHost host;

	AcpStdioAgentHost(AcpAgentAssembly assembly, AcpAgentTransport transport) {
		this.assembly = assembly;
		this.transport = transport;
	}

	void start(@Observes StartupEvent event) {
		AcpAgentSupport support = assembly.builder().transport(transport).build();
		Runnable onTransportEnd = assembly.settings().shutdownOnTransportEnd() ? AcpStdioAgentHost::exit : () -> {
		};
		AcpAgentHost started = new AcpAgentHost(support, onTransportEnd);
		this.agent = support;
		this.host = started;
		started.start();
	}

	void stop(@Observes ShutdownEvent event) {
		AcpAgentHost current = this.host;
		if (current != null) {
			current.stop(STOP_TIMEOUT);
		}
	}

	private static void exit() {
		logger.info("ACP agent transport ended; stopping the application");
		Quarkus.asyncExit();
	}

	/**
	 * Returns the running agent, once the application has started.
	 * @return the agent, or {@code null} before startup
	 */
	public @Nullable AcpAgentSupport agent() {
		return agent;
	}

}

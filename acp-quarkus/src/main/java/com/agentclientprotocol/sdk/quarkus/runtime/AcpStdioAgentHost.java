/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

import com.agentclientprotocol.sdk.agent.support.AcpAgentSupport;
import com.agentclientprotocol.sdk.quarkus.AcpRuntimeConfig;
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
 * Serves the {@code @AcpAgent} bean over one agent transport, stdio unless the
 * application provides its own {@link AcpAgentTransport} bean. The agent starts with the
 * application; when the transport ends (the client closed standard input) the
 * application exits, unless {@code quarkus.acp.agent.shutdown-on-transport-end} is off;
 * when the application stops, the agent closes gracefully.
 *
 * @author Mark Pollack
 */
@Singleton
public class AcpStdioAgentHost {

	private static final Logger logger = LoggerFactory.getLogger(AcpStdioAgentHost.class);

	private final AcpAgentAssembly assembly;

	private final AcpAgentTransport transport;

	private final AcpRuntimeConfig config;

	private final AtomicBoolean stopping = new AtomicBoolean(false);

	private volatile @Nullable AcpAgentSupport agent;

	AcpStdioAgentHost(AcpAgentAssembly assembly, AcpAgentTransport transport, AcpRuntimeConfig config) {
		this.assembly = assembly;
		this.transport = transport;
		this.config = config;
	}

	void start(@Observes StartupEvent event) {
		AcpAgentSupport support = assembly.builder().transport(transport).build();
		this.agent = support;
		support.start();
		if (config.agent().shutdownOnTransportEnd()) {
			transport.awaitTermination().subscribe(ignored -> {
			}, error -> transportEnded(), this::transportEnded);
		}
	}

	private void transportEnded() {
		if (!stopping.get()) {
			logger.info("ACP agent transport ended; stopping the application");
			Quarkus.asyncExit();
		}
	}

	void stop(@Observes ShutdownEvent event) {
		if (stopping.compareAndSet(false, true)) {
			AcpAgentSupport support = this.agent;
			if (support != null) {
				support.close();
			}
		}
	}

	/**
	 * The running agent, once the application has started.
	 * @return the agent, or null before startup
	 */
	public @Nullable AcpAgentSupport agent() {
		return agent;
	}

}

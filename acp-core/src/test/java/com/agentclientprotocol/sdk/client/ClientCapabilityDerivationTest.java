/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpAsyncAgent;
import com.agentclientprotocol.sdk.capabilities.NegotiatedCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A client built without {@code clientCapabilities(..)} advertises what its handlers serve, as an
 * agent without an initialize handler does: {@code fs.readTextFile} and {@code fs.writeTextFile}
 * for their handlers, {@code terminal} once all five terminal handlers are registered, and form
 * elicitation for an elicitation handler. Capabilities set explicitly win: nothing is derived, and
 * the handler check of the explicit capabilities applies as before.
 */
class ClientCapabilityDerivationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final Logger logger = (Logger) LoggerFactory.getLogger(AcpClient.class);

	private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

	private final InMemoryTransportPair pair = InMemoryTransportPair.create();

	private final AcpAsyncAgent agent = AcpAgent.async(pair.agentTransport())
		.promptHandler((request, context) -> Mono.just(AcpSchema.PromptResponse.endTurn()))
		.build();

	@BeforeEach
	void start() {
		logged.start();
		logger.addAppender(logged);
		agent.start().block(TIMEOUT);
	}

	@AfterEach
	void stop() {
		logger.detachAppender(logged);
		agent.close();
		pair.closeGracefully().block(TIMEOUT);
	}

	/** Initializes the client and returns what the agent saw. */
	private NegotiatedCapabilities advertised(AcpAsyncClient client) {
		try {
			client.initialize().block(TIMEOUT);
			return agent.getClientCapabilities();
		}
		finally {
			client.close();
		}
	}

	private static <Q, R> Function<Q, Mono<R>> none() {
		return request -> Mono.empty();
	}

	@Test
	void handlersAdvertiseTheirCapabilities() {
		NegotiatedCapabilities caps = advertised(AcpClient.async(pair.clientTransport())
			.readTextFileHandler(none())
			.createTerminalHandler(none())
			.terminalOutputHandler(none())
			.releaseTerminalHandler(none())
			.waitForTerminalExitHandler(none())
			.killTerminalHandler(none())
			.createElicitationHandler(none())
			.build());

		assertThat(caps.supportsReadTextFile()).isTrue();
		assertThat(caps.supportsWriteTextFile()).isFalse();
		assertThat(caps.supportsTerminal()).isTrue();
		assertThat(caps.supportsElicitationForm()).isTrue();
		assertThat(caps.supportsElicitationUrl()).isFalse();
		assertThat(warnings()).isEmpty();
	}

	@Test
	void syncHandlersAdvertiseTheirCapabilities() {
		AcpSyncClient client = AcpClient.sync(pair.clientTransport())
			.writeTextFileHandler(request -> new AcpSchema.WriteTextFileResponse())
			.build();
		try {
			client.initialize();
			NegotiatedCapabilities caps = agent.getClientCapabilities();
			assertThat(caps.supportsWriteTextFile()).isTrue();
			assertThat(caps.supportsReadTextFile()).isFalse();
			assertThat(caps.supportsTerminal()).isFalse();
		}
		finally {
			client.close();
		}
	}

	@Test
	void noHandlersAdvertiseTheDefaultCapabilities() {
		AcpAsyncClient client = AcpClient.async(pair.clientTransport()).build();
		try {
			client.initialize().block(TIMEOUT);
			NegotiatedCapabilities caps = agent.getClientCapabilities();
			assertThat(caps.supportsReadTextFile() || caps.supportsWriteTextFile() || caps.supportsTerminal()
					|| caps.supportsElicitation()).isFalse();
		}
		finally {
			client.close();
		}
	}

	@Test
	void someTerminalHandlersAdvertiseNoTerminalAndWarn() {
		NegotiatedCapabilities caps = advertised(AcpClient.async(pair.clientTransport())
			.createTerminalHandler(none())
			.killTerminalHandler(none())
			.build());

		assertThat(caps.supportsTerminal()).isFalse();
		assertThat(warnings()).singleElement()
			.satisfies(warning -> assertThat(warning).contains("terminalOutputHandler", "releaseTerminalHandler",
					"waitForTerminalExitHandler"));
	}

	@Test
	void explicitCapabilitiesWin() {
		NegotiatedCapabilities caps = advertised(AcpClient.async(pair.clientTransport())
			.clientCapabilities(AcpSchema.ClientCapabilities.builder()
				.session(AcpSchema.ClientSessionCapabilities.withBooleanConfigOptions())
				.build())
			.readTextFileHandler(none())
			.build());

		assertThat(caps.supportsReadTextFile()).isFalse();
		assertThat(warnings()).singleElement()
			.satisfies(warning -> assertThat(warning).contains("readTextFileHandler", "fs.readTextFile"));
	}

	private List<String> warnings() {
		return logged.list.stream()
			.filter(event -> event.getLevel() == Level.WARN)
			.map(ILoggingEvent::getFormattedMessage)
			.toList();
	}

}

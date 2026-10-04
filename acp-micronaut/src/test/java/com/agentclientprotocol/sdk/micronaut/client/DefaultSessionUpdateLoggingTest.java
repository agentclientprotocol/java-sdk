/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.client;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agentclientprotocol.sdk.integration.AcpClientCustomizer;
import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The client bean logs session updates at DEBUG only while the application adds no consumer of
 * its own: an application consumer replaces that default instead of running beside it.
 */
class DefaultSessionUpdateLoggingTest {

	private final Logger logger = (Logger) LoggerFactory.getLogger(com.agentclientprotocol.sdk.integration.AcpClients.class);

	private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

	private Level level;

	@BeforeEach
	void captureDebugLogs() {
		logged.start();
		level = logger.getLevel();
		logger.setLevel(Level.DEBUG);
		logger.addAppender(logged);
	}

	@AfterEach
	void restoreLogging() {
		logger.detachAppender(logged);
		logger.setLevel(level);
	}

	@Test
	void withoutAnApplicationConsumerTheDefaultLogs() {
		prompt(List.of());

		assertThat(logged.list).anySatisfy(
				event -> assertThat(event.getFormattedMessage()).startsWith("Session update for session-1"));
	}

	@Test
	void anApplicationConsumerReplacesTheDefault() {
		List<AcpSchema.SessionNotification> received = new CopyOnWriteArrayList<>();
		prompt(List.of(spec -> spec.sessionUpdateConsumer(notification -> {
			received.add(notification);
			return Mono.empty();
		})));

		assertThat(received).singleElement();
		assertThat(logged.list)
			.noneSatisfy(event -> assertThat(event.getFormattedMessage()).startsWith("Session update for"));
	}

	private static void prompt(List<AcpClientCustomizer> customizers) {
		InMemoryTransportPair pair = InMemoryTransportPair.create();
		AcpSyncAgent agent = AcpAgent.sync(pair.agentTransport())
			.newSessionHandler(request -> new AcpSchema.NewSessionResponse("session-1", null, null))
			.promptHandler((request, context) -> {
				context.sendMessage("hello");
				return AcpSchema.PromptResponse.endTurn();
			})
			.build();
		agent.start();
		AcpSyncClient client = new AcpSyncClient(
				new AcpClientBeans().acpAsyncClient(pair.clientTransport(), new AcpClientConfiguration(), customizers));
		try {
			client.initialize();
			client.newSession(new AcpSchema.NewSessionRequest("/workspace", List.of()));
			client.prompt(new AcpSchema.PromptRequest("session-1", List.of(new AcpSchema.TextContent("hi"))));
		}
		finally {
			client.close();
			agent.closeGracefully();
		}
	}

}

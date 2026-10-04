/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.micronaut.client;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.annotation.Order;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

/** Client-side beans for the tests, switched on by {@link #RECORDING}. */
public final class TestCustomizers {

	/** Switches the beans below on. */
	public static final String RECORDING = "test.client.recording";

	/** Switches {@link FilesAndTerminals} on. */
	public static final String SERVES_FILES_AND_TERMINALS = "test.client.serves-files-and-terminals";

	private TestCustomizers() {
	}

	/** The order customizers ran in, and the text chunks the client received. */
	@Singleton
	@Requires(property = RECORDING, value = "true")
	public static class Record {

		/** Customizer names, in the order they ran. */
		public final List<String> customizers = new CopyOnWriteArrayList<>();

		/** agent_message_chunk texts received. */
		public final List<String> chunks = new CopyOnWriteArrayList<>();

		/** How often the transport was closed gracefully. */
		public final AtomicInteger transportCloses = new AtomicInteger();

	}

	/** Runs second, and adds the session-update consumer that records chunks. */
	@Singleton
	@Order(20)
	@Requires(property = RECORDING, value = "true")
	public static class Second implements AcpClientCustomizer {

		private final Record record;

		Second(Record record) {
			this.record = record;
		}

		@Override
		public void customize(com.agentclientprotocol.sdk.client.AcpClient.AsyncSpec spec) {
			record.customizers.add("second");
			spec.sessionUpdateConsumer(notification -> {
				if (notification.update() instanceof AcpSchema.AgentMessageChunk chunk
						&& chunk.content() instanceof AcpSchema.TextContent text) {
					record.chunks.add(text.text());
				}
				return Mono.empty();
			});
		}

	}

	/** Runs first. */
	@Singleton
	@Order(10)
	@Requires(property = RECORDING, value = "true")
	public static class First implements AcpClientCustomizer {

		private final Record record;

		First(Record record) {
			this.record = record;
		}

		@Override
		public void customize(com.agentclientprotocol.sdk.client.AcpClient.AsyncSpec spec) {
			record.customizers.add("first");
		}

	}

	/**
	 * Serves file reads and terminals, so a test may advertise them with
	 * {@code acp.client.capabilities.*}: a client that advertises a capability must have its
	 * handlers. The agents under test never call them.
	 */
	@Singleton
	@Order(30)
	@Requires(property = SERVES_FILES_AND_TERMINALS, value = "true")
	public static class FilesAndTerminals implements AcpClientCustomizer {

		@Override
		public void customize(com.agentclientprotocol.sdk.client.AcpClient.AsyncSpec spec) {
			spec.readTextFileHandler(request -> Mono.empty())
				.createTerminalHandler(request -> Mono.empty())
				.terminalOutputHandler(request -> Mono.empty())
				.releaseTerminalHandler(request -> Mono.empty())
				.waitForTerminalExitHandler(request -> Mono.empty())
				.killTerminalHandler(request -> Mono.empty());
		}

	}

	/** Wraps the configured transport to count its graceful closes. */
	@Singleton
	@Requires(property = RECORDING, value = "true")
	public static class CountCloses implements BeanCreatedEventListener<AcpClientTransport> {

		private final Record record;

		CountCloses(Record record) {
			this.record = record;
		}

		@Override
		public AcpClientTransport onCreated(BeanCreatedEvent<AcpClientTransport> event) {
			return new CountingTransport(event.getBean(), record.transportCloses);
		}

	}

}

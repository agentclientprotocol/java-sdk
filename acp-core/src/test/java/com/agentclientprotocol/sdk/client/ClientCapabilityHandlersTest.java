/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agentclientprotocol.sdk.MockAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A client's advertised capabilities must match its handlers: advertising {@code fs.readTextFile},
 * {@code fs.writeTextFile}, {@code terminal} or {@code elicitation} without the handler that
 * serves it fails at {@code build()}, naming the setter, instead of every such request from the
 * agent being answered "Method not found". With capabilities set explicitly, a handler for a
 * capability the client does not advertise is logged once at WARN: an SDK agent will not call it.
 * (Without them the handlers decide; see {@code ClientCapabilityDerivationTest}.)
 */
class ClientCapabilityHandlersTest {

	private final Logger logger = (Logger) LoggerFactory.getLogger(AcpClient.class);

	private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

	@BeforeEach
	void captureWarnings() {
		logged.start();
		logger.addAppender(logged);
	}

	@AfterEach
	void restoreLogging() {
		logger.detachAppender(logged);
	}

	@Test
	void advertisingFileReadsWithoutAHandlerFails() {
		assertThatThrownBy(() -> AcpClient.async(new MockAcpClientTransport())
			.clientCapabilities(capabilities(true, false, false, null))
			.build()).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("fs.readTextFile")
			.hasMessageContaining("readTextFileHandler");
	}

	@Test
	void advertisingFileWritesWithoutAHandlerFails() {
		assertThatThrownBy(() -> AcpClient.sync(new MockAcpClientTransport())
			.clientCapabilities(capabilities(false, true, false, null))
			.readTextFileHandler(request -> null)
			.build()).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("fs.writeTextFile")
			.hasMessageContaining("writeTextFileHandler")
			.hasMessageNotContaining("readTextFileHandler,");
	}

	@Test
	void advertisingTerminalsNeedsAllFiveTerminalHandlers() {
		assertThatThrownBy(() -> AcpClient.async(new MockAcpClientTransport())
			.clientCapabilities(capabilities(false, false, true, null))
			.createTerminalHandler(request -> Mono.empty())
			.killTerminalHandler(request -> Mono.empty())
			.build()).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("terminal")
			.hasMessageContaining("terminalOutputHandler")
			.hasMessageContaining("releaseTerminalHandler")
			.hasMessageContaining("waitForTerminalExitHandler")
			.hasMessageNotContaining("createTerminalHandler")
			.hasMessageNotContaining("killTerminalHandler");
	}

	@Test
	void advertisingElicitationWithoutAHandlerFails() {
		assertThatThrownBy(() -> AcpClient.async(new MockAcpClientTransport())
			.clientCapabilities(capabilities(false, false, false, AcpSchema.ElicitationCapabilities.formOnly()))
			.build()).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("elicitation")
			.hasMessageContaining("createElicitationHandler");
	}

	@Test
	void everyMissingHandlerIsNamedAtOnce() {
		assertThatThrownBy(() -> AcpClient.async(new MockAcpClientTransport())
			.clientCapabilities(capabilities(true, true, false, null))
			.build()).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("readTextFileHandler")
			.hasMessageContaining("writeTextFileHandler");
	}

	@Test
	void advertisedCapabilitiesWithTheirHandlersBuild() {
		AcpSyncClient client = AcpClient.sync(new MockAcpClientTransport())
			.clientCapabilities(capabilities(true, true, true, AcpSchema.ElicitationCapabilities.formOnly()))
			.readTextFileHandler(request -> null)
			.writeTextFileHandler(request -> null)
			.createTerminalHandler(request -> null)
			.terminalOutputHandler(request -> null)
			.releaseTerminalHandler(request -> null)
			.waitForTerminalExitHandler(request -> null)
			.killTerminalHandler(request -> null)
			.createElicitationHandler(request -> null)
			.build();

		assertThat(warnings()).isEmpty();
		client.close();
	}

	@Test
	void aHandlerForACapabilityNotAdvertisedLogsOneWarning() {
		AcpAsyncClient client = AcpClient.async(new MockAcpClientTransport())
			.clientCapabilities(new AcpSchema.ClientCapabilities())
			.readTextFileHandler(request -> Mono.empty())
			.createTerminalHandler(request -> Mono.empty())
			.createElicitationHandler(request -> Mono.empty())
			.requestPermissionHandler(request -> Mono.empty())
			.build();

		assertThat(warnings()).singleElement().satisfies(warning -> assertThat(warning)
			.contains("readTextFileHandler", "fs.readTextFile", "createTerminalHandler", "terminal",
					"createElicitationHandler", "elicitation")
			.doesNotContain("requestPermissionHandler"));
		client.close();
	}

	@Test
	void theDefaultCapabilitiesNeedNoHandler() {
		AcpAsyncClient client = AcpClient.async(new MockAcpClientTransport())
			.requestPermissionHandler(request -> Mono.empty())
			.build();

		assertThat(warnings()).isEmpty();
		client.close();
	}

	private java.util.List<String> warnings() {
		return logged.list.stream()
			.filter(event -> event.getLevel() == Level.WARN)
			.map(ILoggingEvent::getFormattedMessage)
			.toList();
	}

	private static AcpSchema.ClientCapabilities capabilities(boolean read, boolean write, boolean terminal,
			AcpSchema.ElicitationCapabilities elicitation) {
		return AcpSchema.ClientCapabilities.builder()
			.fs(new AcpSchema.FileSystemCapability(read, write))
			.terminal(terminal)
			.elicitation(elicitation)
			.build();
	}

}

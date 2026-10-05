/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import com.agentclientprotocol.sdk.spring.boot.autoconfigure.client.AcpClientProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The build writes Spring Boot's metadata into this module's classes on every JDK: the
 * configuration metadata IDEs complete {@code spring.acp.*} from, and the autoconfiguration
 * metadata Boot reads its conditions from early. On JDK 21 and later the {@code errorprone}
 * profile's processor path used to hide both processors, so neither file was written.
 */
class ConfigurationMetadataTests {

	@Test
	void everySpringAcpPropertyIsDescribed() throws IOException {
		Path metadata = moduleClasses().resolve("META-INF/spring-configuration-metadata.json");
		assertThat(metadata).exists();
		JsonNode root = new ObjectMapper().readTree(metadata.toFile());
		List<String> names = new ArrayList<>();
		for (JsonNode property : root.get("properties")) {
			String name = property.get("name").asString();
			names.add(name);
			assertThat(property.path("description").asString()).as(name).isNotBlank();
		}
		assertThat(names).hasSize(36).allMatch(name -> name.startsWith("spring.acp."));
		assertThat(names).contains("spring.acp.agent.transport.http.shutdown-timeout",
				"spring.acp.agent.transport.http.web-socket-idle-timeout",
				"spring.acp.agent.transport.http.initialize-timeout",
				"spring.acp.client.transport.websocket.connect-timeout", "spring.acp.client.transport.stdio.env");
	}

	@Test
	void theAutoConfigurationMetadataIsWritten() throws IOException {
		Path metadata = moduleClasses().resolve("META-INF/spring-autoconfigure-metadata.properties");
		assertThat(metadata).exists();
		Properties properties = new Properties();
		try (InputStream in = Files.newInputStream(metadata)) {
			properties.load(in);
		}
		assertThat(properties.stringPropertyNames()).anyMatch(key -> key.startsWith(
				"com.agentclientprotocol.sdk.spring.boot.autoconfigure.client.AcpClientAutoConfiguration."));
	}

	/** This module's classes directory, not another jar's metadata of the same name. */
	private static Path moduleClasses() {
		try {
			URI location = AcpClientProperties.class.getProtectionDomain().getCodeSource().getLocation().toURI();
			return Path.of(location);
		}
		catch (URISyntaxException ex) {
			throw new IllegalStateException(ex);
		}
	}

}

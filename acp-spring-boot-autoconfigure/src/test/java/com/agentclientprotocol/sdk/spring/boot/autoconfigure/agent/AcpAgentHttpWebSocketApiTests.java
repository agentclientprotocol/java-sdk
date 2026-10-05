/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent.AcpAgentHttpAutoConfigurationTests.EchoAgentConfiguration;
import org.junit.jupiter.api.Test;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A servlet web application on Tomcat 11 whose classpath puts the Jakarta WebSocket 2.1 API
 * ahead of Tomcat's 2.2 classes, as a build does that takes the API from Jetty's jars without
 * managing its version: Tomcat then fails every WebSocket send with {@code NoSuchMethodError}.
 * Before, the application started and its WebSocket clients hung; now the startup fails with a
 * message that names the fix.
 */
class AcpAgentHttpWebSocketApiTests {

	@Test
	void anOlderWebSocketApiAheadOfTomcatFailsTheStartupNamingTheFix() throws Exception {
		List<URL> urls = new ArrayList<>();
		try (Stream<Path> jars = Files.list(Path.of("target", "websocket-api-2.1"))) {
			for (Path jar : jars.toList()) {
				urls.add(jar.toUri().toURL());
			}
		}
		assertThat(urls).hasSize(2);
		for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
			urls.add(Path.of(entry).toUri().toURL());
		}
		ClassLoader previous = Thread.currentThread().getContextClassLoader();
		try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
			Thread.currentThread().setContextClassLoader(loader);
			String outcome = (String) loader.loadClass(TrappedApplication.class.getName())
				.getMethod("start")
				.invoke(null);
			assertThat(outcome).contains("Jakarta WebSocket 2.2")
				.contains("acp-http-servlet")
				.contains("acp-streamable-http-jetty");
		}
		finally {
			Thread.currentThread().setContextClassLoader(previous);
		}
	}

	@Test
	void anApplicationWithoutTomcatWebSocketsIsNotChecked() {
		assertThat(WebSocketApiCheck.problem(ClassLoader.getPlatformClassLoader())).isNull();
		assertThat(WebSocketApiCheck.problem(getClass().getClassLoader())).as("Tomcat 11 with the 2.2 API").isNull();
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@Import(EchoAgentConfiguration.class)
	public static class TrappedApplication {

		/**
		 * Starts the application in the class loader this class came from.
		 * @return "started", or the message of the startup failure's root cause
		 */
		public static String start() {
			// This Tomcat runs in a class loader of its own: it must not install the JVM-wide URL
			// stream handler factory, which the other tests' Tomcat installs.
			org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.disable();
			try (ConfigurableApplicationContext context = SpringApplication.run(TrappedApplication.class,
					"--server.port=0", "--server.address=127.0.0.1", "--spring.main.web-application-type=servlet",
					"--spring.acp.agent.transport.type=http")) {
				return "started";
			}
			catch (RuntimeException e) {
				Throwable cause = e;
				while (cause.getCause() != null) {
					cause = cause.getCause();
				}
				return String.valueOf(cause.getMessage());
			}
		}

	}

}

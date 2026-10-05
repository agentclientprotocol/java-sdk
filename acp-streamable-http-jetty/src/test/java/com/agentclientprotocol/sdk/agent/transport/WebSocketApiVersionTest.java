/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.transport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The listener brings the Jakarta WebSocket 2.2 API, which Jetty 12.1 runs on and Tomcat 11
 * needs. Jetty's own jars bring 2.1, which lacks {@code SendResult(Session)}: in an application
 * that also runs Tomcat 11 (a Spring Boot web application with this module added) Tomcat then
 * failed every WebSocket send with {@code NoSuchMethodError}.
 */
class WebSocketApiVersionTest {

	@Test
	void theApiOnTheClasspathIsJakartaWebSocket22() {
		assertThatCode(() -> Class.forName("jakarta.websocket.SendResult")
			.getConstructor(Class.forName("jakarta.websocket.Session"))).doesNotThrowAnyException();
	}

}

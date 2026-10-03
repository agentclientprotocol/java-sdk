/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

/**
 * The application's {@code @AcpAgent} class, found in the index at build time.
 *
 * @param type the annotated class (the user's class, never a proxy or subclass)
 * @author Mark Pollack
 */
public record AcpAgentClass(Class<?> type) {
}

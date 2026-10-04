/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

/**
 * The application's {@code @AcpAgent} class, found in the index at build time and handed to the
 * running application as a synthetic bean. {@link AcpAgentAssembly} reads the handler methods from
 * this class. The bean exists only when the agent is enabled and the application has an
 * {@code @AcpAgent} class. Part of the extension's wiring; an application does not use it directly.
 *
 * @param type the annotated class (the user's class, never a proxy or subclass)
 * @author Mark Pollack
 */
public record AcpAgentClass(Class<?> type) {
}

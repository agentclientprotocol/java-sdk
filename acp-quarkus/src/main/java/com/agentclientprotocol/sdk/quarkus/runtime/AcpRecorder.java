/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.util.function.Supplier;

import io.quarkus.runtime.annotations.Recorder;

/**
 * Carries what the build found into the running application: Quarkus records the calls made on it
 * at build time and replays them at startup. It hands over the {@code @AcpAgent} class as an
 * {@link AcpAgentClass} bean. Part of the extension's wiring; an application does not use it.
 *
 * @author Mark Pollack
 */
@Recorder
public class AcpRecorder {

	/**
	 * Returns a supplier of the {@link AcpAgentClass} bean, which loads the {@code @AcpAgent} class
	 * with the application's class loader when the bean is created.
	 * @param className the class's binary name
	 * @return a supplier of the agent class bean; it throws {@link IllegalStateException} if the
	 * class cannot be loaded
	 */
	public Supplier<AcpAgentClass> agentClass(String className) {
		return () -> {
			try {
				return new AcpAgentClass(
						Class.forName(className, false, Thread.currentThread().getContextClassLoader()));
			}
			catch (ClassNotFoundException e) {
				throw new IllegalStateException("The @AcpAgent class " + className + " is not loadable", e);
			}
		};
	}

}

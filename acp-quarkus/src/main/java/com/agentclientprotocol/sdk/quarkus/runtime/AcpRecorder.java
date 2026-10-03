/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.runtime;

import java.util.function.Supplier;

import io.quarkus.runtime.annotations.Recorder;

/**
 * Carries what the build found into the running application.
 *
 * @author Mark Pollack
 */
@Recorder
public class AcpRecorder {

	/**
	 * The {@code @AcpAgent} class, loaded by the application's class loader.
	 * @param className the class's binary name
	 * @return a supplier of the agent class bean
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

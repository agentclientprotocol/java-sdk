/*
 * Copyright 2025-2026 the original author or authors.
 */

package interop.quarkus;

import java.util.List;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.integration.AcpClientCustomizer;
import interop.framework.SmokeClient;
import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The application's main. In the agent builds it waits until the application exits (the agent is
 * served by acp-quarkus; over stdio it exits when its input ends). In the client build
 * ({@code -Dinterop.mode=client}, launch/client.sh) it runs the steps in {@code STEPS} on the
 * {@code AcpAsyncClient} bean acp-quarkus builds from {@code quarkus.acp.client.*}, whose handlers
 * the {@link SmokeClient} customizer bean registers, then exits.
 */
@QuarkusMain
public class Main implements QuarkusApplication {

	@Inject
	Instance<AcpAsyncClient> client;

	@Inject
	Instance<SmokeClient> smokeClient;

	@Override
	public int run(String... args) throws Exception {
		if (!"client".equals(System.getProperty("interop.mode"))) {
			Quarkus.waitForExit();
			return 0;
		}
		List<String> steps = SmokeClient.steps();
		smokeClient.get().run(client.get(), steps);
		return 0;
	}

	/** The shared client steps and the customizer that registers their handlers. */
	@Singleton
	public static class Beans {

		@Produces
		@Singleton
		@Unremovable
		SmokeClient smokeClient() {
			return new SmokeClient();
		}

		@Produces
		@Singleton
		@Unremovable
		AcpClientCustomizer smokeClientHandlers(SmokeClient smokeClient) {
			return smokeClient::customize;
		}

	}

}

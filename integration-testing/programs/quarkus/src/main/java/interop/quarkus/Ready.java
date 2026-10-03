/*
 * Copyright 2025-2026 the original author or authors.
 */

package interop.quarkus;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.ConfigProvider;

/** Prints {@code READY <port>} once the HTTP server accepts connections (the launcher contract). */
@Singleton
public class Ready {

	void ready(@Observes StartupEvent event) {
		int port = ConfigProvider.getConfig().getValue("quarkus.http.port", Integer.class);
		System.out.println("READY " + port);
		System.out.flush();
	}

}

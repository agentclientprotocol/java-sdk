package com.agentclientprotocol.sdk.spring.boot.autoconfigure.client;

import com.agentclientprotocol.sdk.client.AcpClient;

/**
 * Customizes the auto-configured client before it is built: register a session-update
 * consumer, a permission handler, file system or terminal handlers, or anything else the
 * SDK's {@link AcpClient.AsyncSpec} takes. Every {@code AcpClientCustomizer} bean is
 * applied, in order, to the one builder behind both {@code AcpAsyncClient} and
 * {@code AcpSyncClient}.
 */
@FunctionalInterface
public interface AcpClientCustomizer {

	/**
	 * Customizes the client builder.
	 * @param spec the builder of the auto-configured client
	 */
	void customize(AcpClient.AsyncSpec spec);

}

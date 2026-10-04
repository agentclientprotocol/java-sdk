/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.test.http;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * This machine's network addresses, for tests that check what a listener is reachable on.
 *
 * @author Mark Pollack
 */
public final class Networks {

	private Networks() {
	}

	/**
	 * Returns an up, non-loopback, non-link-local address of this machine, IPv4 first.
	 * @return the address, or empty when the machine has none
	 * @throws SocketException if the interfaces cannot be listed
	 */
	public static Optional<InetAddress> nonLoopbackAddress() throws SocketException {
		@Nullable InetAddress ipv6 = null;
		for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
			if (!nic.isUp() || nic.isLoopback()) {
				continue;
			}
			for (InetAddress address : Collections.list(nic.getInetAddresses())) {
				if (address.isLoopbackAddress() || address.isLinkLocalAddress()) {
					continue;
				}
				if (address instanceof Inet4Address) {
					return Optional.of(address);
				}
				if (address instanceof Inet6Address && ipv6 == null) {
					ipv6 = address;
				}
			}
		}
		return Optional.ofNullable(ipv6);
	}

}

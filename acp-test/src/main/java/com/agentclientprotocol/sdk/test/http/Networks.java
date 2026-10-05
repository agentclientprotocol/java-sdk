/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.test.http;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;


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
		List<InetAddress> addresses = new ArrayList<>();
		for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
			if (nic.isUp() && !nic.isLoopback()) {
				addresses.addAll(Collections.list(nic.getInetAddresses()));
			}
		}
		List<InetAddress> routable = addresses.stream()
			.filter(address -> !address.isLoopbackAddress() && !address.isLinkLocalAddress())
			.toList();
		Optional<InetAddress> ipv4 = routable.stream().filter(Inet4Address.class::isInstance).findFirst();
		return ipv4.isPresent() ? ipv4 : routable.stream().filter(Inet6Address.class::isInstance).findFirst();
	}

}

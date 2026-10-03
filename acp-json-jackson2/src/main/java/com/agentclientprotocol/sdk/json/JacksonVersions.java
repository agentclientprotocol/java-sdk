/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

import com.fasterxml.jackson.core.Version;

/**
 * The Jackson 2 versions acp-json-jackson2 supports: {@value #FLOOR} or later, for jackson-core
 * and jackson-databind alike. Frameworks manage their own Jackson version, and an older
 * jackson-core under a newer jackson-databind fails much later with a {@code NoSuchMethodError}
 * while reading a message; checking when the mapper is created names the problem instead.
 */
final class JacksonVersions {

	/**
	 * The oldest Jackson 2 release the SDK's tests pass with. 2.18.0 loses unknown values of the
	 * SDK's open enumerations (stop reasons, plan entry statuses), and 2.17 the unknown fields the
	 * SDK keeps for forward compatibility.
	 */
	static final String FLOOR = "2.18.1";

	private static final Version FLOOR_VERSION = new Version(2, 18, 1, null, "com.fasterxml.jackson.core",
			"jackson-core");

	private JacksonVersions() {
	}

	/**
	 * Fails unless the jackson-core and jackson-databind on the classpath are {@value #FLOOR} or
	 * later.
	 * @throws IllegalStateException naming the version found and the version required
	 */
	static void requireSupported() {
		require("jackson-core", com.fasterxml.jackson.core.json.PackageVersion.VERSION);
		require("jackson-databind", com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION);
	}

	/**
	 * Fails unless {@code found} is {@value #FLOOR} or later.
	 * @param artifact the Jackson artifact the version belongs to
	 * @param found the version on the classpath
	 * @throws IllegalStateException naming the version found and the version required
	 */
	static void require(String artifact, Version found) {
		if (found.getMajorVersion() != FLOOR_VERSION.getMajorVersion()
				|| compare(found, FLOOR_VERSION) < 0) {
			throw new IllegalStateException("acp-json-jackson2 needs Jackson " + FLOOR + " or later, but " + artifact
					+ " " + found.toString() + " is on the classpath (jackson-core "
					+ com.fasterxml.jackson.core.json.PackageVersion.VERSION.toString() + ", jackson-databind "
					+ com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION.toString()
					+ "). Align the Jackson 2 artifacts on one version, " + FLOOR
					+ " or later, for example by importing com.fasterxml.jackson:jackson-bom.");
		}
	}

	/** Compares major, minor and patch level only, ignoring the artifact and any snapshot. */
	private static int compare(Version a, Version b) {
		int major = Integer.compare(a.getMajorVersion(), b.getMajorVersion());
		if (major != 0) {
			return major;
		}
		int minor = Integer.compare(a.getMinorVersion(), b.getMinorVersion());
		return (minor != 0) ? minor : Integer.compare(a.getPatchLevel(), b.getPatchLevel());
	}

}

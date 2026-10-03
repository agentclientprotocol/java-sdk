/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.json.jackson3;

import tools.jackson.core.Version;

/**
 * The Jackson 3 versions acp-json-jackson3 supports: {@value #FLOOR} or later, for jackson-core
 * and jackson-databind alike. Checked when the mapper is created, so a framework that manages an
 * older Jackson fails at once with a message naming the versions, not later while reading a
 * message.
 */
final class Jackson3Versions {

	/** The oldest Jackson 3 release the SDK's tests pass with: the first release of Jackson 3. */
	static final String FLOOR = "3.0.0";

	private static final int FLOOR_MAJOR = 3;

	private static final int FLOOR_MINOR = 0;

	private Jackson3Versions() {
	}

	/**
	 * Fails unless the jackson-core and jackson-databind on the classpath are {@value #FLOOR} or
	 * later.
	 * @throws IllegalStateException naming the version found and the version required
	 */
	static void requireSupported() {
		require("jackson-core", tools.jackson.core.json.PackageVersion.VERSION);
		require("jackson-databind", tools.jackson.databind.cfg.PackageVersion.VERSION);
	}

	/**
	 * Fails unless {@code found} is {@value #FLOOR} or later.
	 * @param artifact the Jackson artifact the version belongs to
	 * @param found the version on the classpath
	 * @throws IllegalStateException naming the version found and the version required
	 */
	static void require(String artifact, Version found) {
		if (found.getMajorVersion() != FLOOR_MAJOR || found.getMinorVersion() < FLOOR_MINOR) {
			throw new IllegalStateException("acp-json-jackson3 needs Jackson " + FLOOR + " or later, but " + artifact
					+ " " + found.toString() + " is on the classpath (jackson-core "
					+ tools.jackson.core.json.PackageVersion.VERSION.toString() + ", jackson-databind "
					+ tools.jackson.databind.cfg.PackageVersion.VERSION.toString()
					+ "). Align the Jackson 3 artifacts on one version, " + FLOOR
					+ " or later, for example by importing tools.jackson:jackson-bom.");
		}
	}

}

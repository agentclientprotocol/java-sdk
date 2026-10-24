/*
 * Copyright 2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.client;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Checks the paths of a session call before the client sends it: ACP v1 requires the working
 * directory and every additional directory to be an absolute path (session setup, "Working
 * Directory" and "Additional Directories").
 *
 * <p>
 * A path is checked by its form, not with {@link java.nio.file.Path#isAbsolute()}: the agent may
 * run on another host and operating system than the client, so a Windows path is absolute on a
 * Linux client and the other way round. Absolute means a POSIX path ({@code /…}), a Windows drive
 * path ({@code C:\…} or {@code C:/…}) or a Windows UNC or device path ({@code \\server\share…}).
 * The path is not resolved and need not exist.
 * </p>
 */
final class SessionPaths {

	private SessionPaths() {
	}

	/**
	 * The error a session call with these paths fails with, or {@code null} when every path is
	 * absolute.
	 * @param method the ACP method, for the message
	 * @param cwd the working directory
	 * @param additionalDirectories the additional directories, or {@code null}
	 * @return the error, or {@code null}
	 */
	static @Nullable IllegalArgumentException check(String method, @Nullable String cwd,
			@Nullable List<String> additionalDirectories) {
		if (!isAbsolute(cwd)) {
			return new IllegalArgumentException(method + ": cwd '" + cwd
					+ "' is not an absolute path (ACP v1, session setup: the cwd MUST be an absolute path)");
		}
		if (additionalDirectories != null) {
			for (String directory : additionalDirectories) {
				if (!isAbsolute(directory)) {
					return new IllegalArgumentException(method + ": additionalDirectories entry '" + directory
							+ "' is not an absolute path (ACP v1, session setup: each entry MUST be an absolute path)");
				}
			}
		}
		return null;
	}

	/**
	 * Whether a path is absolute in POSIX or Windows form.
	 * @param path the path, or {@code null}
	 * @return {@code true} for {@code /…}, {@code X:\…}, {@code X:/…} or {@code \\…}
	 */
	static boolean isAbsolute(@Nullable String path) {
		if (path == null || path.isEmpty()) {
			return false;
		}
		if (path.charAt(0) == '/' || path.startsWith("\\\\")) {
			return true;
		}
		return path.length() >= 3 && isAsciiLetter(path.charAt(0)) && path.charAt(1) == ':'
				&& (path.charAt(2) == '\\' || path.charAt(2) == '/');
	}

	private static boolean isAsciiLetter(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
	}

}

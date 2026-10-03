package com.agentclientprotocol.sdk.spring.boot.autoconfigure;

import java.io.ByteArrayInputStream;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Gives the tests an empty {@code System.in}. Contexts that start the default stdio agent
 * would otherwise leave its reader blocked on the forked JVM's real stdin after the agent
 * closes, where it holds the stream's lock and can swallow surefire's exit
 * acknowledgement, stalling the fork for 30 seconds. Surefire keeps its own reference to
 * the original stream.
 */
public class DetachedStdinListener implements LauncherSessionListener {

	@Override
	public void launcherSessionOpened(LauncherSession session) {
		System.setIn(new ByteArrayInputStream(new byte[0]));
	}

}

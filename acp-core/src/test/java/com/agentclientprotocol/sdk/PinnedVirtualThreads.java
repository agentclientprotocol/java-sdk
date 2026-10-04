/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.agentclientprotocol.sdk.util.VirtualThreads;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs a task on a virtual thread under a JFR recording and returns the
 * {@code jdk.VirtualThreadPinned} events it caused: each is a park of the virtual thread
 * that held its carrier, as a park inside a {@code synchronized} block does on JDK 21 to
 * 23. A park that pins every carrier while the lock it waits for belongs to an unmounted
 * virtual thread deadlocks the scheduler, so code that runs on virtual threads must not
 * park inside a monitor. Skips the calling test where the JDK has no virtual threads.
 */
public final class PinnedVirtualThreads {

	private PinnedVirtualThreads() {
	}

	/**
	 * Runs {@code task} on a virtual thread and returns one line per pinned park, the
	 * event's top frames.
	 * @param task the code under test; it should park (sleep, wait for a lock) where the
	 * code under test may
	 * @return the pinned parks, empty when the task never pinned its carrier
	 * @throws Exception if the task fails or the recording cannot be read
	 */
	public static List<String> pinnedParks(ThrowingRunnable task) throws Exception {
		assumeTrue(VirtualThreads.isSupported(), "virtual threads need JDK 21 or later");
		Path file = Files.createTempFile("pinned", ".jfr");
		try (Recording recording = new Recording()) {
			recording.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ZERO).withStackTrace();
			recording.start();
			ExecutorService executor = VirtualThreads.newPerTaskExecutor("pinning-probe");
			try {
				Future<?> run = executor.submit(() -> {
					task.run();
					return null;
				});
				run.get(30, TimeUnit.SECONDS);
			}
			finally {
				executor.shutdownNow();
			}
			recording.stop();
			recording.dump(file);
			return RecordingFile.readAllEvents(file)
				.stream()
				.filter(event -> event.getEventType().getName().equals("jdk.VirtualThreadPinned"))
				.map(PinnedVirtualThreads::describe)
				.collect(Collectors.toList());
		}
		finally {
			Files.deleteIfExists(file);
		}
	}

	private static String describe(RecordedEvent event) {
		if (event.getStackTrace() == null) {
			return "pinned park (no stack)";
		}
		return event.getStackTrace()
			.getFrames()
			.stream()
			.limit(12)
			.map(PinnedVirtualThreads::frame)
			.collect(Collectors.joining(" <- "));
	}

	private static String frame(RecordedFrame frame) {
		return frame.getMethod().getType().getName() + "." + frame.getMethod().getName();
	}

	/** A task that may throw. */
	@FunctionalInterface
	public interface ThrowingRunnable {

		/**
		 * Runs the task.
		 * @throws Exception on failure
		 */
		void run() throws Exception;

	}

}

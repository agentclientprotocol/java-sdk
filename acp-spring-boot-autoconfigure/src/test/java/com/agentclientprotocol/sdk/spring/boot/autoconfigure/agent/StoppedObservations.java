/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;

/**
 * Records the observations that have stopped, so a test can wait for the one it is about. A
 * server observation stops after the response has reached the client, and the test's server may
 * observe requests other than the test's own, so neither the first observation nor its state
 * when the response arrives can be asserted.
 */
final class StoppedObservations implements ObservationHandler<Observation.Context> {

	private final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();

	@Override
	public boolean supportsContext(Observation.Context context) {
		return true;
	}

	@Override
	public void onStop(Observation.Context context) {
		stopped.add(context);
	}

	/**
	 * Waits for a stopped observation of the given name whose key values, low or high
	 * cardinality, include all those given.
	 */
	Observation.Context await(String name, Duration timeout, KeyValue... keyValues) throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (true) {
			Optional<Observation.Context> match = stopped.stream()
				.filter(context -> name.equals(context.getName()))
				.filter(context -> {
					for (KeyValue keyValue : keyValues) {
						KeyValue actual = context.getLowCardinalityKeyValue(keyValue.getKey());
						if (actual == null) {
							actual = context.getHighCardinalityKeyValue(keyValue.getKey());
						}
						if (actual == null || !keyValue.getValue().equals(actual.getValue())) {
							return false;
						}
					}
					return true;
				})
				.findFirst();
			if (match.isPresent()) {
				return match.get();
			}
			if (System.nanoTime() > deadline) {
				throw new AssertionError("No stopped " + name + " observation with " + List.of(keyValues) + " within "
						+ timeout + "; stopped: "
						+ stopped.stream().map(context -> context.getName() + context.getLowCardinalityKeyValues() + context.getHighCardinalityKeyValues()).toList());
			}
			Thread.sleep(10);
		}
	}

}

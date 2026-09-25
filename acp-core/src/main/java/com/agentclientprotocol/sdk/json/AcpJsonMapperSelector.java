/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/**
 * The selection rule behind {@link AcpJsonMapper#createDefault()}, kept out of the
 * interface so it can be tested with suppliers that are not registered as services.
 *
 * @author Mark Pollack
 */
final class AcpJsonMapperSelector {

	/**
	 * System property naming the fully qualified class of the supplier to use, which
	 * overrides {@link AcpJsonMapperSupplier#priority()}.
	 */
	static final String SUPPLIER_PROPERTY = "acp.json.mapper.supplier";

	private static final Comparator<AcpJsonMapperSupplier> ORDER = Comparator
		.comparingInt(AcpJsonMapperSupplier::priority)
		.reversed()
		.thenComparing(supplier -> supplier.getClass().getName());

	private AcpJsonMapperSelector() {
	}

	static AcpJsonMapperSupplier select() {
		return select(ServiceLoader.load(AcpJsonMapperSupplier.class), System.getProperty(SUPPLIER_PROPERTY));
	}

	/**
	 * Picks one supplier: the one whose class is named by {@code override} if that is
	 * set, otherwise the highest priority, ties broken by class name so the outcome
	 * never depends on classpath order.
	 * @param candidates the discovered suppliers
	 * @param override the class name from {@value #SUPPLIER_PROPERTY}, or {@code null}
	 * @return the chosen supplier
	 * @throws ServiceConfigurationError if there is no candidate, or none matches the
	 * override
	 */
	static AcpJsonMapperSupplier select(Iterable<? extends AcpJsonMapperSupplier> candidates, String override) {
		List<AcpJsonMapperSupplier> all = new ArrayList<>();
		candidates.forEach(all::add);
		if (all.isEmpty()) {
			throw new ServiceConfigurationError("No AcpJsonMapperSupplier found on the classpath; add a JSON module "
					+ "such as com.agentclientprotocol:acp-json-jackson2 or acp-json-jackson3");
		}
		if (override != null && !override.isBlank()) {
			String wanted = override.strip();
			return all.stream()
				.filter(supplier -> supplier.getClass().getName().equals(wanted))
				.findFirst()
				.orElseThrow(() -> new ServiceConfigurationError("System property " + SUPPLIER_PROPERTY + "="
						+ wanted + " names no AcpJsonMapperSupplier on the classpath; found "
						+ all.stream().map(s -> s.getClass().getName()).collect(Collectors.joining(", "))));
		}
		all.sort(ORDER);
		return all.get(0);
	}

}

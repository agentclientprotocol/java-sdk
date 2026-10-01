/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.json;

import java.util.List;
import java.util.ServiceConfigurationError;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rule {@link AcpJsonMapper#createDefault()} uses to choose between several
 * suppliers: explicit override, then highest priority, then class name; never classpath
 * order.
 */
class AcpJsonMapperSelectorTest {

	static class Low implements AcpJsonMapperSupplier {

		@Override
		public int priority() {
			return -200;
		}

		@Override
		public AcpJsonMapper get() {
			throw new UnsupportedOperationException();
		}

	}

	static class High implements AcpJsonMapperSupplier {

		@Override
		public int priority() {
			return -100;
		}

		@Override
		public AcpJsonMapper get() {
			throw new UnsupportedOperationException();
		}

	}

	static class AppA implements AcpJsonMapperSupplier {

		@Override
		public AcpJsonMapper get() {
			throw new UnsupportedOperationException();
		}

	}

	static class AppB implements AcpJsonMapperSupplier {

		@Override
		public AcpJsonMapper get() {
			throw new UnsupportedOperationException();
		}

	}

	@Test
	void highestPriorityWinsWhateverTheDiscoveryOrder() {
		assertThat(AcpJsonMapperSelector.select(List.of(new Low(), new High()), null)).isInstanceOf(High.class);
		assertThat(AcpJsonMapperSelector.select(List.of(new High(), new Low()), null)).isInstanceOf(High.class);
	}

	@Test
	void applicationSupplierWithDefaultPriorityBeatsTheSdkModules() {
		assertThat(new AppA().priority()).isEqualTo(AcpJsonMapperSupplier.DEFAULT_PRIORITY);
		assertThat(AcpJsonMapperSelector.select(List.of(new Low(), new AppA(), new High()), null))
			.isInstanceOf(AppA.class);
	}

	@Test
	void equalPrioritiesAreBrokenByClassName() {
		assertThat(AcpJsonMapperSelector.select(List.of(new AppB(), new AppA()), null)).isInstanceOf(AppA.class);
		assertThat(AcpJsonMapperSelector.select(List.of(new AppA(), new AppB()), null)).isInstanceOf(AppA.class);
	}

	@Test
	void systemPropertyOverridesPriority() {
		assertThat(AcpJsonMapperSelector.select(List.of(new High(), new Low()), Low.class.getName()))
			.isInstanceOf(Low.class);
		assertThat(AcpJsonMapperSelector.select(List.of(new High(), new Low()), " " + Low.class.getName() + " "))
			.as("surrounding whitespace is ignored")
			.isInstanceOf(Low.class);
	}

	@Test
	void blankSystemPropertyIsIgnored() {
		assertThat(AcpJsonMapperSelector.select(List.of(new High(), new Low()), " ")).isInstanceOf(High.class);
	}

	@Test
	void systemPropertyNamingAnAbsentSupplierFails() {
		assertThatThrownBy(() -> AcpJsonMapperSelector.select(List.of(new High()), "com.example.Missing"))
			.isInstanceOf(ServiceConfigurationError.class)
			.hasMessageContaining(AcpJsonMapperSelector.SUPPLIER_PROPERTY)
			.hasMessageContaining("com.example.Missing")
			.hasMessageContaining(High.class.getName());
	}

	@Test
	void noSupplierNamesTheJsonModules() {
		assertThatThrownBy(() -> AcpJsonMapperSelector.select(List.of(), null))
			.isInstanceOf(ServiceConfigurationError.class)
			.hasMessageContaining("acp-json-jackson2");
	}

}

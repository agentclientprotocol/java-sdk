/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import com.agentclientprotocol.sdk.spring.boot.autoconfigure.inprocess.ClaimsAgent;
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @AcpAgent} alone makes a class of the application's packages a bean, once, and only while
 * the agent is enabled.
 */
class AcpAgentScanAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(AcpAgentScanAutoConfiguration.class));

	@Test
	void anAcpAgentClassOfTheApplicationsPackagesIsABean() {
		this.runner.withUserConfiguration(ClaimsPackage.class).run(context -> {
			assertThat(context).hasSingleBean(ClaimsAgent.class);
			assertThat(context).hasBean("claimsAgent");
		});
	}

	@Test
	void aClassAnBeanMethodReturnsIsNotRegisteredTwice() {
		this.runner.withUserConfiguration(ClaimsPackage.class, DeclaredAgent.class)
			.run(context -> assertThat(context).hasSingleBean(ClaimsAgent.class).hasBean("declaredClaimsAgent"));
	}

	@Test
	void nothingIsScannedWithoutAutoConfigurationPackagesOrWhenDisabled() {
		this.runner.run(context -> assertThat(context).doesNotHaveBean(ClaimsAgent.class));
		this.runner.withUserConfiguration(ClaimsPackage.class)
			.withPropertyValues("spring.acp.agent.enabled=false")
			.run(context -> assertThat(context).doesNotHaveBean(ClaimsAgent.class));
	}

	@Configuration(proxyBeanMethods = false)
	@AutoConfigurationPackage(basePackageClasses = ClaimsAgent.class)
	static class ClaimsPackage {

	}

	@Configuration(proxyBeanMethods = false)
	static class DeclaredAgent {

		@Bean
		ClaimsAgent declaredClaimsAgent() {
			return new ClaimsAgent();
		}

	}

}

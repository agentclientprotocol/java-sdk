/*
 * Copyright 2026-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.agentclientprotocol.sdk.annotation.AcpAgent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * Makes a class annotated {@code @AcpAgent} a bean, without {@code @Component}: it scans the
 * application's packages (those of its {@code @SpringBootApplication}, the auto-configuration
 * packages) for {@code @AcpAgent} classes, as Spring Data finds repositories, and registers each as
 * a singleton bean, constructed with its dependencies injected. The {@code acp-annotations} module
 * keeps no Spring dependency for it.
 *
 * <p>A class that is already a bean, because it is also a {@code @Component} or an {@code @Bean}
 * method returns it, is not registered twice. An {@code @AcpAgent} class outside the scanned
 * packages is not found; import it, or declare it as a bean. A bean that implements
 * {@code AutoCloseable} is closed when the context closes, after the agent has stopped, as Spring
 * closes any such bean. Nothing is scanned with {@code spring.acp.agent.enabled=false}.
 */
@AutoConfiguration(before = { AcpAgentTransportAutoConfiguration.class, AcpAgentAutoConfiguration.class },
		beforeName = "com.agentclientprotocol.sdk.spring.boot.autoconfigure.agent.AcpAgentHttpAutoConfiguration")
@ConditionalOnClass(AcpAgent.class)
@ConditionalOnProperty(prefix = "spring.acp.agent", name = "enabled", havingValue = "true", matchIfMissing = true)
@Import(AcpAgentScanAutoConfiguration.Registrar.class)
public class AcpAgentScanAutoConfiguration {

	/** Registers the {@code @AcpAgent} classes of the auto-configuration packages. */
	static class Registrar implements ImportBeanDefinitionRegistrar, BeanFactoryAware, EnvironmentAware,
			ResourceLoaderAware {

		private static final Logger logger = LoggerFactory.getLogger(Registrar.class);

		private @Nullable BeanFactory beanFactory;

		private @Nullable Environment environment;

		private @Nullable ResourceLoader resourceLoader;

		@Override
		public void setBeanFactory(BeanFactory beanFactory) {
			this.beanFactory = beanFactory;
		}

		@Override
		public void setEnvironment(Environment environment) {
			this.environment = environment;
		}

		@Override
		public void setResourceLoader(ResourceLoader resourceLoader) {
			this.resourceLoader = resourceLoader;
		}

		@Override
		public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
			BeanFactory factory = this.beanFactory;
			Environment env = this.environment;
			if (factory == null || env == null || !AutoConfigurationPackages.has(factory)) {
				logger.debug("No auto-configuration packages; no @AcpAgent classes are scanned");
				return;
			}
			ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false,
					env);
			if (this.resourceLoader != null) {
				scanner.setResourceLoader(this.resourceLoader);
			}
			scanner.addIncludeFilter(new AnnotationTypeFilter(AcpAgent.class));
			Set<String> defined = definedClasses(registry);
			List<String> packages = AutoConfigurationPackages.get(factory);
			for (String packageName : packages) {
				for (BeanDefinition candidate : scanner.findCandidateComponents(packageName)) {
					register(candidate, defined, registry);
				}
			}
		}

		private static void register(BeanDefinition candidate, Set<String> defined, BeanDefinitionRegistry registry) {
			String className = candidate.getBeanClassName();
			if (className == null || !defined.add(className)) {
				return;
			}
			if (candidate instanceof AnnotatedBeanDefinition annotated) {
				AnnotationConfigUtils.processCommonDefinitionAnnotations(annotated);
			}
			String name = AnnotationBeanNameGenerator.INSTANCE.generateBeanName(candidate, registry);
			if (!registry.containsBeanDefinition(name)) {
				logger.debug("Registering @AcpAgent class {} as bean '{}'", className, name);
				registry.registerBeanDefinition(name, candidate);
			}
		}

		/** The classes already defined as beans: by class, or as an {@code @Bean} method's type. */
		private static Set<String> definedClasses(BeanDefinitionRegistry registry) {
			Set<String> classes = new HashSet<>();
			for (String name : registry.getBeanDefinitionNames()) {
				BeanDefinition definition = registry.getBeanDefinition(name);
				if (definition.getBeanClassName() != null) {
					classes.add(definition.getBeanClassName());
				}
				if (definition instanceof AnnotatedBeanDefinition annotated) {
					MethodMetadata method = annotated.getFactoryMethodMetadata();
					if (method != null) {
						classes.add(method.getReturnTypeName());
					}
				}
			}
			return classes;
		}

	}

}

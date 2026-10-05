/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.deployment;

import java.util.List;
import java.util.Optional;

import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.integration.AcpAgentDiscovery;
import com.agentclientprotocol.sdk.integration.AcpTransportType;
import com.agentclientprotocol.sdk.quarkus.AcpBuildTimeConfig;
import com.agentclientprotocol.sdk.quarkus.runtime.AcpAgentAssembly;
import com.agentclientprotocol.sdk.quarkus.runtime.AcpAgentClass;
import com.agentclientprotocol.sdk.quarkus.runtime.AcpClientProducers;
import com.agentclientprotocol.sdk.quarkus.runtime.AcpExecutors;
import com.agentclientprotocol.sdk.quarkus.runtime.AcpHttpAgentHost;
import com.agentclientprotocol.sdk.quarkus.runtime.AcpHttpConfigBuilder;
import com.agentclientprotocol.sdk.quarkus.runtime.AcpRecorder;
import com.agentclientprotocol.sdk.quarkus.runtime.AcpStdioAgentHost;
import com.agentclientprotocol.sdk.quarkus.runtime.AcpStdioConfigBuilder;
import com.agentclientprotocol.sdk.quarkus.runtime.AcpStdioTransportProducer;
import com.agentclientprotocol.sdk.quarkus.runtime.AcpVertxHost;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.BeanDefiningAnnotationBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.arc.processor.BuiltinScope;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.RunTimeConfigBuilderBuildItem;
import io.quarkus.deployment.builditem.StaticInitConfigBuilderBuildItem;
import jakarta.inject.Singleton;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.DotName;

/**
 * Build steps of the ACP extension. The {@code @AcpAgent} class is found in the index
 * here, at build time: it becomes a singleton bean (no client proxy), and a second one
 * fails the build with both names. With one, the agent host for the configured
 * transport is added; with none, the application is a client only.
 *
 * @author Mark Pollack
 */
class AcpProcessor {

	static final String FEATURE = "acp";

	static final DotName ACP_AGENT = DotName.createSimple(AcpAgent.class);

	@BuildStep
	FeatureBuildItem feature() {
		return new FeatureBuildItem(FEATURE);
	}

	/**
	 * The client beans, each created only when the application injects it, and the executor
	 * they and the agent run on.
	 */
	@BuildStep
	void clientBeans(BuildProducer<AdditionalBeanBuildItem> beans) {
		beans.produce(new AdditionalBeanBuildItem(AcpClientProducers.class));
		// The agent looks it up when it builds a handler chain, which Arc does not see.
		beans.produce(AdditionalBeanBuildItem.unremovableOf(AcpExecutors.class));
	}

	/** {@code @AcpAgent} alone makes a class a bean, a singleton unless it declares a scope. */
	@BuildStep
	BeanDefiningAnnotationBuildItem agentIsBean() {
		return new BeanDefiningAnnotationBuildItem(ACP_AGENT, BuiltinScope.SINGLETON.getName());
	}

	@BuildStep
	void findAgent(CombinedIndexBuildItem index, AcpBuildTimeConfig config, BuildProducer<AcpAgentBuildItem> agent) {
		if (!config.agent().enabled()) {
			return;
		}
		List<String> agents = index.getIndex()
			.getAnnotations(ACP_AGENT)
			.stream()
			.map(AnnotationInstance::target)
			.filter(target -> target.kind() == AnnotationTarget.Kind.CLASS)
			.map(target -> target.asClass().name().toString())
			.toList();
		AcpAgentDiscovery.requireSingle(agents, "quarkus.acp.agent.enabled")
			.ifPresent(name -> agent.produce(new AcpAgentBuildItem(DotName.createSimple(name))));
	}

	/** The {@code @AcpAgent} class as a bean the runtime reads, and the agent bean kept. */
	@BuildStep
	@Record(ExecutionTime.STATIC_INIT)
	void agentClass(Optional<AcpAgentBuildItem> agent, AcpRecorder recorder,
			BuildProducer<SyntheticBeanBuildItem> syntheticBeans, BuildProducer<UnremovableBeanBuildItem> unremovable) {
		if (agent.isEmpty()) {
			return;
		}
		String agentClass = agent.get().agentClass().toString();
		syntheticBeans.produce(SyntheticBeanBuildItem.configure(AcpAgentClass.class)
			.scope(Singleton.class)
			.supplier(recorder.agentClass(agentClass))
			.done());
		unremovable.produce(UnremovableBeanBuildItem.beanClassNames(agentClass));
	}

	/** The host for the configured transport, and the Quarkus defaults it needs. */
	@BuildStep
	void agentHost(Optional<AcpAgentBuildItem> agent, AcpBuildTimeConfig config,
			BuildProducer<AdditionalBeanBuildItem> beans,
			BuildProducer<StaticInitConfigBuilderBuildItem> staticInitConfig,
			BuildProducer<RunTimeConfigBuilderBuildItem> runTimeConfig) {
		if (agent.isEmpty()) {
			return;
		}
		beans.produce(AdditionalBeanBuildItem.unremovableOf(AcpAgentAssembly.class));
		if (config.agent().transport().type() == AcpTransportType.STDIO) {
			beans.produce(AdditionalBeanBuildItem.unremovableOf(AcpStdioAgentHost.class));
			beans.produce(AdditionalBeanBuildItem.unremovableOf(AcpStdioTransportProducer.class));
			// Standard output carries the protocol: nothing else may write there, and a
			// stdio agent listens on no port.
			staticInitConfig.produce(new StaticInitConfigBuilderBuildItem(AcpStdioConfigBuilder.class));
			runTimeConfig.produce(new RunTimeConfigBuilderBuildItem(AcpStdioConfigBuilder.class));
		}
		else {
			beans.produce(AdditionalBeanBuildItem.builder()
				.addBeanClasses(AcpVertxHost.class, AcpHttpAgentHost.class)
				.setUnremovable()
				.build());
			runTimeConfig.produce(new RunTimeConfigBuilderBuildItem(AcpHttpConfigBuilder.class));
		}
	}

}

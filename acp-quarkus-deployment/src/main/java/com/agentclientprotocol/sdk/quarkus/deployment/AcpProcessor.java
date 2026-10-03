/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.quarkus.deployment;

import java.util.List;
import java.util.Optional;

import com.agentclientprotocol.sdk.annotation.AcpAgent;
import com.agentclientprotocol.sdk.quarkus.AcpBuildTimeConfig;
import com.agentclientprotocol.sdk.quarkus.AgentTransportType;
import com.agentclientprotocol.sdk.quarkus.runtime.AcpAgentAssembly;
import com.agentclientprotocol.sdk.quarkus.runtime.AcpAgentClass;
import com.agentclientprotocol.sdk.quarkus.runtime.AcpRecorder;
import com.agentclientprotocol.sdk.quarkus.runtime.AcpStdioAgentHost;
import com.agentclientprotocol.sdk.quarkus.runtime.AcpStdioConfigBuilder;
import com.agentclientprotocol.sdk.quarkus.runtime.AcpStdioTransportProducer;
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
		List<DotName> agents = index.getIndex()
			.getAnnotations(ACP_AGENT)
			.stream()
			.map(AnnotationInstance::target)
			.filter(target -> target.kind() == AnnotationTarget.Kind.CLASS)
			.map(target -> target.asClass().name())
			.sorted()
			.toList();
		if (agents.size() > 1) {
			throw new IllegalStateException("Found " + agents.size() + " @AcpAgent classes " + agents
					+ ", but an application serves one. Remove @AcpAgent from all but one, or set "
					+ "quarkus.acp.agent.enabled=false to serve none.");
		}
		if (agents.size() == 1) {
			agent.produce(new AcpAgentBuildItem(agents.get(0)));
		}
	}

	@BuildStep
	@Record(ExecutionTime.STATIC_INIT)
	void agentBeans(Optional<AcpAgentBuildItem> agent, AcpBuildTimeConfig config, AcpRecorder recorder,
			BuildProducer<AdditionalBeanBuildItem> beans, BuildProducer<SyntheticBeanBuildItem> syntheticBeans,
			BuildProducer<UnremovableBeanBuildItem> unremovable,
			BuildProducer<StaticInitConfigBuilderBuildItem> staticInitConfig,
			BuildProducer<RunTimeConfigBuilderBuildItem> runTimeConfig) {
		if (agent.isEmpty()) {
			return;
		}
		String agentClass = agent.get().agentClass().toString();
		syntheticBeans.produce(SyntheticBeanBuildItem.configure(AcpAgentClass.class)
			.scope(Singleton.class)
			.supplier(recorder.agentClass(agentClass))
			.done());
		unremovable.produce(UnremovableBeanBuildItem.beanClassNames(agentClass));
		beans.produce(AdditionalBeanBuildItem.unremovableOf(AcpAgentAssembly.class));
		if (config.agent().transport().type() == AgentTransportType.STDIO) {
			beans.produce(AdditionalBeanBuildItem.unremovableOf(AcpStdioAgentHost.class));
			beans.produce(AdditionalBeanBuildItem.unremovableOf(AcpStdioTransportProducer.class));
			// Standard output carries the protocol: nothing else may write there, and a
			// stdio agent listens on no port.
			staticInitConfig.produce(new StaticInitConfigBuilderBuildItem(AcpStdioConfigBuilder.class));
			runTimeConfig.produce(new RunTimeConfigBuilderBuildItem(AcpStdioConfigBuilder.class));
		}
	}

}

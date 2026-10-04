package interop.spring.agent;

import com.agentclientprotocol.sdk.annotation.AcpAgent;
import interop.framework.SmokeAgent;
import org.springframework.stereotype.Component;

/** The shared smoke agent as a Spring bean: acp-spring-boot-starter finds it by its annotation. */
@Component
@AcpAgent(name = "interop-spring-agent", version = "1")
public class SpringSmokeAgent extends SmokeAgent {

}

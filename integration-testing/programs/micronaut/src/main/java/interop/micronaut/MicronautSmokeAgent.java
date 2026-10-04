package interop.micronaut;

import com.agentclientprotocol.sdk.annotation.AcpAgent;
import interop.framework.SmokeAgent;
import jakarta.inject.Singleton;

/** The shared smoke agent as a Micronaut singleton: acp-micronaut finds it by its annotation. */
@Singleton
@AcpAgent(name = "interop-micronaut-agent", version = "1")
public class MicronautSmokeAgent extends SmokeAgent {

}

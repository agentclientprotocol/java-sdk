/*
 * Scenario configuration (configs/<scenario>.json) and ${VAR} substitution.
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Scenario {

	static final ObjectMapper MAPPER = new ObjectMapper()
		.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

	/** One scenario: processes to run (servers first) and what must hold afterwards. */
	@JsonIgnoreProperties({ "comment" })
	public record Config(String name, String description, Integer timeoutSec, List<String> peers,
			List<ProcessSpec> processes, Assertions assertions) {

		public Config {
			peers = peers == null ? List.of() : peers;
			assertions = assertions == null ? new Assertions(null, null, null, null, null) : assertions;
			timeoutSec = timeoutSec == null ? 300 : timeoutSec;
		}

	}

	/**
	 * A process. {@code role} is {@code server} (started first, waited for with {@code ready},
	 * killed at teardown) or {@code client} (run to completion within {@code timeoutSec}, must
	 * exit with {@code exitCode}, default 0). {@code samples} (servers only) take jcmd samples of
	 * the process at named moments.
	 */
	@JsonIgnoreProperties({ "comment" })
	public record ProcessSpec(String name, String language, String role, String dir, String build, String run,
			Map<String, String> env, Ready ready, Integer timeoutSec, Integer exitCode, List<SampleSpec> samples) {

		public boolean isServer() {
			return "server".equals(role);
		}

		public ProcessSpec {
			env = env == null ? Map.of() : env;
			samples = samples == null ? List.of() : samples;
			timeoutSec = timeoutSec == null ? 120 : timeoutSec;
			exitCode = exitCode == null ? 0 : exitCode;
		}

	}

	/** Wait until a line containing {@code line} appears on the process's output. */
	public record Ready(String line, Integer timeoutSec) {

		public Ready {
			timeoutSec = timeoutSec == null ? 60 : timeoutSec;
		}

	}

	/**
	 * Take a sample labelled {@code label} when {@code on} happens: {@code ready} (this server
	 * became ready), {@code line} (process {@code process} printed a line containing
	 * {@code line}) or {@code exit} (process {@code process} exited), after {@code delayMs}.
	 */
	@JsonIgnoreProperties({ "comment" })
	public record SampleSpec(String label, String on, String process, String line, Long delayMs) {

		public SampleSpec {
			delayMs = delayMs == null ? 0L : delayMs;
		}

	}

	/**
	 * Deterministic assertions. Maps are keyed by process name. {@code checks} are
	 * {@code "<process> RESULT <lhs> <op> <rhs>"} or
	 * {@code "<process> SAMPLE <label> <lhs> <op> <rhs>"}; a term is a number, a key, or
	 * {@code <number>*<key>}.
	 */
	@JsonIgnoreProperties({ "comment" })
	public record Assertions(Map<String, List<String>> requiredOutput, Map<String, List<String>> forbiddenOutput,
			Map<String, List<String>> requiredPatterns, List<String> checks, List<ExpectedFailure> expectedFailures) {

		public Assertions {
			requiredOutput = requiredOutput == null ? Map.of() : requiredOutput;
			forbiddenOutput = forbiddenOutput == null ? Map.of() : forbiddenOutput;
			requiredPatterns = requiredPatterns == null ? Map.of() : requiredPatterns;
			checks = checks == null ? List.of() : checks;
			expectedFailures = expectedFailures == null ? List.of() : expectedFailures;
		}

	}

	/**
	 * A step that is known to fail: {@code STEP <step> FAIL} must appear on {@code process}'s
	 * output and contain {@code contains}. If the step passes instead, the scenario fails, so the
	 * expectation flips when the peer changes.
	 */
	public record ExpectedFailure(String process, String step, String contains, String reason, String see) {
	}

	public static Config load(Path file) throws Exception {
		if (!Files.exists(file)) {
			throw new IllegalArgumentException("Config not found: " + file);
		}
		return MAPPER.readValue(file.toFile(), Config.class);
	}

	private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z0-9_.:-]+)}");

	/** Replace every ${NAME}; an unknown name is an error, not an empty string. */
	public static String subst(String s, Map<String, String> vars) {
		if (s == null) {
			return null;
		}
		Matcher m = VAR.matcher(s);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			String v = vars.get(m.group(1));
			if (v == null) {
				throw new IllegalArgumentException("Unknown variable ${" + m.group(1) + "} in: " + s
						+ " (known: " + vars.keySet() + ")");
			}
			m.appendReplacement(sb, Matcher.quoteReplacement(v));
		}
		m.appendTail(sb);
		return sb.toString();
	}

}

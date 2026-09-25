///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.zeroturnaround:zt-exec:1.12
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.1
//DEPS org.slf4j:slf4j-nop:1.7.36
//JAVA 17+
//SOURCES jbang-lib/Scenario.java
//SOURCES jbang-lib/Peers.java
//SOURCES jbang-lib/Proc.java
//SOURCES jbang-lib/Sampler.java
//SOURCES jbang-lib/Checks.java

import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs one cross-SDK or load scenario from configs/&lt;scenario&gt;.json.
 *
 * <pre>
 *   cd integration-testing
 *   jbang RunScenario.java interop-ts-server
 *   jbang RunScenario.java interop-ts-server --peer typescript-sdk=v0.5.0
 *   jbang RunScenario.java load-300 --skip-sdk-install
 *   jbang RunScenario.java --list
 * </pre>
 *
 * Flow: install the SDK from this checkout (unless --skip-sdk-install), prepare the peer SDKs
 * the scenario names, build every process, start the servers in order and wait for their ready
 * line, run the clients in order under their timeouts, take the configured jcmd samples, then
 * tear down every process tree (always, in a finally) and evaluate the assertions. Logs go to
 * logs/&lt;scenario&gt;/. Exit 0 = pass (declared expected failures included), 1 = fail,
 * 2 = the scenario could not be set up.
 */
public class RunScenario {

	public static void main(String... args) throws Exception {
		String scenario = null;
		boolean skipInstall = false;
		String peersRef = null;
		Map<String, String> peerRefs = new HashMap<>();
		for (int i = 0; i < args.length; i++) {
			String a = args[i];
			switch (a) {
				case "-h", "--help" -> {
					usage();
					return;
				}
				case "--list" -> {
					list();
					return;
				}
				case "--skip-sdk-install" -> skipInstall = true;
				case "--peers-ref" -> peersRef = args[++i];
				case "--peer" -> {
					String[] kv = args[++i].split("=", 2);
					if (kv.length != 2) {
						throw new IllegalArgumentException("--peer expects <name>=<ref>, got " + args[i]);
					}
					peerRefs.put(kv[0], kv[1]);
				}
				default -> {
					if (a.startsWith("--peers-ref=")) {
						peersRef = a.substring("--peers-ref=".length());
					}
					else if (a.startsWith("--peer=")) {
						String[] kv = a.substring("--peer=".length()).split("=", 2);
						peerRefs.put(kv[0], kv[1]);
					}
					else if (a.startsWith("-")) {
						throw new IllegalArgumentException("Unknown option " + a);
					}
					else {
						scenario = a;
					}
				}
			}
		}
		if (scenario == null) {
			usage();
			System.exit(2);
		}
		System.exit(new RunScenario(root(), scenario).run(skipInstall, peersRef, peerRefs));
	}

	static void usage() {
		System.out.println("""
				Usage: jbang RunScenario.java <scenario> [options]

				Options:
				  --peer <name>=<ref>   test peer SDK <name> (see peers.json) at <ref> (branch, tag or SHA)
				  --peers-ref <ref>     test every peer SDK at <ref> (a --peer entry wins)
				  --skip-sdk-install    do not run ./mvnw install first (run-all.sh installs once)
				  --list                list scenarios
				""");
	}

	static void list() throws Exception {
		try (Stream<Path> s = Files.list(root().resolve("configs"))) {
			s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).sorted()
				.forEach(n -> System.out.println("  " + n.substring(0, n.length() - 5)));
		}
	}

	static Path root() {
		Path cwd = Path.of("").toAbsolutePath();
		for (Path p : List.of(cwd, cwd.resolve("integration-testing"))) {
			if (Files.exists(p.resolve("RunScenario.java")) && Files.isDirectory(p.resolve("configs"))) {
				return p;
			}
		}
		throw new IllegalStateException("Run from integration-testing/ or the repository root");
	}

	final Path root;

	final Path repo;

	final String scenario;

	final Path logDir;

	final Map<String, Proc> procs = new LinkedHashMap<>();

	final List<CompletableFuture<Void>> pendingSamples = new ArrayList<>();

	final Checks.Outcome outcome = new Checks.Outcome();

	final List<String> notes = new ArrayList<>();

	RunScenario(Path root, String scenario) {
		this.root = root;
		this.repo = root.getParent();
		this.scenario = scenario;
		this.logDir = root.resolve("logs").resolve(scenario);
	}

	int run(boolean skipInstall, String peersRef, Map<String, String> peerRefs) throws Exception {
		long t0 = System.nanoTime();
		Scenario.Config cfg = Scenario.load(root.resolve("configs").resolve(scenario + ".json"));
		resetLogDir();
		banner("Scenario " + scenario + (cfg.description() == null ? "" : ": " + cfg.description()));
		Map<String, String> vars = new LinkedHashMap<>();
		List<String> peerSummary = new ArrayList<>();
		try {
			String acpVersion = sdkVersion();
			if (!skipInstall) {
				step("Installing the SDK from " + repo + " (" + acpVersion + ")");
				exec(logDir.resolve("sdk-install.log"), repo, 900, "./mvnw", "-q", "-B", "-DskipTests", "install");
			}
			vars.put("ROOT", root.toString());
			vars.put("REPO", repo.toString());
			vars.put("MVNW", repo.resolve("mvnw").toString());
			vars.put("ACP_VERSION", acpVersion);
			vars.put("CACHE", root.resolve(".cache").toString());
			vars.put("LOG_DIR", logDir.toString());
			vars.put("PORT", Integer.toString(freePort()));
			if (!cfg.peers().isEmpty()) {
				step("Preparing peer SDKs");
				Map<String, Peers.Peer> known = Peers.load(root.resolve("peers.json"));
				for (String name : cfg.peers()) {
					Peers.Peer peer = known.get(name);
					if (peer == null) {
						throw new IllegalArgumentException("Peer '" + name + "' is not in peers.json");
					}
					String ref = peerRefs.getOrDefault(name, peersRef != null ? peersRef : peer.ref());
					Peers.Checkout c = Peers.prepare(name, peer, ref, root.resolve(".cache"), logDir);
					vars.put("peer." + name, c.dir().toString());
					vars.put("peerRef." + name, ref);
					vars.put("peerKey." + name, c.dir().getFileName().toString());
					peerSummary.add(name + "@" + ref + " (" + c.sha().substring(0, 12) + ")");
				}
			}
			for (String name : peerRefs.keySet()) {
				if (!cfg.peers().contains(name)) {
					System.out.println("  note: --peer " + name + " ignored; this scenario does not use it");
				}
			}
			build(cfg, vars);
		}
		catch (Exception e) {
			outcome.failures.add("setup: " + e.getMessage());
			return finish(cfg, t0, peerSummary, 2);
		}

		Thread hook = new Thread(this::killAll, "teardown");
		Runtime.getRuntime().addShutdownHook(hook);
		try {
			execute(cfg, vars);
		}
		catch (Exception e) {
			outcome.failures.add("run: " + e);
		}
		finally {
			killAll();
			Runtime.getRuntime().removeShutdownHook(hook);
		}
		Map<String, List<String>> output = new LinkedHashMap<>();
		procs.forEach((n, p) -> output.put(n, p.lines()));
		Checks.evaluate(cfg.assertions(), output, outcome);
		return finish(cfg, t0, peerSummary, outcome.failures.isEmpty() ? 0 : 1);
	}

	void build(Scenario.Config cfg, Map<String, String> vars) throws Exception {
		Set<String> done = new LinkedHashSet<>();
		for (Scenario.ProcessSpec p : cfg.processes()) {
			if (p.build() == null || p.build().isBlank()) {
				continue;
			}
			Path dir = Path.of(Scenario.subst(p.dir(), vars));
			String cmd = Scenario.subst(p.build(), vars);
			if (done.add(dir + "\0" + cmd)) {
				step("Building " + p.name() + " (" + p.language() + ")");
				exec(logDir.resolve("build-" + p.name() + ".log"), dir, 1200, "bash", "-c", cmd);
			}
		}
	}

	void execute(Scenario.Config cfg, Map<String, String> vars) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(cfg.timeoutSec());
		for (Scenario.ProcessSpec p : cfg.processes()) {
			if (procs.containsKey(p.name())) {
				throw new IllegalArgumentException("Duplicate process name " + p.name());
			}
			procs.put(p.name(), new Proc(p.name(), logDir.resolve(p.name() + ".log")));
		}
		// Line and exit triggers for samples; exit triggers run from the client loop below.
		Map<String, List<Runnable>> onExit = new HashMap<>();
		for (Scenario.ProcessSpec p : cfg.processes()) {
			Proc target = procs.get(p.name());
			for (Scenario.SampleSpec s : p.samples()) {
				switch (s.on()) {
					case "ready" -> {
					}
					case "line" -> {
						Proc watched = required(s.process());
						AtomicBoolean fired = new AtomicBoolean();
						watched.onLine(line -> {
							if (line.contains(s.line()) && fired.compareAndSet(false, true)) {
								synchronized (pendingSamples) {
									pendingSamples.add(CompletableFuture.runAsync(() -> sample(target, s)));
								}
							}
						});
					}
					case "exit" -> onExit.computeIfAbsent(required(s.process()).name, k -> new ArrayList<>())
						.add(() -> sample(target, s));
					default -> throw new IllegalArgumentException("Unknown sample trigger '" + s.on() + "'");
				}
			}
		}
		for (Scenario.ProcessSpec p : cfg.processes()) {
			if (!p.isServer()) {
				continue;
			}
			Proc proc = procs.get(p.name());
			step("Starting server " + p.name() + " (" + p.language() + ")");
			start(proc, p, vars);
			if (p.ready() != null) {
				if (!proc.awaitLine(p.ready().line(), p.ready().timeoutSec())) {
					throw new IllegalStateException(p.name() + " did not print '" + p.ready().line() + "' within "
							+ p.ready().timeoutSec() + " s" + exitNote(proc) + "; see " + proc.log);
				}
				System.out.println("  ready (pid " + proc.pid() + ")");
			}
			for (Scenario.SampleSpec s : p.samples()) {
				if (s.on().equals("ready")) {
					sample(proc, s);
				}
			}
		}
		for (Scenario.ProcessSpec p : cfg.processes()) {
			if (p.isServer()) {
				continue;
			}
			Proc proc = procs.get(p.name());
			long remaining = TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime());
			int timeout = (int) Math.max(1, Math.min(p.timeoutSec(), remaining));
			step("Running client " + p.name() + " (" + p.language() + ", timeout " + timeout + " s)");
			long c0 = System.nanoTime();
			start(proc, p, vars);
			Integer code = proc.awaitExit(timeout);
			if (code == null) {
				outcome.failures.add(p.name() + " timed out after " + timeout + " s");
				proc.kill();
			}
			else {
				System.out.printf(Locale.ROOT, "  exited %d after %.1f s%n", code, (System.nanoTime() - c0) / 1e9);
				if (code != p.exitCode()) {
					outcome.failures.add(p.name() + " exited " + code + ", expected " + p.exitCode());
				}
				else {
					outcome.passes.add(p.name() + " exited " + code);
				}
			}
			for (Runnable r : onExit.getOrDefault(p.name(), List.of())) {
				r.run();
			}
		}
		synchronized (pendingSamples) {
			for (CompletableFuture<Void> f : pendingSamples) {
				try {
					f.get(120, TimeUnit.SECONDS);
				}
				catch (Exception e) {
					outcome.failures.add("sample did not complete: " + e);
				}
			}
		}
		for (Scenario.ProcessSpec p : cfg.processes()) {
			Proc proc = procs.get(p.name());
			if (p.isServer() && !proc.isAlive()) {
				outcome.failures.add("server " + p.name() + " exited early (code " + proc.exitValueIfExited() + ")");
			}
		}
	}

	void start(Proc proc, Scenario.ProcessSpec p, Map<String, String> vars) throws Exception {
		Map<String, String> env = new LinkedHashMap<>();
		p.env().forEach((k, v) -> env.put(k, Scenario.subst(v, vars)));
		proc.start(Scenario.subst(p.run(), vars), Path.of(Scenario.subst(p.dir(), vars)), env);
	}

	void sample(Proc target, Scenario.SampleSpec s) {
		try {
			if (s.delayMs() > 0) {
				Thread.sleep(s.delayMs());
			}
			if (!target.isAlive()) {
				outcome.failures.add("cannot sample " + target.name + " at " + s.label() + ": it exited");
				return;
			}
			String line = Sampler.sample(s.label(), target.pid());
			target.append(line);
			System.out.println("  " + line);
		}
		catch (Exception e) {
			outcome.failures.add("sample " + s.label() + " of " + target.name + " failed: " + e);
		}
	}

	Proc required(String name) {
		Proc p = procs.get(name);
		if (p == null) {
			throw new IllegalArgumentException("Sample trigger names unknown process '" + name + "'");
		}
		return p;
	}

	void killAll() {
		List<Proc> reversed = new ArrayList<>(procs.values());
		java.util.Collections.reverse(reversed);
		for (Proc p : reversed) {
			p.kill();
			p.closeLog();
		}
	}

	int finish(Scenario.Config cfg, long t0, List<String> peerSummary, int code) throws Exception {
		double secs = (System.nanoTime() - t0) / 1e9;
		banner("Results: " + scenario);
		if (!peerSummary.isEmpty()) {
			System.out.println("Peers: " + String.join(", ", peerSummary));
		}
		for (Proc p : procs.values()) {
			for (String l : p.lines()) {
				if (l.startsWith("RESULT ") || l.startsWith("SAMPLE ")) {
					System.out.println("  " + p.name + ": " + l);
				}
			}
		}
		outcome.passes.forEach(s -> System.out.println("  ok     " + s));
		outcome.expectedFailures.forEach(s -> System.out.println("  XFAIL  " + s));
		outcome.failures.forEach(s -> System.out.println("  FAIL   " + s));
		String status = code == 0 ? (outcome.expectedFailures.isEmpty() ? "PASS" : "PASS (xfail)") : "FAIL";
		System.out.printf(Locale.ROOT, "%nSCENARIO %s %s in %.1f s; logs: %s%n", scenario, status, secs, logDir);
		collectNotes();
		List<String> result = new ArrayList<>();
		result.add("scenario=" + scenario);
		result.add("status=" + (code == 0 ? "PASS" : "FAIL"));
		result.add("xfail=" + outcome.expectedFailures.size());
		result.add(String.format(Locale.ROOT, "seconds=%.1f", secs));
		result.add("peers=" + String.join(", ", peerSummary));
		result.add("notes=" + String.join("; ", notes));
		result.add("failures=" + outcome.failures.size());
		outcome.failures.forEach(f -> result.add("failure=" + f.replace('\n', ' ')));
		Files.write(logDir.resolve("result.txt"), result);
		return code;
	}

	/** Numbers worth seeing in the run-all table (recorded, not asserted). */
	void collectNotes() {
		for (Proc p : procs.values()) {
			Map<String, String> r = Checks.resultValues(p.lines());
			if (r.containsKey("p50_ms")) {
				notes.add("p50/p99 " + r.get("p50_ms") + "/" + r.get("p99_ms") + " ms");
			}
			Map<String, String> peak = Checks.sampleValues(p.lines(), "peak");
			Map<String, String> after = Checks.sampleValues(p.lines(), "after_close");
			if (!peak.isEmpty()) {
				notes.add("heap peak " + peak.get("heap_mb") + " MB, after close " + after.getOrDefault("heap_mb", "?")
						+ " MB; acp threads " + peak.get("acp_threads") + ", threads " + peak.get("threads"));
			}
			for (String l : p.lines()) {
				Matcher m = HTTP_VERSION.matcher(l);
				if (m.find()) {
					notes.add("client negotiated " + m.group(1));
					break;
				}
			}
		}
		if (!outcome.expectedFailures.isEmpty()) {
			notes.add(outcome.expectedFailures.size() + " expected failure(s)");
		}
	}

	static final Pattern HTTP_VERSION = Pattern.compile("negotiated (HTTP_\\S+)");

	void resetLogDir() throws Exception {
		if (Files.exists(logDir)) {
			try (Stream<Path> s = Files.walk(logDir)) {
				s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
			}
		}
		Files.createDirectories(logDir);
	}

	String sdkVersion() throws Exception {
		String pom = Files.readString(repo.resolve("pom.xml"));
		Matcher m = Pattern.compile("<artifactId>acp-java-sdk</artifactId>\\s*<version>([^<]+)</version>").matcher(pom);
		if (!m.find()) {
			throw new IllegalStateException("Cannot read the SDK version from " + repo.resolve("pom.xml"));
		}
		return m.group(1).trim();
	}

	static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			s.setReuseAddress(true);
			return s.getLocalPort();
		}
	}

	void exec(Path log, Path dir, int timeoutSec, String... cmd) throws Exception {
		int code;
		try (OutputStream out = Files.newOutputStream(log)) {
			out.write(("$ (cd " + dir + " && " + String.join(" ", cmd) + ")\n").getBytes());
			code = Proc.runToCompletion(out, dir, timeoutSec, cmd);
		}
		if (code != 0) {
			throw new IllegalStateException("'" + String.join(" ", cmd) + "' exited " + code + "; see " + log);
		}
	}

	static String exitNote(Proc p) {
		Integer code = p.exitValueIfExited();
		return code == null ? "" : " (it exited with " + code + ")";
	}

	static void banner(String s) {
		System.out.println();
		System.out.println("=".repeat(72));
		System.out.println(s);
		System.out.println("=".repeat(72));
	}

	static void step(String s) {
		System.out.println("-> " + s);
	}

}

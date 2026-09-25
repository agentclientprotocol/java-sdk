/*
 * One running process of a scenario: output goes to logs/<scenario>/<name>.log and is kept in
 * memory for readiness, triggers and assertions. kill() takes down the whole process tree.
 */

import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.StartedProcess;
import org.zeroturnaround.exec.stream.LogOutputStream;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class Proc {

	final String name;

	final Path log;

	private final List<String> lines = new ArrayList<>();

	private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

	private final BufferedWriter writer;

	private StartedProcess started;

	private Process process;

	Proc(String name, Path log) throws IOException {
		this.name = name;
		this.log = log;
		this.writer = Files.newBufferedWriter(log, StandardCharsets.UTF_8);
	}

	/** Start {@code bash -c "exec <command>"} so the pid is the program itself. */
	void start(String command, Path dir, Map<String, String> env) throws IOException {
		append("$ (cd " + dir + " && " + command + ")");
		started = new ProcessExecutor().command("bash", "-c", "exec " + command)
			.directory(dir.toFile())
			.environment(env)
			.redirectErrorStream(true)
			.redirectOutput(new LogOutputStream() {
				@Override
				protected void processLine(String line) {
					append(line);
				}
			})
			.destroyOnExit()
			.start();
		process = started.getProcess();
	}

	/** Record a line as if the process printed it (used for SAMPLE lines). */
	void append(String line) {
		synchronized (this) {
			lines.add(line);
			try {
				writer.write(line);
				writer.newLine();
				writer.flush();
			}
			catch (IOException e) {
				// the log is best effort; assertions use the in-memory copy
			}
		}
		for (Consumer<String> l : listeners) {
			l.accept(line);
		}
	}

	synchronized List<String> lines() {
		return new ArrayList<>(lines);
	}

	void onLine(Consumer<String> listener) {
		listeners.add(listener);
	}

	synchronized boolean contains(String s) {
		return lines.stream().anyMatch(l -> l.contains(s));
	}

	long pid() {
		return process.pid();
	}

	boolean isAlive() {
		return process != null && process.isAlive();
	}

	Integer exitValueIfExited() {
		return process != null && !process.isAlive() ? process.exitValue() : null;
	}

	/** Wait until a line contains {@code marker}; false on timeout or if the process exits first. */
	boolean awaitLine(String marker, int timeoutSec) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec);
		while (System.nanoTime() < deadline) {
			if (contains(marker)) {
				return true;
			}
			if (!process.isAlive()) {
				Thread.sleep(200); // let the pump drain the last lines
				return contains(marker);
			}
			Thread.sleep(50);
		}
		return false;
	}

	/** Wait for exit; null on timeout. */
	Integer awaitExit(int timeoutSec) throws Exception {
		if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
			return null;
		}
		try {
			started.getFuture().get(10, TimeUnit.SECONDS); // output fully pumped
		}
		catch (Exception e) {
			// exit value is what matters
		}
		return process.exitValue();
	}

	/** Kill the process and every descendant; wait for them to go. */
	void kill() {
		if (process != null) {
			killTree(process, name);
		}
	}

	/** SIGTERM the tree, SIGKILL whatever is left after 5 s. */
	static void killTree(Process process, String name) {
		List<ProcessHandle> tree = new ArrayList<>();
		process.toHandle().descendants().forEach(tree::add);
		tree.add(process.toHandle());
		for (ProcessHandle h : tree) {
			h.destroy();
		}
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		for (ProcessHandle h : tree) {
			try {
				h.onExit().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
			}
			catch (Exception e) {
				h.destroyForcibly();
			}
		}
		for (ProcessHandle h : tree) {
			try {
				h.onExit().get(5, TimeUnit.SECONDS);
			}
			catch (Exception e) {
				System.out.println("  WARN pid " + h.pid() + " (" + name + ") did not exit after SIGKILL");
			}
		}
	}

	/**
	 * Run a command to completion with output to {@code out}; on timeout kill its whole tree and
	 * throw. Returns the exit code.
	 */
	static int runToCompletion(java.io.OutputStream out, Path dir, int timeoutSec, String... cmd) throws Exception {
		StartedProcess sp = new ProcessExecutor().command(cmd)
			.directory(dir.toFile())
			.redirectOutput(out)
			.redirectErrorStream(true)
			.destroyOnExit()
			.start();
		try {
			return sp.getFuture().get(timeoutSec, TimeUnit.SECONDS).getExitValue();
		}
		catch (java.util.concurrent.TimeoutException e) {
			killTree(sp.getProcess(), cmd[cmd.length - 1]);
			throw new IllegalStateException("'" + String.join(" ", cmd) + "' timed out after " + timeoutSec + " s");
		}
	}

	void closeLog() {
		synchronized (this) {
			try {
				writer.close();
			}
			catch (IOException e) {
				// ignore
			}
		}
	}

}

/*
 * jcmd samples of a JVM (the run-load.sh logic): full GC, then heap used, total threads and the
 * SDK's own threads (names starting "acp-"). Printed as
 *   SAMPLE <label> threads=N acp_threads=N heap_mb=X rss_mb=Y
 */

import org.zeroturnaround.exec.ProcessExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Sampler {

	private static final Pattern USED = Pattern.compile("used (\\d+)K");

	public static String sample(String label, long pid) throws Exception {
		String jcmd = jcmdFor(pid);
		jcmd(jcmd, pid, "GC.run");
		Thread.sleep(1000);
		String heapInfo = jcmd(jcmd, pid, "GC.heap_info");
		Matcher m = USED.matcher(heapInfo);
		double heapMb = m.find() ? Long.parseLong(m.group(1)) / 1024.0 : -1;
		String threadDump = jcmd(jcmd, pid, "Thread.print");
		long acpThreads = threadDump.lines().filter(l -> l.startsWith("\"acp-")).count();
		long dumpThreads = threadDump.lines().filter(l -> l.startsWith("\"")).count();
		long threads = procStatus(pid, "Threads:", dumpThreads);
		long rssKb = procStatus(pid, "VmRSS:", -1);
		return String.format(Locale.ROOT, "SAMPLE %s threads=%d acp_threads=%d heap_mb=%.1f rss_mb=%.1f", label,
				threads, acpThreads, heapMb, rssKb < 0 ? -1.0 : rssKb / 1024.0);
	}

	/** The jcmd next to the target's own java binary, else JAVA_HOME's, else PATH's. */
	private static String jcmdFor(long pid) {
		String cmd = ProcessHandle.of(pid).flatMap(h -> h.info().command()).orElse(null);
		if (cmd != null) {
			Path sibling = Path.of(cmd).resolveSibling("jcmd");
			if (Files.isExecutable(sibling)) {
				return sibling.toString();
			}
		}
		String javaHome = System.getenv("JAVA_HOME");
		if (javaHome != null && Files.isExecutable(Path.of(javaHome, "bin", "jcmd"))) {
			return Path.of(javaHome, "bin", "jcmd").toString();
		}
		return "jcmd";
	}

	private static String jcmd(String jcmd, long pid, String command) throws Exception {
		return new ProcessExecutor().command(jcmd, Long.toString(pid), command)
			.readOutput(true)
			.redirectErrorStream(true)
			.timeout(60, TimeUnit.SECONDS)
			.execute()
			.outputUTF8();
	}

	private static long procStatus(long pid, String field, long fallback) {
		try {
			for (String l : Files.readAllLines(Path.of("/proc", Long.toString(pid), "status"))) {
				if (l.startsWith(field)) {
					return Long.parseLong(l.substring(field.length()).trim().split("\\s+")[0]);
				}
			}
		}
		catch (Exception e) {
			// not Linux
		}
		return fallback;
	}

}

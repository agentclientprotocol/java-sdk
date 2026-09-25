/*
 * Peer SDK checkouts: clone into .cache/peers/<name>@<ref>, check out the ref, build once per
 * resolved commit, and point .cache/peers/<name> at the checkout in use.
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class Peers {

	/** A peer SDK: where it lives, which ref to test by default, how to build a checkout. */
	@JsonIgnoreProperties({ "comment" })
	public record Peer(String url, String ref, String build) {
	}

	/** A prepared checkout. */
	public record Checkout(String name, String ref, String sha, Path dir) {
	}

	public static Map<String, Peer> load(Path file) throws Exception {
		return Scenario.MAPPER.readValue(file.toFile(), new TypeReference<LinkedHashMap<String, Peer>>() {
		});
	}

	public static Checkout prepare(String name, Peer peer, String ref, Path cacheDir, Path logDir) throws Exception {
		Path peersDir = cacheDir.resolve("peers");
		Files.createDirectories(peersDir);
		Path dir = peersDir.resolve(name + "@" + ref.replaceAll("[^A-Za-z0-9._-]", "_"));
		Path log = logDir.resolve("peer-" + name + ".log");
		String sha;
		try (OutputStream out = Files.newOutputStream(log)) {
			if (!Files.exists(dir.resolve(".git"))) {
				System.out.println("  cloning " + peer.url() + " -> " + dir);
				run(out, peersDir, 600, "git", "clone", "--quiet", "--filter=blob:none", peer.url(), dir.toString());
			}
			else {
				// A branch ref (main) moves; tags and SHAs do not. An offline fetch keeps the cache.
				if (exec(out, dir, 300, "git", "fetch", "--quiet", "--tags", "--force", "origin") != 0) {
					System.out.println("  WARN fetch failed for " + name + "; using the cached checkout");
				}
			}
			sha = resolve(dir, ref);
			run(out, dir, 120, "git", "-c", "advice.detachedHead=false", "checkout", "--quiet", "--force", sha);
			Path stamp = dir.resolve(".harness-built");
			String built = Files.exists(stamp) ? Files.readString(stamp).trim() : "";
			if (!built.equals(sha)) {
				System.out.println("  building " + name + " @ " + ref + " (" + sha.substring(0, 12) + ")");
				run(out, dir, 1200, "bash", "-c", peer.build());
				Files.writeString(stamp, sha + "\n");
			}
			else {
				System.out.println("  " + name + " @ " + ref + " (" + sha.substring(0, 12) + ") already built");
			}
		}
		Path link = peersDir.resolve(name);
		Files.deleteIfExists(link);
		Files.createSymbolicLink(link, dir.getFileName());
		return new Checkout(name, ref, sha, dir.toAbsolutePath());
	}

	private static String resolve(Path dir, String ref) throws Exception {
		for (String candidate : List.of("origin/" + ref, "refs/tags/" + ref, ref)) {
			ProcessResult r = new ProcessExecutor()
				.command("git", "rev-parse", "--verify", "--quiet", candidate + "^{commit}")
				.directory(dir.toFile())
				.readOutput(true)
				.timeout(30, TimeUnit.SECONDS)
				.execute();
			if (r.getExitValue() == 0) {
				return r.outputUTF8().trim();
			}
		}
		throw new IllegalArgumentException("Ref '" + ref + "' not found in " + dir);
	}

	private static int exec(OutputStream out, Path dir, int timeoutSec, String... cmd) throws Exception {
		return Proc.runToCompletion(out, dir, timeoutSec, cmd);
	}

	private static void run(OutputStream out, Path dir, int timeoutSec, String... cmd) throws Exception {
		int code = exec(out, dir, timeoutSec, cmd);
		if (code != 0) {
			throw new IllegalStateException("Command failed (exit " + code + "): " + String.join(" ", cmd)
					+ " in " + dir);
		}
	}

}

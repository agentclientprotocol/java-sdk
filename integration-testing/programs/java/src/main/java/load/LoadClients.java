package load;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.transport.StreamableHttpAcpClientTransport;
import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import reactor.core.publisher.Mono;

/**
 * N clients, each: initialize, new session, P prompts back to back (each streams two updates).
 * All clients stay connected until everyone is done, so the server holds N connections at peak.
 * Usage: {@code LoadClients <endpoint> <clients> <prompts> [perclient|shared]}; {@code shared}
 * puts every client on one HttpClient (one HTTP/2 connection).
 *
 * <p>Prints {@code PEAK_REACHED} and holds all connections for {@code HOLD_MS} (default 4000)
 * while the runner samples the server, then {@code RESULT ...}, closes every client and prints
 * a second {@code RESULT} line with close timings and {@code CLOSED}.
 */
public class LoadClients {

	public static void main(String[] args) throws Exception {
		URI endpoint = URI.create(args[0]);
		int clients = Integer.parseInt(args[1]);
		int prompts = Integer.parseInt(args[2]);
		boolean sharedConnection = args.length > 3 && args[3].equals("shared");
		long holdMs = Long.getLong("HOLD_MS", 4000);
		int closeParallelism = Integer.getInteger("CLOSE_PARALLELISM", clients);
		ExecutorService httpExecutor = Executors.newFixedThreadPool(32, daemon("http"));
		HttpClient shared = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).executor(httpExecutor).build();
		AtomicInteger ok = new AtomicInteger();
		AtomicInteger updates = new AtomicInteger();
		ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();
		ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
		List<AcpAsyncClient> all = new ArrayList<>();
		CountDownLatch done = new CountDownLatch(clients);
		// One platform thread per client (the SDK baseline is Java 17, so no virtual threads).
		ExecutorService drivers = Executors.newCachedThreadPool(daemon("driver"));
		long t0 = System.nanoTime();
		for (int c = 0; c < clients; c++) {
			AcpAsyncClient client = AcpClient
				.async(new StreamableHttpAcpClientTransport(endpoint, AcpJsonMapper.createDefault(),
						sharedConnection ? shared
								: HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).executor(httpExecutor).build()))
				.requestTimeout(Duration.ofSeconds(60))
				.sessionUpdateConsumer(n -> {
					updates.incrementAndGet();
					return Mono.empty();
				})
				.build();
			all.add(client);
			drivers.execute(() -> {
				try {
					client.initialize().block();
					String sid = client.newSession(new AcpSchema.NewSessionRequest("/w", List.of())).block().sessionId();
					for (int p = 0; p < prompts; p++) {
						long s = System.nanoTime();
						client.prompt(new AcpSchema.PromptRequest(sid, List.of(new AcpSchema.TextContent("hi")))).block();
						latencies.add((System.nanoTime() - s) / 1_000_000);
						ok.incrementAndGet();
					}
				}
				catch (Throwable e) {
					errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
				}
				finally {
					done.countDown();
				}
			});
		}
		done.await();
		long wall = (System.nanoTime() - t0) / 1_000_000;
		System.out.println("PEAK_REACHED");
		Thread.sleep(holdMs); // hold all connections while the runner samples the server
		List<Long> sorted = new ArrayList<>(latencies);
		sorted.sort(null);
		long p50 = sorted.isEmpty() ? -1 : sorted.get(sorted.size() / 2);
		long p99 = sorted.isEmpty() ? -1 : sorted.get((int) Math.min(sorted.size() - 1, sorted.size() * 0.99));
		System.out.printf(
				"RESULT clients=%d prompts=%d ok=%d expected=%d updates=%d errors=%d wall_ms=%d p50_ms=%d p99_ms=%d%n",
				clients, prompts, ok.get(), clients * prompts, updates.get(), errors.size(), wall, p50, p99);
		errors.stream().distinct().limit(5).forEach(e -> System.out.println("ERROR " + e));
		long closeStart = System.nanoTime();
		ConcurrentLinkedQueue<Long> closeTimes = new ConcurrentLinkedQueue<>();
		AtomicInteger closeErrors = new AtomicInteger();
		CountDownLatch closed = new CountDownLatch(all.size());
		ExecutorService closers = Executors.newFixedThreadPool(closeParallelism, daemon("close"));
		for (AcpAsyncClient client : all) {
			closers.execute(() -> {
				long s = System.nanoTime();
				try {
					client.closeGracefully().block(Duration.ofSeconds(30));
				}
				catch (Throwable e) {
					closeErrors.incrementAndGet();
					System.out.println("CLOSE_ERROR " + e);
				}
				closeTimes.add((System.nanoTime() - s) / 1_000_000);
				closed.countDown();
			});
		}
		closed.await();
		List<Long> ct = new ArrayList<>(closeTimes);
		ct.sort(null);
		// A second RESULT line; the runner merges the keys of every RESULT line a process prints.
		System.out.println("RESULT close_total_ms=" + (System.nanoTime() - closeStart) / 1_000_000 + " close_p50_ms="
				+ ct.get(ct.size() / 2) + " close_max_ms=" + ct.get(ct.size() - 1) + " close_errors=" + closeErrors.get());
		System.out.println("CLOSED");
		System.exit(0);
	}

	private static java.util.concurrent.ThreadFactory daemon(String prefix) {
		AtomicInteger n = new AtomicInteger();
		return r -> {
			Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		};
	}

}

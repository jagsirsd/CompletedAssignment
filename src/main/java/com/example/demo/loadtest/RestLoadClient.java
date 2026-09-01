package com.example.demo.loadtest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standalone REST load generator, the HTTP/2 counterpart to
 * {@link com.example.demo.loadtest.GrpcLoadClient}. Builds ONE {@link HttpClient}
 * requesting HTTP/2 — the JDK client auto-upgrades a plaintext connection to h2c
 * (matching Http2Config's Tomcat-side h2c support) and multiplexes concurrent
 * requests from every worker thread over that shared, persistent connection,
 * instead of a new client process/connection per request.
 *
 * Usage: RestLoadClient <totalRows> <threads> [host] [port]
 */
public final class RestLoadClient {

    private RestLoadClient() {}

    public static void main(String[] args) throws InterruptedException {
        if (args.length < 2) {
            System.err.println("Usage: RestLoadClient <totalRows> <threads> [host] [port]");
            System.exit(1);
        }
        int total = Integer.parseInt(args[0]);
        int threads = Integer.parseInt(args[1]);
        String host = args.length > 2 ? args[2] : "localhost";
        int port = args.length > 3 ? Integer.parseInt(args[3]) : 8080;

        int perThread = total / threads;
        int remainder = total - perThread * threads;

        URI uri = URI.create("http://" + host + ":" + port + "/api/items");
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        System.out.printf("Live load: %d rows | %d threads | ~%d rows/thread (persistent HTTP/2 client to %s)%n",
                total, threads, perThread, uri);
        long start = System.currentTimeMillis();

        AtomicInteger totalOk = new AtomicInteger();
        AtomicInteger totalFail = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int t = 1; t <= threads; t++) {
            int count = (t == threads) ? perThread + remainder : perThread;
            int threadId = t;
            pool.submit(() -> runThread(client, uri, threadId, count, totalOk, totalFail));
        }

        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.HOURS);

        long elapsedSeconds = (System.currentTimeMillis() - start) / 1000;
        System.out.printf("Live load done. ok=%d fail=%d elapsed=%ds%n",
                totalOk.get(), totalFail.get(), elapsedSeconds);
    }

    private static void runThread(HttpClient client, URI uri, int threadId, int count,
                                   AtomicInteger totalOk, AtomicInteger totalFail) {
        int ok = 0;
        int fail = 0;
        for (int i = 1; i <= count; i++) {
            int r = ThreadLocalRandom.current().nextInt(1_000_000);
            String body = String.format(
                    "{\"name\":\"live-t%d-%d-%d\",\"description\":\"desc-t%d-%d-%d\"}",
                    threadId, i, r, threadId, i, r);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            try {
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                if (threadId == 1 && i == 1) {
                    System.out.println("  Negotiated protocol version: " + response.version());
                }
                int status = response.statusCode();
                if (status == 200 || status == 201) {
                    ok++;
                } else {
                    fail++;
                }
            } catch (Exception e) {
                fail++;
            }
        }
        totalOk.addAndGet(ok);
        totalFail.addAndGet(fail);
        System.out.printf("  Thread %d done — ok: %d  failed: %d  (%d total)%n", threadId, ok, fail, count);
    }
}

package com.example.demo.loadtest;

import com.example.demo.grpc.v1.CreateItemRequest;
import com.example.demo.grpc.v1.ItemServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standalone gRPC load generator. Opens a single persistent, HTTP/2-multiplexed
 * channel and fires CreateItem calls concurrently across a fixed thread pool,
 * instead of spawning a new client process (and connection) per request the
 * way a grpcurl-in-a-bash-loop generator would.
 *
 * Usage: GrpcLoadClient <totalRows> <threads> [host] [port]
 */
public final class GrpcLoadClient {

    private GrpcLoadClient() {}

    public static void main(String[] args) throws InterruptedException {
        if (args.length < 2) {
            System.err.println("Usage: GrpcLoadClient <totalRows> <threads> [host] [port]");
            System.exit(1);
        }
        int total = Integer.parseInt(args[0]);
        int threads = Integer.parseInt(args[1]);
        String host = args.length > 2 ? args[2] : "localhost";
        int port = args.length > 3 ? Integer.parseInt(args[3]) : 9090;

        int perThread = total / threads;
        int remainder = total - perThread * threads;

        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        try {
            ItemServiceGrpc.ItemServiceBlockingStub stub = ItemServiceGrpc.newBlockingStub(channel);

            System.out.printf("Live load: %d rows | %d threads | ~%d rows/thread (persistent channel to %s:%d)%n",
                    total, threads, perThread, host, port);
            long start = System.currentTimeMillis();

            AtomicInteger totalOk = new AtomicInteger();
            AtomicInteger totalFail = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(threads);

            for (int t = 1; t <= threads; t++) {
                int count = (t == threads) ? perThread + remainder : perThread;
                int threadId = t;
                pool.submit(() -> runThread(stub, threadId, count, totalOk, totalFail));
            }

            pool.shutdown();
            pool.awaitTermination(2, TimeUnit.HOURS);

            long elapsedSeconds = (System.currentTimeMillis() - start) / 1000;
            System.out.printf("Live load done. ok=%d fail=%d elapsed=%ds%n",
                    totalOk.get(), totalFail.get(), elapsedSeconds);
        } finally {
            channel.shutdownNow();
        }
    }

    private static void runThread(ItemServiceGrpc.ItemServiceBlockingStub stub, int threadId, int count,
                                   AtomicInteger totalOk, AtomicInteger totalFail) {
        int ok = 0;
        int fail = 0;
        for (int i = 1; i <= count; i++) {
            int r = ThreadLocalRandom.current().nextInt(1_000_000);
            try {
                stub.createItem(CreateItemRequest.newBuilder()
                        .setName("live-t" + threadId + "-" + i + "-" + r)
                        .setDescription("desc-t" + threadId + "-" + i + "-" + r)
                        .build());
                ok++;
            } catch (Exception e) {
                fail++;
            }
        }
        totalOk.addAndGet(ok);
        totalFail.addAndGet(fail);
        System.out.printf("  Thread %d done — ok: %d  failed: %d  (%d total)%n", threadId, ok, fail, count);
    }
}

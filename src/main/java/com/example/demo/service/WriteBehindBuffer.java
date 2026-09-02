package com.example.demo.service;

import com.example.demo.entity.Item;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * In-memory write-behind buffer: {@link #enqueue(Item)} returns immediately (no DB round
 * trip on the request path at all), and a scheduled task periodically coalesces whatever
 * has queued up into one batched INSERT. Trades durability for latency and throughput — a
 * row is only actually in PostgreSQL once its batch flushes; a crash before that loses it.
 * Explicitly not a fit for anything that needs a write acknowledgement to mean "durably
 * stored" — see DECISIONS.md for the full tradeoff writeup.
 *
 * <p>Items arrive with their id already assigned by {@link SnowflakeIdGenerator}, so the
 * batch INSERT supplies ids explicitly (bypassing the identity column's own generator via
 * {@code OVERRIDING SYSTEM VALUE}, which Postgres accepts whether the column is
 * {@code GENERATED ALWAYS} or {@code BY DEFAULT}).
 */
@Component
public class WriteBehindBuffer {

    private static final int MAX_ROWS_PER_FLUSH = 5000;
    private static final String BATCH_INSERT_SQL =
            "INSERT INTO items (id, name, description) OVERRIDING SYSTEM VALUE VALUES (?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;
    private final DistributionSummary batchSize;
    private final Timer flushTimer;
    private final ConcurrentLinkedQueue<Item> queue = new ConcurrentLinkedQueue<>();

    public WriteBehindBuffer(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.batchSize = DistributionSummary.builder("writebehind.batch.size")
                .description("Number of rows coalesced into one batched INSERT")
                .register(meterRegistry);
        this.flushTimer = Timer.builder("writebehind.batch.flush.duration")
                .description("Time to execute one batched INSERT")
                .register(meterRegistry);
    }

    /** Non-blocking: adds to the in-memory queue and returns. No DB I/O on this call. */
    public void enqueue(Item item) {
        queue.add(item);
    }

    @Scheduled(fixedDelay = 25)
    public void flush() {
        List<Item> batch = drain();
        if (batch.isEmpty()) {
            return;
        }
        Timer.Sample sample = Timer.start();
        jdbcTemplate.batchUpdate(BATCH_INSERT_SQL, batch, batch.size(), (ps, item) -> {
            ps.setLong(1, item.getId());
            ps.setString(2, item.getName());
            ps.setString(3, item.getDescription());
        });
        sample.stop(flushTimer);
        batchSize.record(batch.size());
    }

    private List<Item> drain() {
        List<Item> batch = new ArrayList<>(Math.min(MAX_ROWS_PER_FLUSH, 256));
        Item item;
        while (batch.size() < MAX_ROWS_PER_FLUSH && (item = queue.poll()) != null) {
            batch.add(item);
        }
        return batch;
    }
}

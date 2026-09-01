package com.example.demo.metrics;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class TableMetricsCollector {

    private final CqlSession                     cqlSession;
    private final RedisTemplate<String, String>  redisTemplate;
    private final MeterRegistry                  meterRegistry;

    private final AtomicLong dbRowCount    = new AtomicLong(0);
    private final AtomicLong redisKeyCount = new AtomicLong(0);

    @Value("${app.metrics.collection-interval-ms:5000}")
    private long collectionIntervalMs;

    @Value("${spring.data.cassandra.keyspace-name:demo}")
    private String keyspaceName;

    public TableMetricsCollector(CqlSession cqlSession,
                                 RedisTemplate<String, String> redisTemplate,
                                 MeterRegistry meterRegistry) {
        this.cqlSession   = cqlSession;
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void registerGauges() {
        Gauge.builder("db.table.rows", dbRowCount, AtomicLong::doubleValue)
                .description("Approximate row count of the items table (system.size_estimates)")
                .tag("table", "items")
                .register(meterRegistry);

        Gauge.builder("cache.key.count", redisKeyCount, AtomicLong::doubleValue)
                .description("Number of item keys currently held in Redis")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${app.metrics.collection-interval-ms:5000}")
    public void collect() {
        try {
            // Cassandra/Scylla equivalent of Postgres's pg_stat_user_tables.n_live_tup —
            // a fast, approximate estimate from system metadata rather than a full scan.
            // partitions_count sums to an approximate row count since id (the sole primary
            // key column) is the partition key: one row per partition in this schema.
            long total = 0;
            for (Row row : cqlSession.execute(
                    "SELECT partitions_count FROM system.size_estimates "
                            + "WHERE keyspace_name = '" + keyspaceName + "' AND table_name = 'items'")) {
                total += row.getLong("partitions_count");
            }
            dbRowCount.set(total);
        } catch (Exception ignored) {}

        try {
            Long keys = redisTemplate.execute(
                    (RedisCallback<Long>) conn -> conn.serverCommands().dbSize());
            if (keys != null) redisKeyCount.set(keys);
        } catch (Exception ignored) {}
    }
}

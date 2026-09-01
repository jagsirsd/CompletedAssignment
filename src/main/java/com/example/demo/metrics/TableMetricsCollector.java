package com.example.demo.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class TableMetricsCollector {

    private final JdbcTemplate               jdbcTemplate;
    private final RedisTemplate<String, String> redisTemplate;
    private final MeterRegistry              meterRegistry;

    private final AtomicLong dbRowCount    = new AtomicLong(0);
    private final AtomicLong redisKeyCount = new AtomicLong(0);

    @Value("${app.metrics.collection-interval-ms:5000}")
    private long collectionIntervalMs;

    public TableMetricsCollector(JdbcTemplate jdbcTemplate,
                                 RedisTemplate<String, String> redisTemplate,
                                 MeterRegistry meterRegistry) {
        this.jdbcTemplate  = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void registerGauges() {
        Gauge.builder("db.table.rows", dbRowCount, AtomicLong::doubleValue)
                .description("Approximate row count of the items table (pg_stat_user_tables)")
                .tag("table", "items")
                .register(meterRegistry);

        Gauge.builder("cache.key.count", redisKeyCount, AtomicLong::doubleValue)
                .description("Number of item keys currently held in Redis")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${app.metrics.collection-interval-ms:5000}")
    public void collect() {
        try {
            Long rows = jdbcTemplate.queryForObject(
                    "SELECT n_live_tup FROM pg_stat_user_tables WHERE relname = 'items'",
                    Long.class);
            if (rows != null) dbRowCount.set(rows);
        } catch (Exception ignored) {}

        try {
            Long keys = redisTemplate.execute(
                    (RedisCallback<Long>) conn -> conn.serverCommands().dbSize());
            if (keys != null) redisKeyCount.set(keys);
        } catch (Exception ignored) {}
    }
}

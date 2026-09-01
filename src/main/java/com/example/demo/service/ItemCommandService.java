package com.example.demo.service;

import com.example.demo.entity.Item;
import com.example.demo.readmodel.ItemReadStore;
import com.example.demo.repository.ItemRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Command side of the CQRS split. Writes to PostgreSQL (source of truth), then — in
 * addition to the existing Debezium CDC path (Postgres WAL -> Kafka -> CdcEventConsumer
 * -> Redis) — synchronously pushes the same change straight into the read-model cache
 * before returning.
 *
 * <p>Why: the CDC path is fully decoupled (this class never touched {@link ItemReadStore}
 * before), which is correct for write-path isolation but means a client that creates an
 * item and immediately reads it back sees a cache miss until Debezium captures the WAL
 * record AND the Kafka consumer processes it — measured in this environment (see
 * {@code cdc.capture.lag.duration}/{@code cdc.consume.lag.duration}) at anywhere from tens
 * of milliseconds up to minutes under backlog. The fast path trades a small, bounded
 * amount of write-path latency (one extra Redis round trip, ~sub-millisecond normally) for
 * read-your-writes consistency in the common case, while the CDC path keeps running
 * unchanged as the durable, replayable reconciliation source — both paths write the same
 * derived state, so the CDC path's later (redundant) write is harmless.
 *
 * <p>The fast-path write is best-effort: if Redis is slow or unavailable, the exception is
 * caught and logged, not propagated — a client's write must never fail because the cache
 * is unhappy. CDC remains the backstop that will eventually populate the cache regardless.
 */
@Service
public class ItemCommandService {

    private static final Logger log = LoggerFactory.getLogger(ItemCommandService.class);

    private final ItemRepository itemRepository;
    private final ItemReadStore  itemReadStore;
    private final MeterRegistry  meterRegistry;

    public ItemCommandService(ItemRepository itemRepository, ItemReadStore itemReadStore,
                               MeterRegistry meterRegistry) {
        this.itemRepository = itemRepository;
        this.itemReadStore = itemReadStore;
        this.meterRegistry = meterRegistry;
    }

    /**
     * @param transport caller's transport, e.g. "rest" or "grpc" — tagged on every metric
     *                  here so the two APIs can be compared directly.
     */
    public Item create(Item item, String transport) {
        Timer.Sample total = Timer.start(meterRegistry);
        Item saved = writeTimer(transport).record(() -> itemRepository.save(item));
        fastPathUpdate(transport, () -> itemReadStore.onItemCreated(
                saved.getId(), saved.getName(), saved.getDescription()));
        total.stop(totalTimer(transport, "create"));
        return saved;
    }

    public boolean delete(Long id, String transport) {
        Timer.Sample total = Timer.start(meterRegistry);
        if (!itemRepository.existsById(id)) {
            return false;
        }
        deleteTimer(transport).record(() -> itemRepository.deleteById(id));
        fastPathUpdate(transport, () -> itemReadStore.onItemDeleted(id));
        total.stop(totalTimer(transport, "delete"));
        return true;
    }

    private void fastPathUpdate(String transport, Runnable cacheWrite) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            cacheWrite.run();
        } catch (Exception e) {
            log.warn("Fast-path cache update failed, CDC will reconcile it later", e);
        } finally {
            sample.stop(fastPathTimer(transport));
        }
    }

    private Timer writeTimer(String transport) {
        return Timer.builder("db.write.duration")
                .description("Time to persist a new item to PostgreSQL")
                .tag("transport", transport)
                .register(meterRegistry);
    }

    private Timer deleteTimer(String transport) {
        return Timer.builder("db.delete.duration")
                .description("Time to delete an item from PostgreSQL")
                .tag("transport", transport)
                .register(meterRegistry);
    }

    private Timer fastPathTimer(String transport) {
        return Timer.builder("fastpath.cache.write.duration")
                .description("Extra time to synchronously push a write into the Redis read model, bypassing CDC/Kafka")
                .tag("transport", transport)
                .register(meterRegistry);
    }

    private Timer totalTimer(String transport, String operation) {
        return Timer.builder("item.command.total.duration")
                .description("Full command-path time: DB write/delete + fast-path cache update combined")
                .tag("transport", transport)
                .tag("operation", operation)
                .register(meterRegistry);
    }
}

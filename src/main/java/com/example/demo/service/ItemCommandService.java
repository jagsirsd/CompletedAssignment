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
 * Command side of the CQRS split — write-behind variant. {@code create} no longer writes
 * to PostgreSQL on the request path at all: it assigns an id (app-generated, see
 * {@link SnowflakeIdGenerator}), pushes the item into {@link WriteBehindBuffer} (an
 * in-memory queue, returns immediately), and synchronously updates the Redis read model
 * directly from this layer. The buffer's own scheduled task coalesces many buffered rows
 * into one batched INSERT shortly after — Postgres involvement is moved entirely off the
 * critical path.
 *
 * <p>This is a genuine durability tradeoff, not a free win: a write is acknowledged to the
 * client (and visible in Redis) before it is durably in PostgreSQL. A crash with rows still
 * queued loses them. CDC still runs against whatever does land in Postgres, so once a
 * batch flushes, that portion reconciles as usual — the tradeoff is scoped to the window
 * between acknowledgement and the next flush (currently up to ~25ms), not open-ended.
 * See DECISIONS.md for the full writeup.
 *
 * <p>{@code delete} is unchanged (direct, synchronous) — deletes aren't the throughput
 * bottleneck this variant targets, and buffering them adds "is this id still only in the
 * queue, or already flushed" bookkeeping for no benefit this workload needs.
 */
@Service
public class ItemCommandService {

    private static final Logger log = LoggerFactory.getLogger(ItemCommandService.class);

    private final ItemRepository       itemRepository;
    private final ItemReadStore        itemReadStore;
    private final WriteBehindBuffer    writeBehindBuffer;
    private final SnowflakeIdGenerator idGenerator;
    private final MeterRegistry        meterRegistry;

    public ItemCommandService(ItemRepository itemRepository, ItemReadStore itemReadStore,
                               WriteBehindBuffer writeBehindBuffer, SnowflakeIdGenerator idGenerator,
                               MeterRegistry meterRegistry) {
        this.itemRepository = itemRepository;
        this.itemReadStore = itemReadStore;
        this.writeBehindBuffer = writeBehindBuffer;
        this.idGenerator = idGenerator;
        this.meterRegistry = meterRegistry;
    }

    /**
     * @param transport caller's transport, e.g. "rest" or "grpc" — tagged on every metric
     *                  here so the two APIs can be compared directly.
     */
    public Item create(Item item, String transport) {
        Timer.Sample total = Timer.start(meterRegistry);

        item.setId(idGenerator.nextId());
        writeBehindBuffer.enqueue(item);

        fastPathUpdate(transport, () -> itemReadStore.onItemCreated(
                item.getId(), item.getName(), item.getDescription()));
        total.stop(totalTimer(transport, "create"));
        return item;
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

    private Timer deleteTimer(String transport) {
        return Timer.builder("db.delete.duration")
                .description("Time to delete an item from PostgreSQL")
                .tag("transport", transport)
                .register(meterRegistry);
    }

    private Timer fastPathTimer(String transport) {
        return Timer.builder("fastpath.cache.write.duration")
                .description("Time to synchronously push a write into the Redis read model")
                .tag("transport", transport)
                .register(meterRegistry);
    }

    private Timer totalTimer(String transport, String operation) {
        return Timer.builder("item.command.total.duration")
                .description("Full command-path time as seen by the caller (excludes the async DB batch flush)")
                .tag("transport", transport)
                .tag("operation", operation)
                .register(meterRegistry);
    }
}

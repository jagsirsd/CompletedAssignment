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
 * Command side of the CQRS split — ScyllaDB variant. Writes go straight to Scylla
 * (synchronous, source of truth), then this layer synchronously pushes the same change
 * into the Redis read model directly.
 *
 * <p>Important gap, stated plainly: unlike the Postgres/Debezium branches, there is no CDC
 * pipeline here. Scylla has its own native CDC mechanism, but it's a completely different
 * integration (a separate Kafka Connect plugin, {@code scylla-cdc-source-connector}, and a
 * different change-log envelope format) that was out of scope to build in this pass — see
 * DECISIONS.md. That means the synchronous Redis write below is not a latency
 * optimization *alongside* a durable reconciliation path, the way it is on the other
 * branches — it is the *only* mechanism populating the read model. If it fails, or if
 * Redis is flushed, there is currently nothing that will replay Scylla's data back into
 * it. Best-effort error handling here (catch and log, never fail the client's write)
 * still stands, but "CDC will catch it later" is not true on this branch the way the
 * comment might imply elsewhere.
 */
@Service
public class ItemCommandService {

    private static final Logger log = LoggerFactory.getLogger(ItemCommandService.class);

    private final ItemRepository       itemRepository;
    private final ItemReadStore        itemReadStore;
    private final SnowflakeIdGenerator idGenerator;
    private final MeterRegistry        meterRegistry;

    public ItemCommandService(ItemRepository itemRepository, ItemReadStore itemReadStore,
                               SnowflakeIdGenerator idGenerator, MeterRegistry meterRegistry) {
        this.itemRepository = itemRepository;
        this.itemReadStore = itemReadStore;
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
            log.warn("Read-model cache update failed — this branch has no CDC backstop, "
                    + "this write will not be reflected in Redis until something else fixes it", e);
        } finally {
            sample.stop(fastPathTimer(transport));
        }
    }

    private Timer writeTimer(String transport) {
        return Timer.builder("db.write.duration")
                .description("Time to persist a new item to ScyllaDB")
                .tag("transport", transport)
                .register(meterRegistry);
    }

    private Timer deleteTimer(String transport) {
        return Timer.builder("db.delete.duration")
                .description("Time to delete an item from ScyllaDB")
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
                .description("Full command-path time: Scylla write/delete + Redis update combined")
                .tag("transport", transport)
                .tag("operation", operation)
                .register(meterRegistry);
    }
}

package com.example.demo.service;

import com.example.demo.entity.Item;
import com.example.demo.repository.ItemRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

@Service
public class ItemCommandService {

    private final ItemRepository itemRepository;
    private final MeterRegistry  meterRegistry;

    public ItemCommandService(ItemRepository itemRepository, MeterRegistry meterRegistry) {
        this.itemRepository = itemRepository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * @param transport caller's transport, e.g. "rest" or "grpc" — tagged on db.write.duration
     *                  so the two APIs' DB-write latency can be compared directly. Everything
     *                  downstream of this write (CDC/Kafka/Redis) is transport-agnostic by
     *                  design, so this is the only stage where a REST-vs-gRPC split is meaningful.
     */
    public Item create(Item item, String transport) {
        return writeTimer(transport).record(() -> itemRepository.save(item));
    }

    public boolean delete(Long id, String transport) {
        if (!itemRepository.existsById(id)) {
            return false;
        }
        deleteTimer(transport).record(() -> itemRepository.deleteById(id));
        return true;
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
}

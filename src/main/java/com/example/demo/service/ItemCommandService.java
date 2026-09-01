package com.example.demo.service;

import com.example.demo.entity.Item;
import com.example.demo.repository.ItemRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

@Service
public class ItemCommandService {

    private final ItemRepository itemRepository;
    private final Timer          writeTimer;
    private final Timer          deleteTimer;

    public ItemCommandService(ItemRepository itemRepository, MeterRegistry meterRegistry) {
        this.itemRepository = itemRepository;
        this.writeTimer = Timer.builder("db.write.duration")
                .description("Time to persist a new item to PostgreSQL")
                .register(meterRegistry);
        this.deleteTimer = Timer.builder("db.delete.duration")
                .description("Time to delete an item from PostgreSQL")
                .register(meterRegistry);
    }

    public Item create(Item item) {
        return writeTimer.record(() -> itemRepository.save(item));
    }

    public boolean delete(Long id) {
        if (!itemRepository.existsById(id)) {
            return false;
        }
        deleteTimer.record(() -> itemRepository.deleteById(id));
        return true;
    }
}

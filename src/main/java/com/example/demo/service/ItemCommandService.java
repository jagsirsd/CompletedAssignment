package com.example.demo.service;

import com.example.demo.entity.Item;
import com.example.demo.kafka.ItemEventProducer;
import com.example.demo.repository.ItemRepository;
import org.springframework.stereotype.Service;

/**
 * Write side of the CQRS split. Talks only to the "items" table (source of truth) and
 * publishes to Kafka — it never touches a read model or cache directly, so the write
 * path's latency doesn't depend on how many read replicas/cache entries exist. See
 * DECISIONS.md.
 */
@Service
public class ItemCommandService {

    private final ItemRepository itemRepository;
    private final ItemEventProducer eventProducer;

    public ItemCommandService(ItemRepository itemRepository, ItemEventProducer eventProducer) {
        this.itemRepository = itemRepository;
        this.eventProducer = eventProducer;
    }

    public Item create(Item item) {
        Item saved = itemRepository.save(item);
        eventProducer.publishCreated(saved.getId(), saved.getName(), saved.getDescription());
        return saved;
    }

    public boolean delete(Long id) {
        if (!itemRepository.existsById(id)) {
            return false;
        }
        itemRepository.deleteById(id);
        eventProducer.publishDeleted(id);
        return true;
    }
}

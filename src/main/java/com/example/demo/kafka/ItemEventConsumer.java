package com.example.demo.kafka;

import com.example.demo.readmodel.ItemReadStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Service;

/**
 * Drives the read side of the CQRS split (see ItemReadStore) off the same item-events
 * topic ItemEventProducer publishes to on every write. This is the async refresh
 * mechanism — the write path never blocks on it, so a slow/unavailable read store only
 * delays how quickly reads become consistent, not how fast writes complete.
 */
@Service
public class ItemEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ItemEventConsumer.class);

    private final ItemReadStore itemReadStore;
    private final ObjectMapper objectMapper;

    public ItemEventConsumer(ItemReadStore itemReadStore, ObjectMapper objectMapper) {
        this.itemReadStore = itemReadStore;
        this.objectMapper = objectMapper;
    }

    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 2000),
            autoCreateTopics = "true"
    )
    @KafkaListener(topics = "item-events", groupId = "demo-group")
    public void consume(ConsumerRecord<String, String> record) {
        log.info("Consumed item event — key={} partition={} offset={} value={}",
                record.key(),
                record.partition(),
                record.offset(),
                record.value());

        JsonNode event;
        try {
            event = objectMapper.readTree(record.value());
        } catch (Exception ex) {
            log.error("Skipping unparseable item event at offset={}: {}", record.offset(), ex.getMessage());
            return;
        }

        String type = event.path("event").asText();
        Long id = event.path("id").asLong();

        switch (type) {
            case "CREATED" -> itemReadStore.onItemCreated(
                    id,
                    event.path("name").asText(null),
                    event.path("description").asText(null));
            case "DELETED" -> itemReadStore.onItemDeleted(id);
            default -> log.warn("Ignoring item event of unknown type '{}' at offset={}", type, record.offset());
        }
    }
}

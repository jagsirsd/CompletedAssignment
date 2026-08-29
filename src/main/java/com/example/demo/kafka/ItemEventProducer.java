package com.example.demo.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class ItemEventProducer {

    private static final Logger log = LoggerFactory.getLogger(ItemEventProducer.class);
    private static final String TOPIC = "item-events";

    private final KafkaOperations<String, String> kafkaOperations;
    private final ObjectMapper objectMapper;

    public ItemEventProducer(KafkaOperations<String, String> kafkaOperations, ObjectMapper objectMapper) {
        this.kafkaOperations = kafkaOperations;
        this.objectMapper = objectMapper;
    }

    public void publishCreated(Long itemId, String itemName, String itemDescription) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "CREATED");
        payload.put("id", itemId);
        payload.put("name", itemName);
        payload.put("description", itemDescription);
        send(String.valueOf(itemId), payload);
    }

    public void publishDeleted(Long itemId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "DELETED");
        payload.put("id", itemId);
        send(String.valueOf(itemId), payload);
    }

    private void send(String key, Map<String, Object> payload) {
        String message;
        try {
            message = objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            log.error("Failed to serialize event payload for key={}: {}", key, ex.getMessage());
            return;
        }

        CompletableFuture<SendResult<String, String>> future = kafkaOperations.send(TOPIC, key, message);
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish to {}: {}", TOPIC, ex.getMessage());
            } else {
                log.info("Published to {} partition={} offset={}: {}",
                        TOPIC,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset(),
                        message);
            }
        });
    }
}

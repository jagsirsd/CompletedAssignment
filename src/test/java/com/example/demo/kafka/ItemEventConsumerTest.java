package com.example.demo.kafka;

import com.example.demo.readmodel.ItemReadStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ItemEventConsumerTest {

    @Mock
    private ItemReadStore itemReadStore;

    private ItemEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ItemEventConsumer(itemReadStore, new ObjectMapper());
    }

    @Test
    void consume_created_updatesReadStore() {
        String value = "{\"event\":\"CREATED\",\"id\":42,\"name\":\"Widget\",\"description\":\"A small widget\"}";
        consumer.consume(new ConsumerRecord<>("item-events", 0, 0, "42", value));

        verify(itemReadStore).onItemCreated(42L, "Widget", "A small widget");
    }

    @Test
    void consume_deleted_updatesReadStore() {
        String value = "{\"event\":\"DELETED\",\"id\":7}";
        consumer.consume(new ConsumerRecord<>("item-events", 0, 0, "7", value));

        verify(itemReadStore).onItemDeleted(7L);
    }

    @Test
    void consume_malformedPayload_isSkippedWithoutThrowing() {
        consumer.consume(new ConsumerRecord<>("item-events", 0, 0, "1", "not json"));

        verifyNoInteractions(itemReadStore);
    }

    @Test
    void consume_unknownEventType_isIgnored() {
        String value = "{\"event\":\"UPDATED\",\"id\":1}";
        consumer.consume(new ConsumerRecord<>("item-events", 0, 0, "1", value));

        verifyNoInteractions(itemReadStore);
    }
}

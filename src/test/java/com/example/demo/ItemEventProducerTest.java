package com.example.demo;

import com.example.demo.kafka.ItemEventProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ItemEventProducerTest {

    @Mock
    private KafkaOperations<String, String> kafkaOperations;

    private ItemEventProducer producer;

    @BeforeEach
    void setUp() {
        producer = new ItemEventProducer(kafkaOperations, new ObjectMapper());

        ProducerRecord<String, String> record = new ProducerRecord<>("item-events", "1", "{}");
        RecordMetadata meta = new RecordMetadata(new TopicPartition("item-events", 0), 0, 0, 0, 0, 0);
        SendResult<String, String> sendResult = new SendResult<>(record, meta);
        when(kafkaOperations.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));
    }

    @Test
    void publishCreated_sendsCorrectPayload() {
        producer.publishCreated(42L, "Widget", "A small widget");

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaOperations).send(eq("item-events"), eq("42"), messageCaptor.capture());

        String message = messageCaptor.getValue();
        assertThat(message).contains("\"event\":\"CREATED\"");
        assertThat(message).contains("\"id\":42");
        assertThat(message).contains("\"name\":\"Widget\"");
        assertThat(message).contains("\"description\":\"A small widget\"");
    }

    @Test
    void publishDeleted_sendsCorrectPayload() {
        producer.publishDeleted(7L);

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaOperations).send(eq("item-events"), eq("7"), messageCaptor.capture());

        String message = messageCaptor.getValue();
        assertThat(message).contains("\"event\":\"DELETED\"");
        assertThat(message).contains("\"id\":7");
    }
}

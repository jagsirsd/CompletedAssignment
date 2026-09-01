package com.example.demo.cdc;

import com.example.demo.readmodel.ItemReadStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

@Component
public class CdcEventConsumer {

    private final ItemReadStore readStore;
    private final ObjectMapper  objectMapper;
    private final MeterRegistry meterRegistry;

    public CdcEventConsumer(ItemReadStore readStore,
                            ObjectMapper objectMapper,
                            MeterRegistry meterRegistry) {
        this.readStore    = readStore;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @RetryableTopic(attempts = "3", backoff = @Backoff(delay = 2000))
    @KafkaListener(topics = "${app.debezium.topic:mydb.public.items}",
                   groupId = "cdc-consumer-group")
    public void consume(String message) throws JsonProcessingException {
        CdcEvent event = objectMapper.readValue(message, CdcEvent.class);

        String opLabel = event.isCreate()   ? "create"
                       : event.isUpdate()   ? "update"
                       : event.isDelete()   ? "delete"
                       : event.isSnapshot() ? "snapshot"
                       : "unknown";

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            if (event.isDelete()) {
                readStore.onItemDeleted(event.before().id());
            } else if (event.isCreate() || event.isSnapshot()) {
                CdcEvent.ItemPayload p = event.after();
                readStore.onItemCreated(p.id(), p.name(), p.description());
            } else if (event.isUpdate()) {
                CdcEvent.ItemPayload p = event.after();
                readStore.onItemUpdated(p.id(), p.name(), p.description());
            }
        } finally {
            sample.stop(Timer.builder("cdc.event.processing.duration")
                    .description("End-to-end time to process a CDC event into Redis")
                    .tag("operation", opLabel)
                    .register(meterRegistry));
        }
    }
}

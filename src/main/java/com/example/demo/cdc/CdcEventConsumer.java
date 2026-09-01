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

import java.time.Duration;

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
        long consumedAtMs = System.currentTimeMillis();
        CdcEvent event = objectMapper.readValue(message, CdcEvent.class);

        String opLabel = event.isCreate()   ? "create"
                       : event.isUpdate()   ? "update"
                       : event.isDelete()   ? "delete"
                       : event.isSnapshot() ? "snapshot"
                       : "unknown";

        recordPipelineLag(event, opLabel, consumedAtMs);

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
                    .description("Local time to process a CDC event into Redis")
                    .tag("operation", opLabel)
                    .register(meterRegistry));
        }
    }

    /**
     * Debezium's envelope carries two timestamps: {@code source.ts_ms} (the Postgres WAL
     * commit time) and the top-level {@code ts_ms} (when Debezium produced the record to
     * Kafka). Splitting on those lets us attribute pipeline latency to the two stages this
     * consumer can't otherwise see into: DB commit -> Kafka publish (Debezium's own capture
     * latency), and Kafka publish -> this consumer picking the message up (delivery time,
     * dominated by consumer backlog when one exists).
     */
    private void recordPipelineLag(CdcEvent event, String opLabel, long consumedAtMs) {
        Long sourceTsMs = event.source() != null ? event.source().tsMs() : null;
        Long publishedTsMs = event.tsMs();

        if (sourceTsMs != null && publishedTsMs != null && publishedTsMs >= sourceTsMs) {
            Timer.builder("cdc.capture.lag.duration")
                    .description("DB commit to Kafka publish (Debezium capture latency)")
                    .tag("operation", opLabel)
                    .register(meterRegistry)
                    .record(Duration.ofMillis(publishedTsMs - sourceTsMs));
        }
        if (publishedTsMs != null && consumedAtMs >= publishedTsMs) {
            Timer.builder("cdc.consume.lag.duration")
                    .description("Kafka publish to consumer pickup (delivery + backlog wait)")
                    .tag("operation", opLabel)
                    .register(meterRegistry)
                    .record(Duration.ofMillis(consumedAtMs - publishedTsMs));
        }
    }
}

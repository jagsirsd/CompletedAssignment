package com.example.demo.service;

import org.springframework.stereotype.Component;

/**
 * App-generated 64-bit IDs, Twitter-Snowflake-style: 41 bits of milliseconds since a fixed
 * epoch, 10 bits of node id, 12 bits of per-millisecond sequence. Roughly time-sortable,
 * fits the existing {@code Long id} used throughout the proto/REST/entity/Redis-key
 * surface unchanged.
 *
 * <p>Needed because {@link com.example.demo.service.WriteBehindBuffer} must hand back an id
 * (and use it as the Redis key) the instant a request arrives, before the row is durably
 * persisted — a DB-generated {@code IDENTITY} id doesn't exist yet at that point.
 */
@Component
public class SnowflakeIdGenerator {

    private static final long EPOCH_MS = 1_700_000_000_000L; // fixed reference point, arbitrary
    private static final long NODE_ID_BITS = 10;
    private static final long SEQUENCE_BITS = 12;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;

    private final long nodeId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator() {
        this(0L);
    }

    public SnowflakeIdGenerator(long nodeId) {
        this.nodeId = nodeId & ((1L << NODE_ID_BITS) - 1);
    }

    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            timestamp = lastTimestamp; // clock moved backwards; hold steady rather than collide
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // sequence exhausted for this millisecond; spin to the next one
                while (timestamp <= lastTimestamp) {
                    timestamp = System.currentTimeMillis();
                }
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;

        return ((timestamp - EPOCH_MS) << (NODE_ID_BITS + SEQUENCE_BITS))
                | (nodeId << SEQUENCE_BITS)
                | sequence;
    }
}

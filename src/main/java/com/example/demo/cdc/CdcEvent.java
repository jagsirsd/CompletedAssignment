package com.example.demo.cdc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CdcEvent(
        @JsonProperty("before") ItemPayload before,
        @JsonProperty("after")  ItemPayload after,
        @JsonProperty("op")     String op,
        @JsonProperty("source") Source source,
        @JsonProperty("ts_ms")  Long tsMs
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ItemPayload(Long id, String name, String description) {}

    /** Debezium's embedded source metadata — carries the source DB commit timestamp. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(@JsonProperty("ts_ms") Long tsMs) {}

    public boolean isCreate()   { return "c".equals(op); }
    public boolean isUpdate()   { return "u".equals(op); }
    public boolean isDelete()   { return "d".equals(op); }
    public boolean isSnapshot() { return "r".equals(op); }
}

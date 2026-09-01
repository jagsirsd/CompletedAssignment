package com.example.demo.cdc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CdcEvent(
        @JsonProperty("before") ItemPayload before,
        @JsonProperty("after")  ItemPayload after,
        @JsonProperty("op")     String op
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ItemPayload(Long id, String name, String description) {}

    public boolean isCreate()   { return "c".equals(op); }
    public boolean isUpdate()   { return "u".equals(op); }
    public boolean isDelete()   { return "d".equals(op); }
    public boolean isSnapshot() { return "r".equals(op); }
}

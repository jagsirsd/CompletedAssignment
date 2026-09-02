package com.example.demo.entity;

import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

/**
 * Cassandra/Scylla has no auto-increment concept — every write supplies its own primary
 * key, so {@code id} is always app-generated (see {@link com.example.demo.service.SnowflakeIdGenerator}),
 * never left null for the datastore to fill in.
 */
@Table("items")
public class Item {

    @PrimaryKey
    private Long id;

    private String name;
    private String description;

    public Item() {}

    public Item(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}

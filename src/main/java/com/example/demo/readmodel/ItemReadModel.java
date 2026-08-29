package com.example.demo.readmodel;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Denormalized read-side row, kept in sync from item-events Kafka events (not written to
 * directly by the command path). Backs the "materialized-table" app.cqrs.read-mode.
 */
@Entity
@Table(name = "items_read")
public class ItemReadModel {

    @Id
    private Long id;

    private String name;

    private String description;

    public ItemReadModel() {}

    public ItemReadModel(Long id, String name, String description) {
        this.id = id;
        this.name = name;
        this.description = description;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
}

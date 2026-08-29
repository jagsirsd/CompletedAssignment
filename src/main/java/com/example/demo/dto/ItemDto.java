package com.example.demo.dto;

import com.example.demo.entity.Item;

public record ItemDto(Long id, String name, String description) implements java.io.Serializable {

    public static ItemDto from(Item item) {
        return new ItemDto(item.getId(), item.getName(), item.getDescription());
    }
}

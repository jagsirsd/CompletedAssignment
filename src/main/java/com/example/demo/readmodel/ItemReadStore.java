package com.example.demo.readmodel;

import com.example.demo.dto.ItemDto;
import com.example.demo.dto.PagedResult;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface ItemReadStore {

    Optional<ItemDto> findById(Long id);

    PagedResult<ItemDto> findAll(Pageable pageable);

    void onItemCreated(Long id, String name, String description);

    void onItemUpdated(Long id, String name, String description);

    void onItemDeleted(Long id);
}

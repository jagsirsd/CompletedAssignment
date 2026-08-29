package com.example.demo.readmodel;

import com.example.demo.dto.ItemDto;
import com.example.demo.dto.PagedResult;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

/**
 * Read side of the CQRS split. Implementations never see writes directly — they're kept
 * current by ItemEventConsumer replaying the item-events Kafka topic that
 * ItemCommandService publishes to. See DECISIONS.md for the consistency tradeoff this
 * implies and how to switch implementations via app.cqrs.read-mode.
 */
public interface ItemReadStore {

    Optional<ItemDto> findById(Long id);

    PagedResult<ItemDto> findAll(Pageable pageable);

    void onItemCreated(Long id, String name, String description);

    void onItemDeleted(Long id);
}

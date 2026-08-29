package com.example.demo.readmodel;

import com.example.demo.dto.ItemDto;
import com.example.demo.dto.PagedResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Reads from the separate "items_read" table instead of the write-side "items" table —
 * a closer approximation of physically separate read/write stores than
 * CacheBackedItemReadStore. Still cached in front, so the two app.cqrs.read-mode
 * strategies compose rather than being mutually exclusive with caching. See
 * DECISIONS.md.
 */
@Service
@ConditionalOnProperty(name = "app.cqrs.read-mode", havingValue = "materialized-table")
public class MaterializedItemReadStore implements ItemReadStore {

    private static final String BY_ID_CACHE = "items-by-id";
    private static final String LIST_CACHE = "items-list";

    private final ItemReadModelRepository readModelRepository;
    private final CacheManager cacheManager;

    public MaterializedItemReadStore(ItemReadModelRepository readModelRepository, CacheManager cacheManager) {
        this.readModelRepository = readModelRepository;
        this.cacheManager = cacheManager;
    }

    @Override
    @Cacheable(cacheNames = BY_ID_CACHE, key = "#id")
    public Optional<ItemDto> findById(Long id) {
        return readModelRepository.findById(id).map(MaterializedItemReadStore::toDto);
    }

    @Override
    @Cacheable(cacheNames = LIST_CACHE, key = "#pageable")
    public PagedResult<ItemDto> findAll(Pageable pageable) {
        return PagedResult.of(readModelRepository.findAll(pageable), MaterializedItemReadStore::toDto);
    }

    @Override
    @Transactional
    public void onItemCreated(Long id, String name, String description) {
        readModelRepository.save(new ItemReadModel(id, name, description));
        byIdCache().put(id, new ItemDto(id, name, description));
        listCache().clear();
    }

    @Override
    @Transactional
    public void onItemDeleted(Long id) {
        // Kafka delivers at-least-once: a redelivered DELETED event must be a no-op, not
        // an EmptyResultDataAccessException from deleting an already-gone row.
        readModelRepository.findById(id).ifPresent(readModelRepository::delete);
        byIdCache().evict(id);
        listCache().clear();
    }

    private static ItemDto toDto(ItemReadModel row) {
        return new ItemDto(row.getId(), row.getName(), row.getDescription());
    }

    private Cache byIdCache() {
        return cacheManager.getCache(BY_ID_CACHE);
    }

    private Cache listCache() {
        return cacheManager.getCache(LIST_CACHE);
    }
}

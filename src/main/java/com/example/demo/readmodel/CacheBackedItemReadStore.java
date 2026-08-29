package com.example.demo.readmodel;

import com.example.demo.dto.ItemDto;
import com.example.demo.dto.PagedResult;
import com.example.demo.repository.ItemRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Reads straight from the "items" table (the same table the write path uses), fronted by
 * a cache that's refreshed by Kafka events rather than invalidated synchronously on
 * write. Default read-model implementation — see DECISIONS.md.
 *
 * The write-side methods (onItemCreated/onItemDeleted) go through CacheManager directly
 * rather than @CachePut/@CacheEvict: they're called from ItemEventConsumer with plain
 * (id, name, description) values, not a full Item, so there's no method return value for
 * declarative caching to key off.
 */
@Service
@ConditionalOnProperty(name = "app.cqrs.read-mode", havingValue = "cache", matchIfMissing = true)
public class CacheBackedItemReadStore implements ItemReadStore {

    private static final String BY_ID_CACHE = "items-by-id";
    private static final String LIST_CACHE = "items-list";

    private final ItemRepository itemRepository;
    private final CacheManager cacheManager;

    public CacheBackedItemReadStore(ItemRepository itemRepository, CacheManager cacheManager) {
        this.itemRepository = itemRepository;
        this.cacheManager = cacheManager;
    }

    @Override
    @Cacheable(cacheNames = BY_ID_CACHE, key = "#id")
    public Optional<ItemDto> findById(Long id) {
        return itemRepository.findById(id).map(ItemDto::from);
    }

    @Override
    @Cacheable(cacheNames = LIST_CACHE, key = "#pageable")
    public PagedResult<ItemDto> findAll(Pageable pageable) {
        return PagedResult.of(itemRepository.findAll(pageable), ItemDto::from);
    }

    @Override
    public void onItemCreated(Long id, String name, String description) {
        // @Cacheable unwraps Optional<T> return values before storing, so the raw DTO —
        // not Optional.of(dto) — is what belongs in the cache to match its shape.
        byIdCache().put(id, new ItemDto(id, name, description));
        listCache().clear();
    }

    @Override
    public void onItemDeleted(Long id) {
        byIdCache().evict(id);
        listCache().clear();
    }

    private Cache byIdCache() {
        return cacheManager.getCache(BY_ID_CACHE);
    }

    private Cache listCache() {
        return cacheManager.getCache(LIST_CACHE);
    }
}

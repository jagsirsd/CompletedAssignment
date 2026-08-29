package com.example.demo.readmodel;

import com.example.demo.dto.ItemDto;
import com.example.demo.entity.Item;
import com.example.demo.repository.ItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Cacheable / cache eviction only takes effect through the Spring AOP proxy Spring
 * creates around the bean, so this boots a minimal cache-enabled context rather than
 * instantiating CacheBackedItemReadStore directly.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = CacheBackedItemReadStoreTest.Config.class)
class CacheBackedItemReadStoreTest {

    @Configuration
    @EnableCaching
    static class Config {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("items-by-id", "items-list");
        }

        @Bean
        ItemRepository itemRepository() {
            return org.mockito.Mockito.mock(ItemRepository.class);
        }

        @Bean
        CacheBackedItemReadStore cacheBackedItemReadStore(ItemRepository itemRepository, CacheManager cacheManager) {
            return new CacheBackedItemReadStore(itemRepository, cacheManager);
        }
    }

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private ItemReadStore readStore;

    @Test
    void findById_secondCallIsServedFromCache() {
        Item item = new Item("Widget", "desc");
        setId(item, 1L);
        when(itemRepository.findById(1L)).thenReturn(Optional.of(item));

        assertThat(readStore.findById(1L)).contains(new ItemDto(1L, "Widget", "desc"));
        assertThat(readStore.findById(1L)).contains(new ItemDto(1L, "Widget", "desc"));

        verify(itemRepository, times(1)).findById(1L);
    }

    @Test
    void onItemCreated_warmsByIdCacheWithoutHittingRepository() {
        readStore.onItemCreated(2L, "Gadget", "desc2");

        assertThat(readStore.findById(2L)).contains(new ItemDto(2L, "Gadget", "desc2"));
        verify(itemRepository, times(0)).findById(2L);
    }

    @Test
    void onItemDeleted_evictsByIdAndListCaches() {
        Item item = new Item("Widget", "desc");
        setId(item, 3L);
        when(itemRepository.findById(3L)).thenReturn(Optional.of(item));
        Pageable pageable = PageRequest.of(0, 20);
        when(itemRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(item), pageable, 1));

        readStore.findById(3L);
        readStore.findAll(pageable);

        readStore.onItemDeleted(3L);

        when(itemRepository.findById(3L)).thenReturn(Optional.empty());
        assertThat(readStore.findById(3L)).isEmpty();
        verify(itemRepository, times(2)).findById(3L);

        readStore.findAll(pageable);
        verify(itemRepository, times(2)).findAll(pageable);
    }

    private static void setId(Item item, Long id) {
        try {
            var field = Item.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(item, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}

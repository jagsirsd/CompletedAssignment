package com.example.demo.readmodel;

import com.example.demo.dto.ItemDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = MaterializedItemReadStoreTest.Config.class)
class MaterializedItemReadStoreTest {

    @Configuration
    @EnableCaching
    static class Config {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("items-by-id", "items-list");
        }

        @Bean
        ItemReadModelRepository itemReadModelRepository() {
            return mock(ItemReadModelRepository.class);
        }

        @Bean
        MaterializedItemReadStore materializedItemReadStore(
                ItemReadModelRepository repository, CacheManager cacheManager) {
            return new MaterializedItemReadStore(repository, cacheManager);
        }
    }

    @Autowired
    private ItemReadModelRepository repository;

    @Autowired
    private ItemReadStore readStore;

    @Test
    void onItemCreated_persistsToReadTableAndWarmsCache() {
        readStore.onItemCreated(1L, "Widget", "desc");

        ArgumentCaptor<ItemReadModel> captor = ArgumentCaptor.forClass(ItemReadModel.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(1L);
        assertThat(captor.getValue().getName()).isEqualTo("Widget");
        assertThat(captor.getValue().getDescription()).isEqualTo("desc");

        assertThat(readStore.findById(1L)).contains(new ItemDto(1L, "Widget", "desc"));
        verify(repository, times(0)).findById(1L);
    }

    @Test
    void onItemDeleted_removesRowAndEvictsCache() {
        ItemReadModel row = new ItemReadModel(2L, "Gadget", "desc2");
        when(repository.findById(2L)).thenReturn(Optional.of(row));

        readStore.findById(2L);
        readStore.onItemDeleted(2L);

        verify(repository).delete(row);
        when(repository.findById(2L)).thenReturn(Optional.empty());
        assertThat(readStore.findById(2L)).isEmpty();
        // 1: readStore.findById above, 2: onItemDeleted's own lookup before deleting,
        // 3: this call, a genuine cache miss since onItemDeleted evicted the entry.
        verify(repository, times(3)).findById(2L);
    }

    @Test
    void onItemDeleted_redeliveredEvent_isNoOp() {
        when(repository.findById(3L)).thenReturn(Optional.empty());

        readStore.onItemDeleted(3L);

        verify(repository, times(0)).delete(org.mockito.ArgumentMatchers.any());
    }
}

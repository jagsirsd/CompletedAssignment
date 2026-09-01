package com.example.demo.readmodel;

import com.example.demo.dto.ItemDto;
import com.example.demo.dto.PagedResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class RedisCacheItemReadStore implements ItemReadStore {

    private static final String KEY_PREFIX = "item:";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper                  objectMapper;
    private final Duration                      ttl;
    private final MeterRegistry                 meterRegistry;
    private final Timer                         cacheWriteTimer;

    public RedisCacheItemReadStore(RedisTemplate<String, String> redisTemplate,
                                   ObjectMapper objectMapper,
                                   Duration itemsCacheTtl,
                                   MeterRegistry meterRegistry) {
        this.redisTemplate   = redisTemplate;
        this.objectMapper    = objectMapper;
        this.ttl             = itemsCacheTtl;
        this.meterRegistry   = meterRegistry;
        this.cacheWriteTimer = Timer.builder("cache.write.duration")
                .description("Time to serialize and write an item to Redis")
                .register(meterRegistry);
    }

    @Override
    public Optional<ItemDto> findById(Long id) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String json = redisTemplate.opsForValue().get(KEY_PREFIX + id);
        String result = json == null ? "miss" : "hit";
        sample.stop(Timer.builder("cache.read.duration")
                .description("Time to read an item from Redis")
                .tag("result", result)
                .register(meterRegistry));

        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, ItemDto.class));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    @Override
    public PagedResult<ItemDto> findAll(Pageable pageable) {
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return new PagedResult<>(List.of(), pageable.getPageNumber(),
                    pageable.getPageSize(), 0L, 0);
        }

        List<ItemDto> all = keys.stream()
                .map(key -> redisTemplate.opsForValue().get(key))
                .filter(json -> json != null)
                .map(json -> {
                    try { return objectMapper.readValue(json, ItemDto.class); }
                    catch (JsonProcessingException e) { return null; }
                })
                .filter(dto -> dto != null)
                .sorted(Comparator.comparingLong(ItemDto::id))
                .toList();

        long total     = all.size();
        int  fromIndex = (int) Math.min(pageable.getOffset(), total);
        int  toIndex   = (int) Math.min(fromIndex + pageable.getPageSize(), total);
        List<ItemDto> page = all.subList(fromIndex, toIndex);
        int totalPages = pageable.getPageSize() == 0 ? 0
                : (int) Math.ceil((double) total / pageable.getPageSize());

        return new PagedResult<>(page, pageable.getPageNumber(),
                pageable.getPageSize(), total, totalPages);
    }

    @Override
    public void onItemCreated(Long id, String name, String description) {
        store(id, name, description);
    }

    @Override
    public void onItemUpdated(Long id, String name, String description) {
        store(id, name, description);
    }

    @Override
    public void onItemDeleted(Long id) {
        redisTemplate.delete(KEY_PREFIX + id);
    }

    private void store(Long id, String name, String description) {
        try {
            String json = objectMapper.writeValueAsString(new ItemDto(id, name, description));
            cacheWriteTimer.record(() ->
                    redisTemplate.opsForValue().set(KEY_PREFIX + id, json, ttl));
        } catch (JsonProcessingException ignored) {
        }
    }
}

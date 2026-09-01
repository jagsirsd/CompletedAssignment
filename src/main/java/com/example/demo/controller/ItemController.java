package com.example.demo.controller;

import com.example.demo.dto.ItemDto;
import com.example.demo.dto.PagedResult;
import com.example.demo.entity.Item;
import com.example.demo.readmodel.ItemReadStore;
import com.example.demo.service.ItemCommandService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/items")
public class ItemController {

    private final ItemReadStore itemReadStore;
    private final ItemCommandService itemCommandService;

    public ItemController(ItemReadStore itemReadStore, ItemCommandService itemCommandService) {
        this.itemReadStore = itemReadStore;
        this.itemCommandService = itemCommandService;
    }

    @GetMapping
    public PagedResult<ItemDto> getAll(@PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return itemReadStore.findAll(pageable);
    }

    @PostMapping
    public Item create(@RequestBody Item item) {
        return itemCommandService.create(item, "rest");
    }

    @GetMapping("/{id}")
    public ResponseEntity<ItemDto> getById(@PathVariable Long id) {
        return itemReadStore.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!itemCommandService.delete(id, "rest")) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }
}

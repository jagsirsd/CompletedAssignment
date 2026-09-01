package com.example.demo.grpc;

import com.example.demo.dto.ItemDto;
import com.example.demo.dto.PagedResult;
import com.example.demo.entity.Item;
import com.example.demo.grpc.v1.ItemMessage;
import com.example.demo.grpc.v1.ListItemsRequest;
import com.example.demo.grpc.v1.ListItemsResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

final class ItemGrpcMapper {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String DEFAULT_SORT = "id";

    private ItemGrpcMapper() {}

    static ItemMessage toMessage(ItemDto dto) {
        return ItemMessage.newBuilder()
                .setId(dto.id())
                .setName(dto.name())
                .setDescription(dto.description() == null ? "" : dto.description())
                .build();
    }

    static ItemMessage toMessage(Item item) {
        return ItemMessage.newBuilder()
                .setId(item.getId())
                .setName(item.getName())
                .setDescription(item.getDescription() == null ? "" : item.getDescription())
                .build();
    }

    static ListItemsResponse toListResponse(PagedResult<ItemDto> paged) {
        ListItemsResponse.Builder builder = ListItemsResponse.newBuilder()
                .setPage(paged.page())
                .setSize(paged.size())
                .setTotalElements(paged.totalElements())
                .setTotalPages(paged.totalPages());
        paged.content().forEach(dto -> builder.addContent(toMessage(dto)));
        return builder.build();
    }

    static Pageable toPageable(ListItemsRequest request) {
        int page = Math.max(request.getPage(), 0);
        int size = request.getSize() <= 0 ? DEFAULT_PAGE_SIZE : Math.min(request.getSize(), MAX_PAGE_SIZE);
        String sortSpec = request.getSort().isBlank() ? DEFAULT_SORT : request.getSort();

        String[] parts = sortSpec.split(",", 2);
        String property = parts[0].trim();
        Sort.Direction direction = parts.length > 1 && parts[1].trim().equalsIgnoreCase("desc")
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;

        return PageRequest.of(page, size, Sort.by(direction, property));
    }
}

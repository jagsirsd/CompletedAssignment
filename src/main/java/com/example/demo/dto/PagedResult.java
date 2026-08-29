package com.example.demo.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

public record PagedResult<T>(List<T> content, int page, int size, long totalElements, int totalPages)
        implements java.io.Serializable {

    public static <S, T> PagedResult<T> of(Page<S> page, Function<S, T> mapper) {
        return new PagedResult<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}

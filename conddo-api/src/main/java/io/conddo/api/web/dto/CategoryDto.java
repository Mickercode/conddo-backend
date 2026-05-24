package io.conddo.api.web.dto;

import io.conddo.core.domain.ProductCategory;

import java.util.UUID;

/** An inventory category (§11.6). */
public record CategoryDto(UUID id, String name) {

    public static CategoryDto from(ProductCategory c) {
        return new CategoryDto(c.getId(), c.getName());
    }
}

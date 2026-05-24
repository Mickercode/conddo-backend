package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.UUID;

/** Create an inventory product (§11.6). */
public record CreateProductRequest(
        @NotBlank String name,
        String sku,
        UUID categoryId,
        BigDecimal price,
        Integer stock,
        Integer reorderThreshold,
        Boolean active
) {
}

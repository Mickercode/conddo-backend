package io.conddo.api.web.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** PATCH an inventory product (§11.6). All optional — only sent fields change. */
public record UpdateProductRequest(
        String name,
        String sku,
        UUID categoryId,
        BigDecimal price,
        Integer stock,
        Integer reorderThreshold,
        Boolean active
) {
}

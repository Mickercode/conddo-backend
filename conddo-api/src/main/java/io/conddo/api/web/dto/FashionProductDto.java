package io.conddo.api.web.dto;

import io.conddo.core.service.FashionProductService;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Fashion product DTO with size/color/material variants.
 */
public record FashionProductDto(
        UUID id,
        String name,
        String sku,
        String category,
        String material,
        BigDecimal basePrice,
        int totalStock,
        boolean active,
        List<VariantDto> variants,
        boolean hasLowStock
) {
    public static FashionProductDto from(FashionProductService.FashionProductView view) {
        return new FashionProductDto(
                view.id(),
                view.name(),
                view.sku(),
                view.category(),
                view.material(),
                view.basePrice(),
                view.totalStock(),
                view.active(),
                view.variants().stream().map(v -> new VariantDto(v.size(), v.color(), v.stock())).toList(),
                view.hasLowStock()
        );
    }
}

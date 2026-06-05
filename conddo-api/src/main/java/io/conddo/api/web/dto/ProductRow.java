package io.conddo.api.web.dto;

import io.conddo.core.domain.Product;
import io.conddo.core.service.InventoryService.ProductView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A product row / detail (§11.6). {@code lowStock} is derived from stock vs reorder
 * threshold. {@code expiryDate} + {@code batchNumber} are pharmacy-specific
 * (PHARMACY_DEEP_DIVE_SPEC §2) — null on non-pharmacy products so the existing
 * FE rendering stays unchanged.
 */
public record ProductRow(
        UUID id,
        String name,
        String sku,
        UUID categoryId,
        String category,
        BigDecimal price,
        int stock,
        int reorderThreshold,
        boolean lowStock,
        boolean active,
        LocalDate expiryDate,
        String batchNumber
) {
    public static ProductRow from(ProductView v) {
        Product p = v.product();
        return new ProductRow(p.getId(), p.getName(), p.getSku(), p.getCategoryId(), v.categoryName(),
                p.getPrice(), p.getStock(), p.getReorderThreshold(), p.isLowStock(), p.isActive(),
                p.getExpiryDate(), p.getBatchNumber());
    }
}

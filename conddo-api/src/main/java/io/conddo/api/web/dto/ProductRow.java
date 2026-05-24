package io.conddo.api.web.dto;

import io.conddo.core.domain.Product;
import io.conddo.core.service.InventoryService.ProductView;

import java.math.BigDecimal;
import java.util.UUID;

/** A product row / detail (§11.6). {@code lowStock} is derived from stock vs reorder threshold. */
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
        boolean active
) {
    public static ProductRow from(ProductView v) {
        Product p = v.product();
        return new ProductRow(p.getId(), p.getName(), p.getSku(), p.getCategoryId(), v.categoryName(),
                p.getPrice(), p.getStock(), p.getReorderThreshold(), p.isLowStock(), p.isActive());
    }
}

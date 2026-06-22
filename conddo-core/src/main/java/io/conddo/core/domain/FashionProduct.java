package io.conddo.core.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Fashion-specific product with size/color/material variants (Fashion vertical).
 * Tenant-scoped via RLS. Extends the generic Product concept with fashion-specific
 * attributes like shoe sizes, colors, and materials.
 */
@Entity
@Table(name = "fashion_products")
public class FashionProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    private String sku;

    @Column(name = "category", nullable = false)
    private String category; // Sneakers, Formal, Casual, Boots, Sandals, Loafers, Athletic

    @Column(name = "material", nullable = false)
    private String material; // Leather, Suede, Canvas, Synthetic, Textile, Rubber

    @Column(nullable = false)
    private BigDecimal basePrice = BigDecimal.ZERO;

    @Column(name = "total_stock", nullable = false)
    private int totalStock = 0;

    @Column(nullable = false)
    private boolean active = true;

    /** Size/color variants stored as JSON. Each variant has size, color, and stock. */
    @JdbcTypeCode(SqlTypes.JSON)
    private List<SizeColorVariant> variants;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected FashionProduct() {
    }

    public FashionProduct(UUID tenantId, String name, String sku, String category, 
                          String material, BigDecimal basePrice, List<SizeColorVariant> variants) {
        this.tenantId = tenantId;
        this.name = name;
        this.sku = sku;
        this.category = category;
        this.material = material;
        this.basePrice = basePrice == null ? BigDecimal.ZERO : basePrice;
        this.variants = variants;
        this.totalStock = variants != null ? variants.stream().mapToInt(SizeColorVariant::getStock).sum() : 0;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public String getSku() {
        return sku;
    }

    public String getCategory() {
        return category;
    }

    public String getMaterial() {
        return material;
    }

    public BigDecimal getBasePrice() {
        return basePrice;
    }

    public int getTotalStock() {
        return totalStock;
    }

    public boolean isActive() {
        return active;
    }

    public List<SizeColorVariant> getVariants() {
        return variants;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public void setMaterial(String material) {
        this.material = material;
    }

    public void setBasePrice(BigDecimal basePrice) {
        if (basePrice != null) {
            this.basePrice = basePrice;
        }
    }

    public void setVariants(List<SizeColorVariant> variants) {
        this.variants = variants;
        this.totalStock = variants != null ? variants.stream().mapToInt(SizeColorVariant::getStock).sum() : 0;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    /** Adjust stock for a specific size/color variant. */
    public void adjustVariantStock(String size, String color, int delta) {
        if (variants != null) {
            for (SizeColorVariant variant : variants) {
                if (variant.getSize().equals(size) && variant.getColor().equals(color)) {
                    variant.setStock(Math.max(0, variant.getStock() + delta));
                    break;
                }
            }
            this.totalStock = variants.stream().mapToInt(SizeColorVariant::getStock).sum();
        }
    }

    /** Check if any variant is low on stock (stock < 5). */
    public boolean hasLowStock() {
        if (variants == null) return false;
        return variants.stream().anyMatch(v -> v.getStock() < 5);
    }

    /** Inner class for size/color variant stored as JSON. */
    @Embeddable
    public static class SizeColorVariant {
        @Column(name = "size")
        private String size;

        @Column(name = "color")
        private String color;

        @Column(name = "stock")
        private int stock;

        protected SizeColorVariant() {
        }

        public SizeColorVariant(String size, String color, int stock) {
            this.size = size;
            this.color = color;
            this.stock = Math.max(0, stock);
        }

        public String getSize() {
            return size;
        }

        public String getColor() {
            return color;
        }

        public int getStock() {
            return stock;
        }

        public void setStock(int stock) {
            this.stock = Math.max(0, stock);
        }
    }
}

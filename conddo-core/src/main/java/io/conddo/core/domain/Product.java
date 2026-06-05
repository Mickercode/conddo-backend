package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An inventory product/service (§11.6). Tenant-scoped via RLS. A product is
 * "low stock" when it has a positive {@code reorderThreshold} and {@code stock}
 * has fallen to or below it — that feeds the dashboard low-stock KPI.
 */
@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    private String sku;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(nullable = false)
    private BigDecimal price = BigDecimal.ZERO;

    @Column(nullable = false)
    private int stock = 0;

    @Column(name = "reorder_threshold", nullable = false)
    private int reorderThreshold = 0;

    @Column(nullable = false)
    private boolean active = true;

    /** Expiry-aware inventory (pharmacy). Null = "not tracked" — most non-pharmacy verticals. */
    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    /** Batch / lot number associated with the current stock. Informational at V1; Phase 2 splits into a batches table. */
    @Column(name = "batch_number")
    private String batchNumber;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected Product() {
    }

    public Product(UUID tenantId, String name, String sku, UUID categoryId,
                   BigDecimal price, int stock, int reorderThreshold) {
        this.tenantId = tenantId;
        this.name = name;
        this.sku = sku;
        this.categoryId = categoryId;
        this.price = price == null ? BigDecimal.ZERO : price;
        this.stock = Math.max(0, stock);
        this.reorderThreshold = Math.max(0, reorderThreshold);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSku() {
        return sku;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public int getStock() {
        return stock;
    }

    public int getReorderThreshold() {
        return reorderThreshold;
    }

    public boolean isActive() {
        return active;
    }

    /** True when a reorder threshold is set and stock has reached it. */
    public boolean isLowStock() {
        return reorderThreshold > 0 && stock <= reorderThreshold;
    }

    // ----- mutators (PATCH applies only the fields it was given) -------------

    public void rename(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
    }

    public void setPrice(BigDecimal price) {
        if (price != null) {
            this.price = price;
        }
    }

    public void setStock(int stock) {
        this.stock = Math.max(0, stock);
    }

    /** Applies a signed delta, clamping at zero. */
    public void adjustStock(int delta) {
        this.stock = Math.max(0, this.stock + delta);
    }

    public void setReorderThreshold(int reorderThreshold) {
        this.reorderThreshold = Math.max(0, reorderThreshold);
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    /** PATCH semantics: callers explicitly pass null to clear (PrescriptionRequest sends null). */
    public void setExpiryDate(LocalDate expiryDate) {
        this.expiryDate = expiryDate;
    }

    public String getBatchNumber() {
        return batchNumber;
    }

    public void setBatchNumber(String batchNumber) {
        this.batchNumber = batchNumber;
    }
}

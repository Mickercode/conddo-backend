package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One cart line. Snapshots {@code unitPrice} + {@code productName} at
 * add-time so later price/name edits don't retroactively change the
 * receipt. Same-product re-adds increment {@code qty} rather than
 * inserting a duplicate row (enforced by a unique index on
 * {@code (sale_id, product_id)}).
 */
@Entity
@Table(name = "pos_sale_items")
public class PosSaleItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "sale_id", nullable = false)
    private UUID saleId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(length = 80)
    private String sku;

    @Column(nullable = false)
    private int qty;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "line_total", nullable = false)
    private BigDecimal lineTotal;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected PosSaleItem() {
    }

    public PosSaleItem(UUID tenantId, UUID saleId, UUID productId, String productName,
                       String sku, int qty, BigDecimal unitPrice) {
        this.tenantId = tenantId;
        this.saleId = saleId;
        this.productId = productId;
        this.productName = productName;
        this.sku = sku;
        this.qty = qty;
        this.unitPrice = unitPrice;
        this.lineTotal = unitPrice.multiply(BigDecimal.valueOf(qty));
    }

    public void setQty(int qty) {
        this.qty = qty;
        this.lineTotal = this.unitPrice.multiply(BigDecimal.valueOf(qty));
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getSaleId() { return saleId; }
    public UUID getProductId() { return productId; }
    public String getProductName() { return productName; }
    public String getSku() { return sku; }
    public int getQty() { return qty; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getLineTotal() { return lineTotal; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}

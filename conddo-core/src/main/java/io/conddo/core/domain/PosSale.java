package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One POS transaction. Lifecycle: OPEN (cart being built) →
 * COMPLETED or VOIDED. Inventory only leaves on complete; OPEN
 * carries no stock reservation in Phase 1.
 */
@Entity
@Table(name = "pos_sales")
public class PosSale {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_VOIDED = "VOIDED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "cashier_id", nullable = false)
    private UUID cashierId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(nullable = false, length = 20)
    private String status = STATUS_OPEN;

    @Column(name = "sale_number", nullable = false, length = 40)
    private String saleNumber;

    @Column(nullable = false)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal total = BigDecimal.ZERO;

    @Column(name = "opened_at", nullable = false)
    private OffsetDateTime openedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "voided_at")
    private OffsetDateTime voidedAt;

    protected PosSale() {
    }

    public PosSale(UUID tenantId, UUID sessionId, UUID cashierId, UUID customerId,
                   String saleNumber, OffsetDateTime openedAt) {
        this.tenantId = tenantId;
        this.sessionId = sessionId;
        this.cashierId = cashierId;
        this.customerId = customerId;
        this.saleNumber = saleNumber;
        this.openedAt = openedAt;
    }

    public void markCompleted(OffsetDateTime when) {
        this.status = STATUS_COMPLETED;
        this.completedAt = when;
    }

    public void markVoided(OffsetDateTime when) {
        this.status = STATUS_VOIDED;
        this.voidedAt = when;
    }

    public void recomputeTotals(BigDecimal subtotal) {
        this.subtotal = subtotal;
        // Phase 1: total == subtotal (no discount, no tax). Phase 2 splits.
        this.total = subtotal;
    }

    public void setCustomerId(UUID customerId) {
        this.customerId = customerId;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getSessionId() { return sessionId; }
    public UUID getCashierId() { return cashierId; }
    public UUID getCustomerId() { return customerId; }
    public String getStatus() { return status; }
    public String getSaleNumber() { return saleNumber; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getTotal() { return total; }
    public OffsetDateTime getOpenedAt() { return openedAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public OffsetDateTime getVoidedAt() { return voidedAt; }
}

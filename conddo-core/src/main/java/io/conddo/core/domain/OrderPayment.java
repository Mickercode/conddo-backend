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

/** A payment recorded against an {@link Order} (§11.4). Tenant-scoped via RLS. */
@Entity
@Table(name = "order_payments")
public class OrderPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(nullable = false)
    private BigDecimal amount;

    private String method;

    private String note;

    @Column(name = "paid_at", nullable = false)
    private OffsetDateTime paidAt = OffsetDateTime.now();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected OrderPayment() {
    }

    public OrderPayment(UUID tenantId, UUID orderId, BigDecimal amount, String method, String note) {
        this.tenantId = tenantId;
        this.orderId = orderId;
        this.amount = amount;
        this.method = method;
        this.note = note;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getMethod() {
        return method;
    }

    public String getNote() {
        return note;
    }

    public OffsetDateTime getPaidAt() {
        return paidAt;
    }
}

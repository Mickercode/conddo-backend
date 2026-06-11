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
 * One tender against a sale. Splits are first-class — a sale may
 * carry many payments of mixed methods. CARD is reserved for Phase 2;
 * v1 only accepts CASH + TRANSFER through the controller.
 */
@Entity
@Table(name = "pos_payments")
public class PosPayment {

    public static final String METHOD_CASH = "CASH";
    public static final String METHOD_TRANSFER = "TRANSFER";
    public static final String METHOD_CARD = "CARD";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "sale_id", nullable = false)
    private UUID saleId;

    @Column(nullable = false, length = 30)
    private String method;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(length = 120)
    private String reference;

    @Column(name = "paid_at", nullable = false)
    private OffsetDateTime paidAt;

    protected PosPayment() {
    }

    public PosPayment(UUID tenantId, UUID saleId, String method, BigDecimal amount,
                      String reference, OffsetDateTime paidAt) {
        this.tenantId = tenantId;
        this.saleId = saleId;
        this.method = method;
        this.amount = amount;
        this.reference = reference;
        this.paidAt = paidAt;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getSaleId() { return saleId; }
    public String getMethod() { return method; }
    public BigDecimal getAmount() { return amount; }
    public String getReference() { return reference; }
    public OffsetDateTime getPaidAt() { return paidAt; }
}

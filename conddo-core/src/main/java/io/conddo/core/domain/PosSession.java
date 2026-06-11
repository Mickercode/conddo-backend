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
 * Cashier shift (POS Phase 1). Exactly one OPEN session per
 * {@code (tenant, cashier)} at a time, enforced by a partial unique
 * index. Closing computes {@code expectedCash = openingFloat + ΣCASH}
 * and writes the variance against the cashier's hand-counted total.
 */
@Entity
@Table(name = "pos_sessions")
public class PosSession {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CLOSED = "CLOSED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "cashier_id", nullable = false)
    private UUID cashierId;

    @Column(nullable = false, length = 20)
    private String status = STATUS_OPEN;

    @Column(name = "opening_float", nullable = false)
    private BigDecimal openingFloat = BigDecimal.ZERO;

    @Column(name = "counted_cash")
    private BigDecimal countedCash;

    @Column(name = "expected_cash")
    private BigDecimal expectedCash;

    @Column(name = "cash_variance")
    private BigDecimal cashVariance;

    private String notes;

    @Column(name = "opened_at", nullable = false)
    private OffsetDateTime openedAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    protected PosSession() {
    }

    public PosSession(UUID tenantId, UUID cashierId, BigDecimal openingFloat, String notes,
                      OffsetDateTime openedAt) {
        this.tenantId = tenantId;
        this.cashierId = cashierId;
        this.openingFloat = openingFloat == null ? BigDecimal.ZERO : openingFloat;
        this.notes = notes;
        this.openedAt = openedAt;
    }

    public void close(BigDecimal expectedCash, BigDecimal countedCash, String closingNotes,
                      OffsetDateTime when) {
        this.status = STATUS_CLOSED;
        this.expectedCash = expectedCash;
        this.countedCash = countedCash;
        this.cashVariance = countedCash.subtract(expectedCash);
        if (closingNotes != null && !closingNotes.isBlank()) {
            this.notes = this.notes == null ? closingNotes : this.notes + " | " + closingNotes;
        }
        this.closedAt = when;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getCashierId() { return cashierId; }
    public String getStatus() { return status; }
    public BigDecimal getOpeningFloat() { return openingFloat; }
    public BigDecimal getCountedCash() { return countedCash; }
    public BigDecimal getExpectedCash() { return expectedCash; }
    public BigDecimal getCashVariance() { return cashVariance; }
    public String getNotes() { return notes; }
    public OffsetDateTime getOpenedAt() { return openedAt; }
    public OffsetDateTime getClosedAt() { return closedAt; }
}

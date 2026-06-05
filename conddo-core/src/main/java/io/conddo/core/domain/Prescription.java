package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * A prescription on a tenant's books (PHARMACY_DEEP_DIVE_SPEC §1). Tenant-scoped:
 * RLS scopes every read/write so application code never filters by tenant_id.
 *
 * <p>{@code next_refill_due} is recomputed in the service layer on every write
 * (Postgres generated columns can't reference the CASE on nullable timestamps
 * the spec needs portably). The stored value powers fast list filters
 * ({@code due_soon}, {@code overdue}) without a per-row CASE.
 *
 * <p>{@code refill_interval_days = null} means "one-off" — never auto-derives a
 * {@code next_refill_due}.
 */
@Entity
@Table(name = "prescriptions")
public class Prescription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(nullable = false)
    private String medication;

    @Column
    private String dosage;

    @Column
    private Integer quantity;

    @Column(name = "refill_interval_days")
    private Integer refillIntervalDays;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "issued_at", nullable = false)
    private OffsetDateTime issuedAt = OffsetDateTime.now();

    @Column(name = "last_filled_at")
    private OffsetDateTime lastFilledAt;

    @Column(name = "next_refill_due")
    private LocalDate nextRefillDue;

    /** 12-hour reminder de-dupe. Null when no reminder has ever been sent. */
    @Column(name = "last_reminded_at")
    private OffsetDateTime lastRemindedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected Prescription() {
    }

    public Prescription(UUID tenantId, UUID customerId, String medication, String dosage,
                        Integer quantity, Integer refillIntervalDays, String notes) {
        this.tenantId = tenantId;
        this.customerId = customerId;
        this.medication = medication;
        this.dosage = dosage;
        this.quantity = quantity;
        this.refillIntervalDays = refillIntervalDays;
        this.notes = notes;
        recomputeNextRefillDue();
    }

    /**
     * Recompute {@code next_refill_due} per the spec:
     * <ul>
     *   <li>{@code refill_interval_days IS NULL} → {@code null} (one-off)</li>
     *   <li>{@code last_filled_at IS NOT NULL} → {@code last_filled_at::date + refill_interval_days}</li>
     *   <li>else → {@code issued_at::date + refill_interval_days}</li>
     * </ul>
     * Called from the constructor and from any mutator that touches an input
     * to the derivation.
     */
    public void recomputeNextRefillDue() {
        if (refillIntervalDays == null) {
            this.nextRefillDue = null;
            return;
        }
        LocalDate base = (lastFilledAt != null ? lastFilledAt : issuedAt)
                .atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        this.nextRefillDue = base.plusDays(refillIntervalDays);
    }

    /** Idempotent fill — stamps {@code last_filled_at} and recomputes the next due date. */
    public void markFilled(OffsetDateTime filledAt) {
        this.lastFilledAt = filledAt;
        recomputeNextRefillDue();
    }

    public void recordReminder(OffsetDateTime sentAt) {
        this.lastRemindedAt = sentAt;
    }

    // ----- mutators (PATCH-style — only touch when value provided) -----------

    public void setMedication(String medication) {
        if (medication != null && !medication.isBlank()) {
            this.medication = medication;
        }
    }

    public void setDosage(String dosage) {
        this.dosage = dosage;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public void setRefillIntervalDays(Integer refillIntervalDays) {
        this.refillIntervalDays = refillIntervalDays;
        recomputeNextRefillDue();
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    // ----- getters -----------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getMedication() {
        return medication;
    }

    public String getDosage() {
        return dosage;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public Integer getRefillIntervalDays() {
        return refillIntervalDays;
    }

    public String getNotes() {
        return notes;
    }

    public OffsetDateTime getIssuedAt() {
        return issuedAt;
    }

    public OffsetDateTime getLastFilledAt() {
        return lastFilledAt;
    }

    public LocalDate getNextRefillDue() {
        return nextRefillDue;
    }

    public OffsetDateTime getLastRemindedAt() {
        return lastRemindedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}

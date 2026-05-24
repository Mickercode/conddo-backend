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
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An appointment in a tenant's calendar (§11.5). Tenant-scoped: every row
 * carries tenant_id and is protected by the {@code tenant_isolation} RLS policy.
 * {@code mode} is in_person/virtual; {@code status} is confirmed/pending/
 * cancelled/completed (public self-bookings land as {@code pending}).
 */
@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "customer_name")
    private String customerName;

    private String service;

    @Column(name = "starts_at", nullable = false)
    private OffsetDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private OffsetDateTime endsAt;

    @Column(nullable = false)
    private String mode = "in_person";

    @Column(nullable = false)
    private String status = "confirmed";

    @Column(nullable = false)
    private BigDecimal amount = BigDecimal.ZERO;

    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected Booking() {
    }

    public Booking(UUID tenantId, UUID customerId, String customerName, String service,
                   OffsetDateTime startsAt, OffsetDateTime endsAt, String mode, String status) {
        this.tenantId = tenantId;
        this.customerId = customerId;
        this.customerName = customerName;
        this.service = service;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        if (mode != null && !mode.isBlank()) {
            this.mode = mode;
        }
        if (status != null && !status.isBlank()) {
            this.status = status;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getService() {
        return service;
    }

    public OffsetDateTime getStartsAt() {
        return startsAt;
    }

    public OffsetDateTime getEndsAt() {
        return endsAt;
    }

    public String getMode() {
        return mode;
    }

    public String getStatus() {
        return status;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getNotes() {
        return notes;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    // ----- mutators (PATCH applies only the fields it was given) -------------

    public void reschedule(OffsetDateTime startsAt, OffsetDateTime endsAt) {
        if (startsAt != null) {
            this.startsAt = startsAt;
        }
        if (endsAt != null) {
            this.endsAt = endsAt;
        }
    }

    public void setService(String service) {
        if (service != null) {
            this.service = service;
        }
    }

    public void setMode(String mode) {
        if (mode != null && !mode.isBlank()) {
            this.mode = mode;
        }
    }

    public void setStatus(String status) {
        if (status != null && !status.isBlank()) {
            this.status = status;
        }
    }

    public void setAmount(BigDecimal amount) {
        if (amount != null) {
            this.amount = amount;
        }
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}

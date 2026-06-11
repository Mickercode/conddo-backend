package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "pharmacy_program_enrollments")
public class PharmacyProgramEnrollment {

    public static final String STATUS_PENDING_PAYMENT = "PENDING_PAYMENT";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "program_id", nullable = false)
    private UUID programId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(nullable = false, length = 30)
    private String status = STATUS_PENDING_PAYMENT;

    @Column(name = "paystack_subscription_code", length = 64)
    private String paystackSubscriptionCode;

    @CreationTimestamp
    @Column(name = "enrolled_at", updatable = false)
    private OffsetDateTime enrolledAt;

    @Column(name = "next_billing_at")
    private OffsetDateTime nextBillingAt;

    @Column(name = "ends_at")
    private OffsetDateTime endsAt;

    @Column(name = "enrolled_by")
    private UUID enrolledBy;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected PharmacyProgramEnrollment() {
    }

    public PharmacyProgramEnrollment(UUID tenantId, UUID programId, UUID customerId,
                                      OffsetDateTime endsAt, UUID enrolledBy) {
        this.tenantId = tenantId;
        this.programId = programId;
        this.customerId = customerId;
        this.endsAt = endsAt;
        this.enrolledBy = enrolledBy;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getProgramId() {
        return programId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getStatus() {
        return status;
    }

    public String getPaystackSubscriptionCode() {
        return paystackSubscriptionCode;
    }

    public OffsetDateTime getEnrolledAt() {
        return enrolledAt;
    }

    public OffsetDateTime getNextBillingAt() {
        return nextBillingAt;
    }

    public OffsetDateTime getEndsAt() {
        return endsAt;
    }

    public UUID getEnrolledBy() {
        return enrolledBy;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void activate(String subscriptionCode, OffsetDateTime nextBillingAt) {
        this.status = STATUS_ACTIVE;
        this.paystackSubscriptionCode = subscriptionCode;
        this.nextBillingAt = nextBillingAt;
    }

    public void pause() {
        this.status = STATUS_PAUSED;
    }

    public void cancel() {
        this.status = STATUS_CANCELLED;
    }
}

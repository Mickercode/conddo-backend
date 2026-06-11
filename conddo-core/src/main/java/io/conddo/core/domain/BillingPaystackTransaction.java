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

/**
 * One Paystack-initialised checkout for a Conddo plan billing
 * upgrade (HANDOFF_2026-06-11 §8). The {@code reference} is the
 * source-of-truth identifier — used by the FE polling
 * {@code /billing/verify} and by the webhook handler.
 */
@Entity
@Table(name = "billing_paystack_transactions")
public class BillingPaystackTransaction {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_ABANDONED = "abandoned";

    public static final String PURPOSE_PLAN_UPGRADE = "PLAN_UPGRADE";
    public static final String PURPOSE_PROGRAM_ENROLLMENT = "PROGRAM_ENROLLMENT";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 80, unique = true)
    private String reference;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "billing_cycle", nullable = false, length = 20)
    private String billingCycle;

    @Column(name = "amount_kobo", nullable = false)
    private long amountKobo;

    @Column(nullable = false, length = 20)
    private String status = STATUS_PENDING;

    @Column(name = "paystack_subscription_code", length = 64)
    private String paystackSubscriptionCode;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "initiated_by")
    private UUID initiatedBy;

    @Column(nullable = false, length = 30)
    private String purpose = PURPOSE_PLAN_UPGRADE;

    @Column(name = "enrollment_id")
    private UUID enrollmentId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected BillingPaystackTransaction() {
    }

    public BillingPaystackTransaction(UUID tenantId, String reference, UUID planId,
                                       String billingCycle, long amountKobo, UUID initiatedBy) {
        this.tenantId = tenantId;
        this.reference = reference;
        this.planId = planId;
        this.billingCycle = billingCycle;
        this.amountKobo = amountKobo;
        this.initiatedBy = initiatedBy;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getReference() {
        return reference;
    }

    public UUID getPlanId() {
        return planId;
    }

    public String getBillingCycle() {
        return billingCycle;
    }

    public long getAmountKobo() {
        return amountKobo;
    }

    public String getStatus() {
        return status;
    }

    public String getPaystackSubscriptionCode() {
        return paystackSubscriptionCode;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public OffsetDateTime getPaidAt() {
        return paidAt;
    }

    public UUID getInitiatedBy() {
        return initiatedBy;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public UUID getEnrollmentId() {
        return enrollmentId;
    }

    public void setEnrollmentId(UUID enrollmentId) {
        this.enrollmentId = enrollmentId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void markSuccess(OffsetDateTime paidAt, String subscriptionCode) {
        this.status = STATUS_SUCCESS;
        this.paidAt = paidAt;
        this.paystackSubscriptionCode = subscriptionCode;
        this.failureReason = null;
    }

    public void markFailed(String reason) {
        this.status = STATUS_FAILED;
        this.failureReason = reason;
    }

    public void markAbandoned() {
        this.status = STATUS_ABANDONED;
    }
}

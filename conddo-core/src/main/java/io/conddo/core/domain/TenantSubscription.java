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
 * One row per tenant subscription (BILLING_TIERS_SPEC §1). The partial
 * unique index {@code idx_tenant_active_sub} guarantees at most one row
 * per tenant in the {@code trialing / active / grace} statuses;
 * historical rows are kept for audit and re-activation.
 *
 * <p>State machine:
 * {@code trialing → active} (on first paid renewal) →
 * {@code active → grace} (on expires_at past) →
 * {@code grace → expired} (after grace period elapses) →
 * {@code * → cancelled} (on tenant explicit cancel — keeps access until expires_at).
 */
@Entity
@Table(name = "tenant_subscriptions")
public class TenantSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    /** {@code monthly} / {@code quarterly} / {@code custom} (Scaler). */
    @Column(name = "billing_cycle", nullable = false)
    private String billingCycle;

    /** {@code trialing} / {@code active} / {@code grace} / {@code expired} / {@code cancelled}. */
    @Column(nullable = false)
    private String status;

    /** Kobo — running total paid (sum of renewal charges). */
    @Column(name = "amount_paid", nullable = false)
    private int amountPaid = 0;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "trial_ends_at")
    private OffsetDateTime trialEndsAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected TenantSubscription() {
    }

    public TenantSubscription(UUID tenantId, UUID planId, String billingCycle, String status,
                              OffsetDateTime startedAt, OffsetDateTime expiresAt,
                              OffsetDateTime trialEndsAt) {
        this.tenantId = tenantId;
        this.planId = planId;
        this.billingCycle = billingCycle;
        this.status = status;
        this.startedAt = startedAt;
        this.expiresAt = expiresAt;
        this.trialEndsAt = trialEndsAt;
    }

    // ----- state-machine helpers ---------------------------------------------

    public void activate(int paidKobo) {
        this.status = "active";
        this.amountPaid = Math.max(0, this.amountPaid + paidKobo);
    }

    public void enterGrace() {
        this.status = "grace";
    }

    public void expire() {
        this.status = "expired";
    }

    public void cancel(OffsetDateTime at) {
        this.cancelledAt = at;
        // Access continues until expires_at; status flips to 'cancelled' later via the grace/expired sweeper.
    }

    public void completeCancellation() {
        this.status = "cancelled";
    }

    public void extendExpiry(OffsetDateTime newExpiry) {
        if (newExpiry != null && newExpiry.isAfter(this.expiresAt)) {
            this.expiresAt = newExpiry;
        }
    }

    public boolean isWritable() {
        // Read-only states: cancelled (after access ends), expired.
        // Grace is technically writable per the spec — the FE shows a warning banner.
        return "trialing".equals(status) || "active".equals(status) || "grace".equals(status);
    }

    // ----- getters -----------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getPlanId() {
        return planId;
    }

    public String getBillingCycle() {
        return billingCycle;
    }

    public String getStatus() {
        return status;
    }

    public int getAmountPaid() {
        return amountPaid;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public OffsetDateTime getTrialEndsAt() {
        return trialEndsAt;
    }

    public OffsetDateTime getCancelledAt() {
        return cancelledAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}

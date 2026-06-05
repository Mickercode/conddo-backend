package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One plan in the catalog (BILLING_TIERS_SPEC §1). Three rows seeded by
 * V24: launcher, growth, scaler. Prices in <b>Kobo</b> on the wire to
 * the DB; the wire shape to the FE is in <b>Naira</b> — converted in the
 * response builder.
 *
 * <p>Scaler rows have {@code monthlyPrice} set but {@code quarterlyPrice}
 * null + {@code isCustom = true} — the FE shows "Contact sales" instead
 * of a quarterly price chip.
 */
@Entity
@Table(name = "subscription_plans")
public class SubscriptionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Canonical plan name — {@code launcher} / {@code growth} / {@code scaler}. */
    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "monthly_price")
    private Integer monthlyPrice;

    @Column(name = "quarterly_price")
    private Integer quarterlyPrice;

    @Column(name = "is_custom", nullable = false)
    private boolean custom = false;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected SubscriptionPlan() {
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Integer getMonthlyPrice() {
        return monthlyPrice;
    }

    public Integer getQuarterlyPrice() {
        return quarterlyPrice;
    }

    public boolean isCustom() {
        return custom;
    }

    public boolean isActive() {
        return active;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}

package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * One feature-flag row tied to a plan (BILLING_TIERS_SPEC §1 + §5). Values
 * are stored as strings so the same column carries booleans
 * ({@code "true" / "false"}), counts ({@code "5"}), and the sentinel
 * {@code "unlimited"}. The {@code (plan_id, feature_key)} pair is unique.
 */
@Entity
@Table(name = "plan_features",
        uniqueConstraints = @UniqueConstraint(name = "uk_plan_feature",
                columnNames = {"plan_id", "feature_key"}))
public class PlanFeature {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "feature_key", nullable = false)
    private String featureKey;

    @Column(name = "feature_value")
    private String featureValue;

    protected PlanFeature() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getPlanId() {
        return planId;
    }

    public String getFeatureKey() {
        return featureKey;
    }

    public String getFeatureValue() {
        return featureValue;
    }
}

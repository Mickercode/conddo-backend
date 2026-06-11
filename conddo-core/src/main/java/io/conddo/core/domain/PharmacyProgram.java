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
 * Chronic-care subscription program (Pharmacy Roadmap Beta 3). The
 * pharmacist defines a bundle (medication + price + duration) the
 * customer subscribes to via Paystack.
 */
@Entity
@Table(name = "pharmacy_programs")
public class PharmacyProgram {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 150)
    private String name;

    private String description;

    @Column(name = "target_condition", length = 120)
    private String targetCondition;

    @Column(name = "duration_months")
    private Integer durationMonths;

    @Column(name = "monthly_price", nullable = false)
    private BigDecimal monthlyPrice;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "is_published", nullable = false)
    private boolean published = false;

    @Column(name = "paystack_plan_code", length = 64)
    private String paystackPlanCode;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected PharmacyProgram() {
    }

    public PharmacyProgram(UUID tenantId, String name, BigDecimal monthlyPrice, UUID createdBy) {
        this.tenantId = tenantId;
        this.name = name;
        this.monthlyPrice = monthlyPrice;
        this.createdBy = createdBy;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getTargetCondition() {
        return targetCondition;
    }

    public Integer getDurationMonths() {
        return durationMonths;
    }

    public BigDecimal getMonthlyPrice() {
        return monthlyPrice;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isPublished() {
        return published;
    }

    public String getPaystackPlanCode() {
        return paystackPlanCode;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setTargetCondition(String targetCondition) {
        this.targetCondition = targetCondition;
    }

    public void setDurationMonths(Integer durationMonths) {
        this.durationMonths = durationMonths;
    }

    public void setMonthlyPrice(BigDecimal monthlyPrice) {
        this.monthlyPrice = monthlyPrice;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public void setPublished(boolean published) {
        this.published = published;
    }

    public void setPaystackPlanCode(String paystackPlanCode) {
        this.paystackPlanCode = paystackPlanCode;
    }
}

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

/** A sales lead in the marketing funnel (§11.8). Tenant-scoped via RLS. */
@Entity
@Table(name = "marketing_leads")
public class MarketingLead {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    private String email;

    private String phone;

    private String source;

    @Column(nullable = false)
    private String stage = "new";

    @Column(nullable = false)
    private BigDecimal value = BigDecimal.ZERO;

    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected MarketingLead() {
    }

    public MarketingLead(UUID tenantId, String name, String email, String phone, String source) {
        this.tenantId = tenantId;
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.source = source;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getSource() {
        return source;
    }

    public String getStage() {
        return stage;
    }

    public BigDecimal getValue() {
        return value;
    }

    public String getNotes() {
        return notes;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void moveToStage(String stage) {
        if (stage != null && !stage.isBlank()) {
            this.stage = stage;
        }
    }

    public void setName(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public void setValue(BigDecimal value) {
        if (value != null) {
            this.value = value;
        }
    }
}

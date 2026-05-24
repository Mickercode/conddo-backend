package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A customer in a tenant's CRM. Tenant-scoped: every row carries tenant_id and
 * is protected by the {@code tenant_isolation} RLS policy. Application code
 * never filters by tenant_id manually — PostgreSQL does it.
 */
@Entity
@Table(name = "customers")
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    private String email;

    private String phone;

    private String notes;

    @Column(name = "total_spent", nullable = false)
    private BigDecimal totalSpent = BigDecimal.ZERO;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags")
    private List<String> tags = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "measurements")
    private Map<String, Object> measurements;

    @Column(name = "last_active")
    private OffsetDateTime lastActive;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected Customer() {
    }

    public Customer(UUID tenantId, String fullName, String email, String phone, String notes) {
        this.tenantId = tenantId;
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.notes = notes;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getFullName() {
        return fullName;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getNotes() {
        return notes;
    }

    public BigDecimal getTotalSpent() {
        return totalSpent;
    }

    public List<String> getTags() {
        return tags;
    }

    public Map<String, Object> getMeasurements() {
        return measurements;
    }

    public OffsetDateTime getLastActive() {
        return lastActive;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    // ----- mutators (PATCH applies only the fields it was given) -------------

    public void rename(String fullName) {
        if (fullName != null && !fullName.isBlank()) {
            this.fullName = fullName;
        }
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public void setMeasurements(Map<String, Object> measurements) {
        this.measurements = measurements;
    }

    public void addTag(String tag) {
        if (tags == null) {
            tags = new ArrayList<>();
        }
        if (tag != null && !tag.isBlank() && !tags.contains(tag)) {
            tags.add(tag);
        }
    }

    public void removeTag(String tag) {
        if (tags != null) {
            tags.remove(tag);
        }
    }
}

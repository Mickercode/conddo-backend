package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * An order in a tenant's pipeline (§11.4). Tenant-scoped: every row carries
 * tenant_id and is protected by the {@code tenant_isolation} RLS policy. The
 * stage is a free-text name drawn from the tenant's pipeline (its overrides, or
 * the vertical's default stages). {@code amount} is the order total; deposit and
 * balance are derived from {@link OrderPayment}s, not stored here.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** "ORD-2894" — display label, filled by a DB default sequence on insert. */
    @Generated(event = EventType.INSERT)
    @Column(name = "reference", insertable = false, updatable = false)
    private String reference;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "customer_name")
    private String customerName;

    private String service;

    @Column(nullable = false)
    private String stage;

    @Column(nullable = false)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "due_date")
    private LocalDate dueDate;

    private String flag;

    private String notes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "measurements")
    private Map<String, Object> measurements;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected Order() {
    }

    public Order(UUID tenantId, UUID customerId, String customerName, String service, String stage) {
        this.tenantId = tenantId;
        this.customerId = customerId;
        this.customerName = customerName;
        this.service = service;
        this.stage = stage;
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

    public UUID getCustomerId() {
        return customerId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getService() {
        return service;
    }

    public String getStage() {
        return stage;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public String getFlag() {
        return flag;
    }

    public String getNotes() {
        return notes;
    }

    public Map<String, Object> getMeasurements() {
        return measurements;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    // ----- mutators (PATCH applies only the fields it was given) -------------

    public void setService(String service) {
        this.service = service;
    }

    public void setStage(String stage) {
        if (stage != null && !stage.isBlank()) {
            this.stage = stage;
        }
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount == null ? BigDecimal.ZERO : amount;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public void setFlag(String flag) {
        this.flag = flag;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public void setMeasurements(Map<String, Object> measurements) {
        this.measurements = measurements;
    }

    public void setCustomer(UUID customerId, String customerName) {
        this.customerId = customerId;
        this.customerName = customerName;
    }
}

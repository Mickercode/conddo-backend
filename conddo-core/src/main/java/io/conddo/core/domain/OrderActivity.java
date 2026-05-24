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
 * An entry in an {@link Order}'s activity log (§11.4): stage transitions,
 * payments, messages, notes. Tenant-scoped via RLS; append-only in practice
 * (the service only ever inserts).
 */
@Entity
@Table(name = "order_activity")
public class OrderActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String title;

    private String detail;

    private String actor;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected OrderActivity() {
    }

    public OrderActivity(UUID tenantId, UUID orderId, String type, String title, String detail, String actor) {
        this.tenantId = tenantId;
        this.orderId = orderId;
        this.type = type;
        this.title = title;
        this.detail = detail;
        this.actor = actor;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getDetail() {
        return detail;
    }

    public String getActor() {
        return actor;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}

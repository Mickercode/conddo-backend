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
 * A tenant's override of a pipeline stage (§11.4). When a tenant has no rows,
 * the dashboard uses the vertical's default stages; the first edit materialises
 * those defaults so they can be renamed/reordered/removed. Tenant-scoped via RLS.
 */
@Entity
@Table(name = "order_stages")
public class OrderStage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int position;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected OrderStage() {
    }

    public OrderStage(UUID tenantId, String name, int position) {
        this.tenantId = tenantId;
        this.name = name;
        this.position = position;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getPosition() {
        return position;
    }

    public void setName(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
    }

    public void setPosition(Integer position) {
        if (position != null) {
            this.position = position;
        }
    }
}

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
 * A connected social account (§11.8). Tenant-scoped via RLS; unique per platform
 * per tenant. Real OAuth is deferred — this records that a handle was linked.
 */
@Entity
@Table(name = "marketing_connections")
public class MarketingConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String platform;

    private String handle;

    @Column(nullable = false)
    private String status = "connected";

    @CreationTimestamp
    @Column(name = "connected_at", updatable = false)
    private OffsetDateTime connectedAt;

    protected MarketingConnection() {
    }

    public MarketingConnection(UUID tenantId, String platform, String handle) {
        this.tenantId = tenantId;
        this.platform = platform;
        this.handle = handle;
    }

    public UUID getId() {
        return id;
    }

    public String getPlatform() {
        return platform;
    }

    public String getHandle() {
        return handle;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getConnectedAt() {
        return connectedAt;
    }

    public void setHandle(String handle) {
        this.handle = handle;
    }
}

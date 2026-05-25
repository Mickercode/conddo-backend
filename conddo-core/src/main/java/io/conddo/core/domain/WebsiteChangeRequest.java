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
 * An owner's request to edit their website (§11.2). The site is built in Conddo
 * Studio (§8); this row captures the request on the tenant side. Linking it to a
 * Studio job ({@code studioJobId}) is a soft reference filled when the
 * cross-service hand-off is built. Tenant-scoped via RLS.
 */
@Entity
@Table(name = "website_change_requests")
public class WebsiteChangeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    private String area;

    @Column(nullable = false)
    private String details;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(name = "studio_job_id")
    private UUID studioJobId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected WebsiteChangeRequest() {
    }

    public WebsiteChangeRequest(UUID tenantId, String area, String details) {
        this.tenantId = tenantId;
        this.area = area;
        this.details = details;
    }

    /** Links this request to the Studio job created for it, moving it past PENDING. */
    public void markSubmittedToStudio(UUID studioJobId) {
        this.studioJobId = studioJobId;
        this.status = "SUBMITTED";
    }

    public UUID getId() {
        return id;
    }

    public String getArea() {
        return area;
    }

    public String getDetails() {
        return details;
    }

    public String getStatus() {
        return status;
    }

    public UUID getStudioJobId() {
        return studioJobId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}

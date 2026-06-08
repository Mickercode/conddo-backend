package io.conddo.studio.platform;

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
 * Immutable audit row for every Site-admin mutation
 * (SITE_REGISTRATION_ADMIN_SPEC §6). Lives in {@code studio.site_audit_log}.
 * {@code siteId} is a soft FK into {@code public.tenant_sites}, and
 * {@code byStaffId} a soft FK into {@code studio.staff_users}.
 */
@Entity
@Table(name = "site_audit_log", schema = "studio")
public class SiteAuditLog {

    public static final String ACTION_REGISTERED = "REGISTERED";
    public static final String ACTION_KEY_ROTATED = "KEY_ROTATED";
    public static final String ACTION_QA_APPROVED = "QA_APPROVED";
    public static final String ACTION_QA_REVOKED = "QA_REVOKED";
    public static final String ACTION_ACTIVATED = "ACTIVATED";
    public static final String ACTION_DEACTIVATED = "DEACTIVATED";
    public static final String ACTION_METADATA_UPDATED = "METADATA_UPDATED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "site_id", nullable = false)
    private UUID siteId;

    @Column(nullable = false)
    private String action;

    @Column(name = "by_staff_id", nullable = false)
    private UUID byStaffId;

    private String detail;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected SiteAuditLog() {
    }

    public SiteAuditLog(UUID siteId, String action, UUID byStaffId, String detail) {
        this.siteId = siteId;
        this.action = action;
        this.byStaffId = byStaffId;
        this.detail = detail;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSiteId() {
        return siteId;
    }

    public String getAction() {
        return action;
    }

    public UUID getByStaffId() {
        return byStaffId;
    }

    public String getDetail() {
        return detail;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}

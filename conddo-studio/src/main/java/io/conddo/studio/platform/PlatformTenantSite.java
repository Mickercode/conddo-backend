package io.conddo.studio.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Studio's read-write mirror of {@code public.tenant_sites}
 * (SITE_REGISTRATION_ADMIN_SPEC §3). Conddo-api owns the table schema
 * (V25 in the platform migrations); Studio reads + writes the same row
 * for the ops admin flows.
 *
 * <p>Maps to {@code public.tenant_sites} explicitly because Studio's
 * Hibernate {@code default_schema=studio} would otherwise mis-qualify
 * the lookup. The Studio service connects as {@code conddo_owner}, the
 * RLS owner role — it sees every tenant_sites row across the platform
 * without RLS scoping.
 *
 * <p>{@code lastKeyRotatedAt} + {@code lastKeyRotatedBy} are tracked in
 * the audit log table; they're not denormalised onto this row.
 */
@Entity
@Table(name = "tenant_sites", schema = "public")
public class PlatformTenantSite {

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    private String subdomain;

    @Column(name = "custom_domain")
    private String customDomain;

    @Column(name = "hosting_provider")
    private String hostingProvider;

    @Column(name = "site_type")
    private String siteType;

    @Column(name = "api_key_hash", nullable = false)
    private String apiKeyHash;

    @Column(name = "api_key_last4", nullable = false)
    private String apiKeyLast4;

    @Column(name = "is_active", nullable = false)
    private boolean active = false;

    @Column(name = "qa_approved", nullable = false)
    private boolean qaApproved = false;

    @Column(name = "qa_approved_by")
    private UUID qaApprovedBy;

    @Column(name = "qa_approved_at")
    private OffsetDateTime qaApprovedAt;

    @Column(name = "submitted_url")
    private String submittedUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected PlatformTenantSite() {
    }

    /** Ops registration — id + apiKeyHash + last4 + createdAt are set by the caller. */
    public PlatformTenantSite(UUID id, UUID tenantId, String subdomain, String customDomain,
                              String hostingProvider, String siteType, String submittedUrl,
                              String apiKeyHash, String apiKeyLast4,
                              OffsetDateTime createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.subdomain = subdomain;
        this.customDomain = customDomain;
        this.hostingProvider = hostingProvider;
        this.siteType = siteType;
        this.submittedUrl = submittedUrl;
        this.apiKeyHash = apiKeyHash;
        this.apiKeyLast4 = apiKeyLast4;
        this.createdAt = createdAt;
    }

    // ----- mutators ----------------------------------------------------------

    /** Issue a new key (initial or rotate). */
    public void rotateKey(String newHash, String newLast4) {
        this.apiKeyHash = newHash;
        this.apiKeyLast4 = newLast4;
    }

    public void setSubdomain(String subdomain) {
        this.subdomain = subdomain;
    }

    public void setCustomDomain(String customDomain) {
        this.customDomain = customDomain;
    }

    public void setHostingProvider(String hostingProvider) {
        this.hostingProvider = hostingProvider;
    }

    public void setSiteType(String siteType) {
        this.siteType = siteType;
    }

    public void setSubmittedUrl(String submittedUrl) {
        this.submittedUrl = submittedUrl;
    }

    public void approveQa(UUID staffId, OffsetDateTime at) {
        this.qaApproved = true;
        this.qaApprovedBy = staffId;
        this.qaApprovedAt = at;
    }

    public void revokeQa() {
        this.qaApproved = false;
        // Keep approvedBy/At so the audit log can still show who last approved.
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    // ----- getters -----------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getSubdomain() {
        return subdomain;
    }

    public String getCustomDomain() {
        return customDomain;
    }

    public String getHostingProvider() {
        return hostingProvider;
    }

    public String getSiteType() {
        return siteType;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public String getApiKeyLast4() {
        return apiKeyLast4;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isQaApproved() {
        return qaApproved;
    }

    public UUID getQaApprovedBy() {
        return qaApprovedBy;
    }

    public OffsetDateTime getQaApprovedAt() {
        return qaApprovedAt;
    }

    public String getSubmittedUrl() {
        return submittedUrl;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}

package io.conddo.studio.web.dto;

import io.conddo.studio.platform.PlatformTenant;
import io.conddo.studio.platform.PlatformTenantSite;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Wire shape for Site Registration admin
 * (SITE_REGISTRATION_ADMIN_SPEC §3 — TenantSiteSummary + TenantSiteDetail).
 * {@code apiKeyMasked} is always {@code sk_live_••••••••<last4>} so the
 * FE never has to reassemble it. Plaintext key is on
 * {@link RegisterKeyResponse}, surfaced once on register + rotate.
 */
public record PlatformSiteDto(
        UUID id,
        UUID tenantId,
        String tenantName,
        String tenantVertical,
        String subdomain,
        String customDomain,
        String hostingProvider,
        String siteType,
        String apiKeyMasked,
        String apiKeyLast4,
        boolean isActive,
        boolean qaApproved,
        UUID qaApprovedBy,
        String qaApprovedByName,
        OffsetDateTime qaApprovedAt,
        String submittedUrl,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static PlatformSiteDto of(PlatformTenantSite site, PlatformTenant tenant,
                                     String qaApprovedByName) {
        return new PlatformSiteDto(
                site.getId(),
                site.getTenantId(),
                tenant == null ? null : tenant.getName(),
                tenant == null ? null : tenant.getVerticalId(),
                site.getSubdomain(),
                site.getCustomDomain(),
                site.getHostingProvider(),
                site.getSiteType(),
                "sk_live_••••••••" + site.getApiKeyLast4(),
                site.getApiKeyLast4(),
                site.isActive(),
                site.isQaApproved(),
                site.getQaApprovedBy(),
                qaApprovedByName,
                site.getQaApprovedAt(),
                site.getSubmittedUrl(),
                site.getCreatedAt(),
                site.getUpdatedAt());
    }
}

package io.conddo.studio.web.dto;

/**
 * PATCH /admin/platform/sites/{id} — every field optional; only non-null
 * fields are touched. Key + qa state + active state have their own routes.
 */
public record PatchSiteRequest(
        String subdomain,
        String customDomain,
        String hostingProvider,
        String siteType,
        String submittedUrl) {
}

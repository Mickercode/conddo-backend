package io.conddo.studio.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** POST /admin/platform/sites — register a new site for a tenant. */
public record RegisterSiteRequest(
        @NotNull UUID tenantId,
        String subdomain,
        String customDomain,
        String hostingProvider,
        String siteType,
        String submittedUrl) {
}

package io.conddo.api.web.dto;

import io.conddo.core.domain.Tenant;

/**
 * A tenant's PUBLIC identity, resolved from its subdomain (§6.3). Public-safe
 * fields only — name, vertical, and branding for the client-facing site/landing.
 */
public record PublicTenantDto(
        String slug,
        String name,
        String tagline,
        String vertical,
        String primaryColor,
        String logoUrl,
        String status
) {
    public static PublicTenantDto from(Tenant t) {
        return new PublicTenantDto(t.getSlug(), t.getName(), t.getTagline(), t.getVerticalId(),
                t.getPrimaryColor(), t.getLogoUrl(), t.getStatus());
    }
}

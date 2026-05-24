package io.conddo.api.web.dto;

import io.conddo.core.domain.Tenant;

/**
 * Business profile (§11.11). {@code industry} (the vertical) and {@code subdomain}
 * (the slug) are read-only — surfaced here but never updated via this endpoint.
 */
public record BusinessProfileResponse(
        String name,
        String tagline,
        String description,
        String email,
        String phone,
        String industry,
        String subdomain,
        String status
) {
    public static BusinessProfileResponse from(Tenant t) {
        return new BusinessProfileResponse(t.getName(), t.getTagline(), t.getDescription(),
                t.getContactEmail(), t.getContactPhone(), t.getVerticalId(), t.getSlug(), t.getStatus());
    }
}

package io.conddo.api.web.dto;

import io.conddo.core.domain.Tenant;

/** Branding (§11.11): brand colour + logo (logo via media upload §11.12, here a URL). */
public record BrandingRequest(String primaryColor, String logoUrl) {

    public static BrandingRequest from(Tenant t) {
        return new BrandingRequest(t.getPrimaryColor(), t.getLogoUrl());
    }
}

package io.conddo.api.web.dto;

import io.conddo.core.domain.Tenant;

/**
 * GET / PUT response for /api/v1/settings/email-branding (V52).
 * Returns both the raw override fields (null when not set) and the
 * effective values so the FE can render "Currently: <X>" hints.
 */
public record EmailBrandingResponse(
        String fromName,
        String replyTo,
        String effectiveFromName,
        String effectiveReplyTo
) {

    public static EmailBrandingResponse from(Tenant tenant) {
        return new EmailBrandingResponse(
                tenant.getEmailFromName(),
                tenant.getEmailReplyTo(),
                tenant.effectiveEmailFromName(),
                tenant.effectiveEmailReplyTo());
    }
}

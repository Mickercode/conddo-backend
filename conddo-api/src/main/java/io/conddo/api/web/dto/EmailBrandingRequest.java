package io.conddo.api.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * PUT /api/v1/settings/email-branding (V52). Either field can be
 * null/blank to clear the override → outbound emails fall back to
 * the business name + contact email respectively.
 */
public record EmailBrandingRequest(
        @Size(max = 150) String fromName,
        @Email @Size(max = 254) String replyTo
) {
}

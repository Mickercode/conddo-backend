package io.conddo.api.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Starts a password reset for an account, scoped by tenant (PRD §13.1). */
public record ForgotPasswordRequest(
        @NotBlank String tenantSlug,
        @NotBlank @Email String email
) {
}

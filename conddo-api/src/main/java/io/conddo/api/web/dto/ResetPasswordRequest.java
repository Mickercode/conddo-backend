package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Completes a password reset using the token from the reset link (PRD §13.1). */
public record ResetPasswordRequest(
        @NotBlank String token,
        @NotBlank @Size(min = 8, message = "password must be at least 8 characters")
        String newPassword
) {
}

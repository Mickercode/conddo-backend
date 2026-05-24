package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/** Signup step 2 — the 4-digit code from the SMS. */
public record VerifyOtpRequest(
        @NotNull UUID registrationId,
        @Pattern(regexp = "\\d{4}", message = "code must be 4 digits") String code
) {
}

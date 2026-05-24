package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** "Resend code" on the OTP screen. */
public record ResendOtpRequest(
        @NotNull UUID registrationId
) {
}

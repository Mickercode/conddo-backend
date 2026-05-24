package io.conddo.api.web.dto;

import io.conddo.core.auth.RegistrationService;

import java.util.UUID;

/**
 * Returned by start/resend: the id the frontend carries through the wizard, plus
 * how long until a resend is allowed (drives the "Resend code in 0:30" timer).
 */
public record RegisterStartResponse(
        UUID registrationId,
        long resendCooldownSeconds
) {
    public static RegisterStartResponse from(RegistrationService.StartResult result) {
        return new RegisterStartResponse(result.registrationId(), result.resendCooldownSeconds());
    }
}

package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * Final signup step — business details gathered across the wizard (type + info +
 * plan). The tenant slug is auto-generated from {@code businessName}.
 * {@code businessType} maps to the tenant's vertical.
 */
public record CompleteRegistrationRequest(
        @NotNull UUID registrationId,
        @NotBlank String businessName,
        String businessType,
        String planId
) {
}

package io.conddo.payments.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * Body for the internal {@code POST /api/payments/internal/tenants} call —
 * conddo-api sends this from its {@code TenantActivatedEvent} listener.
 */
public record ProvisionTenantRequest(@jakarta.validation.constraints.NotNull UUID tenantId,
                                     @NotBlank String tenantSlug,
                                     @NotBlank String businessName,
                                     @NotBlank @Email String contactEmail) {
}

package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Create a booking (§11.5). Link a CRM customer via {@code customerId} (its name
 * is snapshotted) or pass a free-text {@code customerName}. {@code end} defaults
 * to {@code start} plus the tenant's slot duration.
 */
public record CreateBookingRequest(
        UUID customerId,
        String customerName,
        String service,
        @NotNull OffsetDateTime start,
        OffsetDateTime end,
        String mode,
        BigDecimal amount,
        String notes
) {
}

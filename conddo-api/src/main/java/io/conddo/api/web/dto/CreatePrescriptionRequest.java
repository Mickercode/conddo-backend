package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/**
 * Body for {@code POST /api/v1/prescriptions}. Exactly one of
 * {@code customerId} / {@code customerName} required (the service enforces);
 * when only the name is given, the customer is created on the fly with the
 * supplied {@code customerPhone}.
 */
public record CreatePrescriptionRequest(
        UUID customerId,
        String customerName,
        String customerPhone,
        @NotBlank String medication,
        String dosage,
        @Positive Integer quantity,
        @Positive Integer refillIntervalDays,
        String notes) {
}
